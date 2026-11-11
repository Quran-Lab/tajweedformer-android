#!/usr/bin/env python3
"""Fail the build when a class would be rejected by Android's verifier for its monitor handling.

WHAT HAPPENED
    SDK 2.0.2 crashed at launch on Android (found on an Android 11 phone, armeabi-v7a):

        java.lang.VerifyError: Verifier rejected class ai.quranlab.tajweedformer.cloud.Telemetry:
        ... rec(Function1) ... expected to be within a catch-all for an instruction where a
        monitor is held

    `Telemetry.rec` was a `private inline fun` with `synchronized` inside `try/catch (Exception)`.
    Kotlin keeps a standalone copy of every inline function in the class. In that copy the marker
    call `InlineMarker.finallyStart` sits after the end of the catch-all range that guards the
    synchronized block and before `monitorexit`, so the only handler around it is the typed
    `catch (Exception)`. The JVM's verifier has no rule about that, which is why every unit test
    passed. Android's verifier has one, and it rejects the whole class. The method is never called
    (every call site is inlined), so only apps that shrink with R8 lose it and start.

THE RULE (Android's, read from its source)
    art/runtime/verifier/method_verifier.cc, "Handle instructions that can throw and that are
    sitting in a try block": when an instruction that can throw is inside a try block, a monitor
    is held after it, and none of its handlers is a catch-all (or java.lang.Throwable), the
    verifier fails with VERIFY_ERROR_BAD_CLASS_HARD:

        if (work_line_->MonitorStackDepth() > 0 && !has_catch_all_handler) {
          if (inst->Opcode() != Instruction::MONITOR_ENTER || work_line_->MonitorStackDepth() != 1) {
            Fail(VERIFY_ERROR_BAD_CLASS_HARD)
                << "expected to be within a catch-all for an instruction where a monitor is held";

    The same lines are in every release: android-5.0.0_r1 line 2847, 8.0.0_r1 line 3634,
    11.0.0_r1 line 3642, 14.0.0_r1 line 3515, 16.0.0_r1 line 4239 (and Dalvik before ART,
    vm/analysis/CodeVerify.cpp line 6021 at android-4.4_r1). So one static pass stands for every
    Android version and both 32-bit and 64-bit ART.

WHAT THIS SCRIPT CHECKS
    Every method of every class in the given AAR, JAR, class file or folder. For each method with
    a `monitorenter` it follows the control flow (exception edges included), keeps the number of
    monitors held at each instruction, and reports:

    REJECTED     the rule above: an instruction that can throw, a monitor held after it, inside a
                 try block, no catch-all among its handlers. Android refuses to load the class.
                 Fails the build.
    UNPROTECTED  an instruction that can throw while a monitor is held and no handler at all
                 around it. Android accepts this today (the instruction is not in a try block),
                 but the lock leaks if it throws, and one `try/catch` around it turns it into
                 REJECTED. This is what the standalone copy of an inline function with
                 `synchronized` looks like. Fails the build (`--art-only` makes it a warning).
    SLOW         paths reach an instruction with different numbers of monitors held, or a method
                 returns with a monitor held. Android accepts the class but reports "failed lock
                 verification and will run slower" and runs the method in the interpreter.
                 A warning; `--fail-on-slow` makes it fail.

    How the class file maps to what the phone verifies (the dex file):
    - "can throw" is judged as the dex instruction would be: field access, invokes, `new`, array
      access and length, casts, integer division, `athrow`, `monitorenter`, and `ldc` of anything
      but an int or a float. Loads, stores, arithmetic, branches and returns cannot, and neither
      does `monitorexit`: the verifier clears its "can throw" flag ("monitor-exit instructions
      are odd", same file), so it sends nothing to a handler and is never checked.
    - the handlers of an instruction are the exception-table entries that cover it, in table
      order, up to and including the first catch-all: D8 drops the handlers a catch-all shadows.
    - a handler is entered with the monitors held BEFORE the throwing instruction, and the rule
      looks at the monitors held AFTER it, exactly as the verifier does (so the `monitorenter`
      that takes the first monitor is exempt).
    - where paths disagree about the number of monitors, every count is followed, so the script
      fails closed. `synchronized` METHODS take no part: their monitor is not in the bytecode.

    What it cannot see: the register aliasing that only exists after D8 (some "will run slower"
    reports come from that), and anything R8 rewrites in an app that shrinks. It checks the
    classes the SDK ships, which is what a debug build and an app with shrinking off run.

TRIED AND REJECTED
    - An emulator job per API level: disk and minutes on every run, and it still misses 32-bit
      ART (the phone that found this is armeabi-v7a). The rule is identical in every release.
    - Reading the dex after D8: needs the Android build tools and a dex reader on the runner.
      The rule depends on try ranges and monitor depth, which D8 carries over from the class file.
    - Parsing `javap -c` text: its layout changes between JDKs.
    - A source search for `inline fun` with `synchronized`: it misses whatever a compiler version
      generates differently, and the bytecode is what the phone verifies.
    - Relying on R8 to drop the dead copy: only apps that shrink get that.

USAGE
    python3 check_monitor_handlers.py PATH [PATH ...]
        PATH is an .aar (its classes.jar and libs/*.jar), a .jar, a .class file or a folder.
        Exit 0: nothing found. Exit 1: at least one REJECTED or UNPROTECTED method.
        Exit 2: nothing to check, or a class file that could not be read (fails closed).
    python3 check_monitor_handlers.py --expect-rejected PATH [PATH ...]
        The check of the check: exit 0 only if EVERY path has at least one REJECTED method.
        CI runs it on the class from 2.0.2 kept in testdata/, so a change that blinds this
        script fails the build too.
    Options: --art-only (UNPROTECTED is a warning), --fail-on-slow, --quiet (findings only).

Standard library only; Python 3.8 or later.
"""
import argparse
import io
import os
import struct
import sys
import zipfile

MONITORENTER, MONITOREXIT = 0xC2, 0xC3
RETURNS = set(range(0xAC, 0xB2))
MAX_DEPTH = 32  # the verifier's kMaxMonitorStackDepth

NAMES = {
    0x12: "ldc", 0x13: "ldc_w", 0x14: "ldc2_w", 0x6C: "idiv", 0x6D: "ldiv", 0x70: "irem",
    0x71: "lrem", 0xB2: "getstatic", 0xB3: "putstatic", 0xB4: "getfield", 0xB5: "putfield",
    0xB6: "invokevirtual", 0xB7: "invokespecial", 0xB8: "invokestatic", 0xB9: "invokeinterface",
    0xBA: "invokedynamic", 0xBB: "new", 0xBC: "newarray", 0xBD: "anewarray", 0xBE: "arraylength",
    0xBF: "athrow", 0xC0: "checkcast", 0xC1: "instanceof", 0xC2: "monitorenter",
    0xC3: "monitorexit", 0xC5: "multianewarray",
}
for _op in range(0x2E, 0x36):
    NAMES[_op] = "array load"
for _op in range(0x4F, 0x57):
    NAMES[_op] = "array store"


def _lengths():
    table = [None] * 256

    def put(lo, hi, n):
        for op in range(lo, hi + 1):
            table[op] = n

    put(0x00, 0x0F, 1)
    table[0x10], table[0x11], table[0x12], table[0x13], table[0x14] = 2, 3, 2, 3, 3
    put(0x15, 0x19, 2)
    put(0x1A, 0x35, 1)
    put(0x36, 0x3A, 2)
    put(0x3B, 0x83, 1)
    table[0x84] = 3
    put(0x85, 0x98, 1)
    put(0x99, 0xA8, 3)
    table[0xA9] = 2
    put(0xAC, 0xB1, 1)
    put(0xB2, 0xB8, 3)
    table[0xB9], table[0xBA] = 5, 5
    table[0xBB], table[0xBC], table[0xBD] = 3, 2, 3
    table[0xBE], table[0xBF] = 1, 1
    table[0xC0], table[0xC1] = 3, 3
    table[0xC2], table[0xC3] = 1, 1
    table[0xC5] = 4
    table[0xC6], table[0xC7] = 3, 3
    table[0xC8], table[0xC9] = 5, 5
    return table


LENGTH = _lengths()
ALWAYS_THROW = (
    set(range(0x2E, 0x36)) | set(range(0x4F, 0x57)) | {0x6C, 0x6D, 0x70, 0x71}
    | set(range(0xB2, 0xC3)) | {0xC5}
)  # 0xC3 monitorexit is left out on purpose: the verifier clears its "can throw" flag


class Unreadable(Exception):
    pass


class ClassFile:
    """The parts of a class file this check needs: constant pool, methods, code, handlers."""

    def __init__(self, data):
        self.data = data
        self.pos = 0
        if self.u4() != 0xCAFEBABE:
            raise Unreadable("not a class file")
        self.u2()
        self.u2()
        self.pool = self._pool()
        self.u2()
        self.name = self.class_name(self.u2())
        self.u2()
        for _ in range(self.u2()):
            self.u2()
        for _ in range(self.u2()):  # fields
            self.pos += 6
            self._skip_attributes()
        self.methods = []
        for _ in range(self.u2()):
            access, name, desc = self.u2(), self.utf8(self.u2()), self.utf8(self.u2())
            code = None
            for _ in range(self.u2()):
                attr, size = self.utf8(self.u2()), self.u4()
                end = self.pos + size
                if attr == "Code":
                    code = self._code()
                self.pos = end
            self.methods.append((access, name, desc, code))
        self.source = None
        for _ in range(self.u2()):
            attr, size = self.utf8(self.u2()), self.u4()
            if attr == "SourceFile":
                self.source = self.utf8(struct.unpack_from(">H", self.data, self.pos)[0])
            self.pos += size

    def u1(self):
        v = self.data[self.pos]
        self.pos += 1
        return v

    def u2(self):
        v = struct.unpack_from(">H", self.data, self.pos)[0]
        self.pos += 2
        return v

    def u4(self):
        v = struct.unpack_from(">I", self.data, self.pos)[0]
        self.pos += 4
        return v

    def _pool(self):
        count = self.u2()
        pool = [None] * count
        i = 1
        while i < count:
            tag = self.u1()
            if tag == 1:
                n = self.u2()
                pool[i] = (tag, self.data[self.pos:self.pos + n])
                self.pos += n
            elif tag in (3, 4):
                pool[i] = (tag, None)
                self.pos += 4
            elif tag in (5, 6):
                pool[i] = (tag, None)
                self.pos += 8
                i += 1
            elif tag in (7, 8, 16, 19, 20):
                pool[i] = (tag, self.u2())
            elif tag in (9, 10, 11, 12, 17, 18):
                pool[i] = (tag, (self.u2(), self.u2()))
            elif tag == 15:
                pool[i] = (tag, (self.u1(), self.u2()))
            else:
                raise Unreadable("constant pool tag %d" % tag)
            i += 1
        return pool

    def utf8(self, index):
        return self.pool[index][1].decode("utf-8", "replace")

    def class_name(self, index):
        return self.utf8(self.pool[index][1])

    def member(self, index):
        """'owner.name desc' of a field or method reference, for messages."""
        entry = self.pool[index]
        if entry is None or entry[0] not in (9, 10, 11):
            return ""
        owner, nat = entry[1]
        name, desc = self.pool[nat][1]
        return "%s.%s %s" % (self.class_name(owner), self.utf8(name), self.utf8(desc))

    def _skip_attributes(self):
        for _ in range(self.u2()):
            self.u2()
            size = self.u4()  # read first: `self.pos += self.u4()` would lose these four bytes
            self.pos += size

    def _code(self):
        self.u2()
        self.u2()
        n = self.u4()
        code = self.data[self.pos:self.pos + n]
        self.pos += n
        handlers = []
        for _ in range(self.u2()):
            start, end, target, ctype = self.u2(), self.u2(), self.u2(), self.u2()
            handlers.append((start, end, target, self.class_name(ctype) if ctype else None))
        lines = []
        for _ in range(self.u2()):
            attr, size = self.utf8(self.u2()), self.u4()
            end = self.pos + size
            if attr == "LineNumberTable":
                for _ in range(self.u2()):
                    lines.append((self.u2(), self.u2()))
            self.pos = end
        return code, handlers, sorted(lines)


def decode(code):
    """pc -> (opcode, next pc, branch targets or None, falls through)."""
    insns = {}
    pc, n = 0, len(code)
    while pc < n:
        op = code[pc]
        targets, falls = None, True
        if op == 0xAA or op == 0xAB:  # tableswitch, lookupswitch
            base = (pc + 4) & ~3
            default = struct.unpack_from(">i", code, base)[0]
            targets = [pc + default]
            if op == 0xAA:
                low, high = struct.unpack_from(">ii", code, base + 4)
                count = high - low + 1
                for k in range(count):
                    targets.append(pc + struct.unpack_from(">i", code, base + 12 + 4 * k)[0])
                nxt = base + 12 + 4 * count
            else:
                count = struct.unpack_from(">i", code, base + 4)[0]
                for k in range(count):
                    targets.append(pc + struct.unpack_from(">i", code, base + 12 + 8 * k)[0])
                nxt = base + 8 + 8 * count
            falls = False
        elif op == 0xC4:  # wide
            nxt = pc + (6 if code[pc + 1] == 0x84 else 4)
            if code[pc + 1] == 0xA9:
                raise Unreadable("ret")
        else:
            size = LENGTH[op]
            if size is None:
                raise Unreadable("opcode 0x%02x" % op)
            nxt = pc + size
            if 0x99 <= op <= 0xA6 or op in (0xC6, 0xC7):
                targets = [pc + struct.unpack_from(">h", code, pc + 1)[0]]
            elif op == 0xA7:
                targets, falls = [pc + struct.unpack_from(">h", code, pc + 1)[0]], False
            elif op == 0xC8:
                targets, falls = [pc + struct.unpack_from(">i", code, pc + 1)[0]], False
            elif op in (0xA8, 0xA9, 0xC9):
                raise Unreadable("jsr or ret")
            elif op in RETURNS or op == 0xBF:
                falls = False
        insns[pc] = (op, nxt, targets, falls)
        pc = nxt
    return insns


def can_throw(cf, code, pc, op):
    if op in ALWAYS_THROW:
        return True
    if op in (0x12, 0x13, 0x14):
        index = code[pc + 1] if op == 0x12 else struct.unpack_from(">H", code, pc + 1)[0]
        return cf.pool[index][0] not in (3, 4, 5, 6)
    return False


def describe(cf, code, pc, op):
    text = NAMES.get(op, "opcode 0x%02x" % op)
    if 0xB2 <= op <= 0xB9:
        text += " " + cf.member(struct.unpack_from(">H", code, pc + 1)[0])
    return text


def line_of(lines, pc):
    found = None
    for start, line in lines:
        if start > pc:
            break
        found = line
    return found


def check_method(cf, code, handlers, lines):
    """Return a list of (kind, pc, text) for one method's code."""
    if MONITORENTER not in code:  # no monitor instruction anywhere: nothing can be held
        return []
    insns = decode(code)
    if not any(op == MONITORENTER for op, _, _, _ in insns.values()):
        return []
    findings, reported = [], set()

    def report(kind, pc, text):
        if (kind, pc) not in reported:
            reported.add((kind, pc))
            findings.append((kind, pc, text))

    def effective(pc):
        out = []
        for start, end, target, ctype in handlers:
            if start <= pc < end:
                out.append((target, ctype))
                if ctype is None:
                    break
        return out

    depths = {}
    work = [(0, 0)]
    while work:
        pc, depth = work.pop()
        seen = depths.setdefault(pc, set())
        if depth in seen:
            continue
        seen.add(depth)
        if pc not in insns:
            raise Unreadable("a branch into the middle of an instruction at %d" % pc)
        op, nxt, targets, falls = insns[pc]
        after = depth + (op == MONITORENTER) - (op == MONITOREXIT)
        if after < 0:
            report("SLOW", pc, "monitorexit with no monitor held")
            after = 0
        if after > MAX_DEPTH:
            report("SLOW", pc, "more than %d monitors held" % MAX_DEPTH)
            after = MAX_DEPTH
        if can_throw(cf, code, pc, op):
            around = effective(pc)
            for target, _ in around:
                work.append((target, depth))
            if after > 0 and not (op == MONITORENTER and after == 1):
                what = describe(cf, code, pc, op)
                if not around:
                    report("UNPROTECTED", pc, "%s can throw with %d monitor(s) held and no "
                           "handler around it" % (what, after))
                elif not any(t is None or t == "java/lang/Throwable" for _, t in around):
                    report("REJECTED", pc, "%s can throw with %d monitor(s) held and its only "
                           "handlers are [%s], none of them a catch-all"
                           % (what, after, ", ".join(t for _, t in around)))
        if op in RETURNS and depth > 0:
            report("SLOW", pc, "returns with %d monitor(s) held" % depth)
        if targets:
            for target in targets:
                work.append((target, after))
        if falls:
            work.append((nxt, after))
    for pc in sorted(depths):
        if len(depths[pc]) > 1:
            report("SLOW", pc, "paths arrive with different numbers of monitors held (%s)"
                   % ", ".join(str(d) for d in sorted(depths[pc])))
            break  # one line per method is enough: the rest follow from it
    out = []
    for kind, pc, text in sorted(findings, key=lambda f: f[1]):
        line = line_of(lines, pc)
        out.append((kind, pc, text + (" (line %d)" % line if line else "")))
    return out


def classes_in(path):
    """Yield (label, bytes) for every class file reachable from PATH."""
    def from_zip(label, blob):
        with zipfile.ZipFile(io.BytesIO(blob)) as z:
            for name in sorted(z.namelist()):
                if name.endswith(".class"):
                    yield label + "!" + name, z.read(name)
                elif name == "classes.jar" or (name.startswith("libs/") and name.endswith(".jar")):
                    yield from from_zip(label + "!" + name, z.read(name))

    if os.path.isdir(path):
        for root, dirs, files in os.walk(path):
            dirs.sort()
            for name in sorted(files):
                if name.endswith((".class", ".jar", ".aar")):
                    yield from classes_in(os.path.join(root, name))
    elif path.endswith(".class"):
        with open(path, "rb") as f:
            yield path, f.read()
    elif path.endswith((".jar", ".aar", ".zip")):
        with open(path, "rb") as f:
            yield from from_zip(path, f.read())
    else:
        raise Unreadable("%s is not an .aar, .jar, .class or folder" % path)


def check_path(path, quiet):
    """Return (classes, methods with a monitor, findings) for one PATH; findings are printed."""
    classes = monitors = 0
    counts = {"REJECTED": 0, "UNPROTECTED": 0, "SLOW": 0}
    for label, blob in classes_in(path):
        try:
            cf = ClassFile(blob)
        except (Unreadable, struct.error, IndexError, TypeError) as e:
            raise Unreadable("%s: %s" % (label, e))
        classes += 1
        for access, name, desc, code in cf.methods:
            if code is None:
                continue
            body, handlers, lines = code
            if MONITORENTER not in body:
                continue
            try:
                findings = check_method(cf, body, handlers, lines)
            except (Unreadable, struct.error, IndexError) as e:
                raise Unreadable("%s %s%s: %s" % (cf.name, name, desc, e))
            if any(op == MONITORENTER for op, _, _, _ in decode(body).values()):
                monitors += 1
            worst = {}
            for kind, pc, text in findings:
                worst.setdefault(kind, []).append((pc, text))
            for kind in ("REJECTED", "UNPROTECTED", "SLOW"):
                if kind in worst:
                    counts[kind] += 1
                    print("%s  %s.%s%s%s" % (kind, cf.name.replace("/", "."), name, desc,
                                              "  [%s]" % cf.source if cf.source else ""))
                    for pc, text in worst[kind]:
                        print("    at %d: %s" % (pc, text))
    if not quiet:
        print("%s: %d classes, %d methods that take a monitor; %d rejected, %d unprotected, "
              "%d slow" % (path, classes, monitors, counts["REJECTED"], counts["UNPROTECTED"],
                           counts["SLOW"]))
    return classes, counts


def main(argv=None):
    p = argparse.ArgumentParser(description="Android's monitor and catch-all rule, checked on "
                                "class files. See the top of this file.")
    p.add_argument("paths", nargs="+", metavar="PATH")
    p.add_argument("--expect-rejected", action="store_true",
                   help="succeed only if every PATH has a REJECTED method")
    p.add_argument("--art-only", action="store_true", help="UNPROTECTED does not fail")
    p.add_argument("--fail-on-slow", action="store_true", help="SLOW fails too")
    p.add_argument("--quiet", action="store_true", help="print findings only")
    a = p.parse_args(argv)
    failed = False
    for path in a.paths:
        try:
            classes, counts = check_path(path, a.quiet)
        except (Unreadable, OSError, zipfile.BadZipFile) as e:
            print("CANNOT CHECK  %s" % e)
            return 2
        if classes == 0:
            print("CANNOT CHECK  %s holds no class files" % path)
            return 2
        if a.expect_rejected:
            if counts["REJECTED"] == 0:
                print("EXPECTED A REJECTION in %s and found none: this check has gone blind" % path)
                failed = True
        elif (counts["REJECTED"] or (counts["UNPROTECTED"] and not a.art_only)
              or (counts["SLOW"] and a.fail_on_slow)):
            failed = True
    if a.expect_rejected and not failed and not a.quiet:
        print("as expected: the known-bad class is rejected")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
