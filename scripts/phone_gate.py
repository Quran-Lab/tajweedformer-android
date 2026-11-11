#!/usr/bin/env python3
"""The release gate on a phone: the example app, built against the AAR under test, on one attached phone.

  python scripts/phone_gate.py --serial <adb serial> --aar <the AAR under test>
      --expected <selftest/expected_c16_l64.json of the model build the key is served>
      [--key-var QURANLAB_KEY] [--out <folder for logs>] [--rehearse]

What must hold for the gate to pass, all of it through the SDK's public API in the example app (GateActivity, the
gate's screen in the app's debug build), with a development apps key and the model as Quran Lab serves it:

1. the app starts and makes its Client;
2. `prefetch` downloads the model and `model()` verifies and opens it (the bytes and the build are printed);
3. every sample clip of the model, one take each through `model.listen(clip)` at c16_l64, gives the tokens the
   model build expects (`--expected`: the `selftest/expected_c16_l64.json` of the same model version; the phone
   prints token ids, this script compares them);
4. `diagnose()`: the key accepted, the config fresh, the model verified, no last error, no dropped chunk (the
   context it names is the app's setting at Quran Lab and is printed, not judged; the takes name c16_l64 themselves);
5. the phone's own verifier has nothing to say about the SDK: after `cmd package compile -m verify -f` no
   "Verification error" and no "failed lock verification" line names `ai.quranlab` (SDK 2.0.0 to 2.0.2 shipped a
   class every Android version refused, and no test on the JVM could see it);
6. the example's own screen (ExampleActivity) then starts and says "Ready: model <version>".

What the script does, in order:

- Reads the key from the environment variable named by --key-var; on Windows also from the user environment in the
  registry (HKCU\\Environment), because a process started before the variable was set does not have it. The key is
  handed to Gradle in the environment of that one process (QURANLAB_KEY, which the example's build reads). It is
  never printed, never logged, never on a command line; only whether it is there and how long it is are said.
- Builds `:examples:quickstart:assembleDebug -PsdkAar=<aar>`: the app against the AAR as a file, not against the
  library's sources.
- On the phone (only the one named by --serial): notes whether the screen is on and which packages are installed,
  stops if the example is already installed (it is not this script's to remove), wakes the screen, installs the
  app, starts GateActivity, and reads its lines from the log (tag TJ_GATE) until `RESULT done` (30 minutes at most).
- Runs the verifier, starts ExampleActivity and reads its status line from a dump of the screen.
- ALWAYS, also after a failure: uninstalls the app, puts the screen back to sleep when it was asleep, checks that
  the list of installed packages is what it was, and deletes the example's build folder (the generated resource and
  the APK hold the key).
- Prints a summary and exits 0 only when every point passed. The lines kept in --out are the gate's own (TJ_GATE),
  a crash of the app if there was one, and the verifier's; `diagnose()` is printed with anything key-like and the
  application id taken out.

--rehearse builds WITHOUT a key (the placeholder): the app must start and say that its key is not one. It proves
this script and the phone, not the SDK, and makes no request to any server.

Tried and rejected: an instrumented test (`am instrument`): some phones' power managers refuse to start a test
process that has no screen, and the command then waits for ever; `am start -W`: it waits for ever when the app
crashes at launch; a key in a Gradle property or a file in the repository: it would be on a command line or one
`git add` away from a commit.
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PACKAGE = "ai.quranlab.tajweedformer.example"
TAG = "TJ_GATE"
CONTEXT = "c16_l64"
# a line of the gate's screen in the log: logcat pads the tag before its colon ("I TJ_GATE : RESULT ...")
GATE_LINE = re.compile(r"\b%s\s*: (.*)$" % TAG)


def gate_lines(log):
    """What the gate's screen wrote, in order, without logcat's own columns."""
    return [m.group(1) for m in (GATE_LINE.search(l) for l in log.splitlines()) if m]


def read_key(name):
    """The key from the process environment, else (Windows) from the user environment in the registry. Never printed."""
    value = os.environ.get(name)
    if not value and os.name == "nt":
        try:
            import winreg
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, "Environment") as k:
                value = winreg.QueryValueEx(k, name)[0]
        except OSError:
            value = None
    return (value or "").strip() or None


class Phone:
    def __init__(self, adb, serial):
        self.base = [adb, "-s", serial]

    def run(self, *args, timeout=120):
        r = subprocess.run(self.base + list(args), capture_output=True, timeout=timeout)
        return r.returncode, (r.stdout + r.stderr).decode("utf-8", "replace").replace("\r", "")

    def shell(self, command, timeout=120):
        return self.run("shell", command, timeout=timeout)[1]

    def packages(self):
        return sorted(line.strip() for line in self.shell("pm list packages").splitlines() if line.strip())

    def awake(self):
        return "mWakefulness=Awake" in self.shell("dumpsys power | grep mWakefulness=")

    def now(self):
        return self.shell("date '+%m-%d %H:%M:%S.000'").strip()

    def log_since(self, since, *filters):
        """The log lines since [since] (the phone's clock), for the given tag filters; the buffer is never cleared."""
        return self.run("logcat", "-d", "-v", "threadtime", "-t", since, *filters, timeout=180)[1]


def screen_texts(phone):
    """The texts on the phone's screen now (uiautomator), newlines inside a text as ` | `."""
    code, xml = phone.run("exec-out", "uiautomator", "dump", "/dev/tty", timeout=90)
    if "<node" not in xml:
        mine = "/data/local/tmp/tj_phone_gate_ui.xml"
        phone.shell("uiautomator dump %s >/dev/null 2>&1" % mine, timeout=90)
        xml = phone.shell("cat %s; rm -f %s" % (mine, mine), timeout=90)
    return [t.replace("&#10;", " | ").replace("&quot;", '"').replace("&amp;", "&") for t in re.findall(r'text="([^"]*)"', xml) if t]


def scrub(text):
    """Nothing key-like and no application id in what is printed or kept."""
    text = re.sub(r"ql[a-z]_v\d+_[A-Za-z0-9_.-]+", "<key>", text)
    return text.replace(PACKAGE, "<app id>")


def main():
    # each line as it is said, also into a pipe: the gate takes minutes
    sys.stdout.reconfigure(line_buffering=True)
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True, help="the adb serial of the one phone the gate may touch")
    ap.add_argument("--aar", required=True)
    ap.add_argument("--expected", required=True)
    ap.add_argument("--key-var", default="QURANLAB_KEY")
    ap.add_argument("--out", default=os.path.join(ROOT, "build", "phone-gate"))
    ap.add_argument("--rehearse", action="store_true")
    a = ap.parse_args()

    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        local = os.path.join(ROOT, "local.properties")
        if os.path.isfile(local):
            for line in open(local, encoding="utf-8"):
                if line.startswith("sdk.dir="):
                    sdk = line.split("=", 1)[1].strip()
    if not sdk:
        sys.exit("the Android SDK is not known: set ANDROID_HOME, or sdk.dir in local.properties")
    adb = os.path.join(sdk, "platform-tools", "adb.exe" if os.name == "nt" else "adb")
    aar = os.path.abspath(a.aar)
    if not os.path.isfile(aar):
        sys.exit("no AAR at " + aar)
    expected = {name: clip["tokens"] for name, clip in json.load(open(a.expected, encoding="utf-8"))["clips"].items()}
    os.makedirs(a.out, exist_ok=True)

    key = None if a.rehearse else read_key(a.key_var)
    if a.rehearse:
        print("rehearsal: no key is used; the app must start and say that its key is not one")
    elif key is None:
        sys.exit("the key is missing: the variable %s is not set (the process environment%s)" %
                 (a.key_var, " and the user environment in the registry" if os.name == "nt" else ""))
    else:
        print("the key: present in %s, %d characters" % (a.key_var, len(key)))

    phone = Phone(adb, a.serial)
    code, state = phone.run("get-state")
    if "device" not in state:
        sys.exit("the phone %s is not attached: %s" % (a.serial, state.strip()))
    props = [phone.shell("getprop " + p).strip() for p in ("ro.product.model", "ro.build.version.release", "ro.build.version.sdk", "ro.product.cpu.abi")]
    print("phone: %s, Android %s (API %s), %s" % tuple(props))

    build_dir = os.path.join(ROOT, "examples", "quickstart", "build")
    apk = os.path.join(build_dir, "outputs", "apk", "debug", "quickstart-debug.apk")
    failures = []
    takes = {}
    result = {}
    installed = False
    was_awake = phone.awake()
    before = phone.packages()
    try:
        # ---- build the example against the AAR; the key only in this process's environment
        env = dict(os.environ)
        env.pop("QURANLAB_KEY", None)
        if key is not None:
            env["QURANLAB_KEY"] = key
        gradlew = os.path.join(ROOT, "gradlew.bat" if os.name == "nt" else "gradlew")
        command = ([os.environ.get("COMSPEC", "cmd.exe"), "/c", gradlew] if os.name == "nt" else [gradlew]) + [
            "-p", ROOT, "--console=plain", "--no-daemon", ":examples:quickstart:assembleDebug", "-PsdkAar=" + aar]
        print("building the example against " + os.path.basename(aar))
        with open(os.path.join(a.out, "build.log"), "w", encoding="utf-8", errors="replace") as log:
            code = subprocess.call(command, env=env, stdout=log, stderr=subprocess.STDOUT)
        del env
        if code != 0 or not os.path.isfile(apk):
            sys.exit("the example did not build against the AAR (build.log in %s)" % a.out)
        with open(os.path.join(a.out, "build.log"), encoding="utf-8", errors="replace") as log:
            text = log.read()
        if ":tajweedformer:compile" in text:
            sys.exit("the example was built against the library's sources, not against the AAR")
        if key is not None and key in text:
            os.remove(os.path.join(a.out, "build.log"))
            sys.exit("the build's output held the key: the log was deleted, nothing was installed")

        # ---- the phone
        if any(p == "package:" + PACKAGE for p in before):
            sys.exit("the example app is already installed on the phone: it is not this script's to remove")
        if not was_awake:
            phone.shell("input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard")
        since = phone.now()
        code, out = phone.run("install", apk, timeout=600)
        installed = "Success" in out
        if not installed:
            sys.exit("the install failed: " + out.strip()[-300:])
        phone.shell("am start -n %s/.GateActivity" % PACKAGE)
        started = time.time()
        deadline = started + 30 * 60
        lines = []
        while time.time() < deadline:
            time.sleep(5)
            log = phone.log_since(since, "-s", TAG + ":I", "AndroidRuntime:E")
            lines = gate_lines(log)
            crash = [l for l in log.splitlines() if "AndroidRuntime" in l and ("FATAL" in l or PACKAGE in l or "\tat " in l or "Error" in l or "Exception" in l)]
            if any("RESULT done" in l for l in lines):
                break
            if any(PACKAGE in l for l in crash):
                failures.append("the app crashed: " + scrub(next((l for l in crash if "Exception" in l or "Error" in l), crash[0]))[-400:])
                break
            if (lines or time.time() - started > 60) and not phone.shell("pidof " + PACKAGE).strip():
                time.sleep(3)
                log = phone.log_since(since, "-s", TAG + ":I")
                lines = gate_lines(log)
                if not any("RESULT done" in l for l in lines):
                    kill = [l for l in phone.log_since(since, "-s", "ActivityManager:I").splitlines() if PACKAGE in l and ("Killing" in l or "died" in l)]
                    failures.append("the app's process ended before the gate did: " + scrub(" | ".join(kill[-2:]))[-400:])
                break
        else:
            failures.append("the gate did not finish in 30 minutes")
        gate = [scrub(l) for l in lines]
        with open(os.path.join(a.out, "gate.log"), "w", encoding="utf-8") as f:
            f.write("\n".join(gate) + "\n")
        for l in gate:
            if not l.startswith("download: "):
                print("  " + (l if len(l) < 1500 else l[:1500] + " ..."))
        downloads = [l for l in gate if l.startswith("download: ")]
        if downloads:
            print("  (%d download progress lines, the last: %s)" % (len(downloads), downloads[-1]))

        if a.rehearse:
            ok = any(l.startswith("RESULT failed: invalid_argument") for l in gate) and any(l == "RESULT done: failed" for l in gate)
            if not ok:
                failures.append("the rehearsal expected the app to start and refuse the placeholder key")
            # the app must still be there a while after its start (some phones end an app started from a shell), and
            # its screen must be readable: the real gate needs both
            time.sleep(12)
            alive = bool(phone.shell("pidof " + PACKAGE).strip())
            texts = screen_texts(phone)
            seen = any("RESULT done" in t for t in texts)
            print("12 s after the gate's last line: the app's process is alive: %s; its screen was read: %s" % (alive, seen))
            if not alive:
                kill = [l for l in phone.log_since(since, "-s", "ActivityManager:I").splitlines() if PACKAGE in l and ("Killing" in l or "died" in l)]
                failures.append("the phone ended the app's process: " + scrub(" | ".join(kill[-2:]))[-400:])
            if not seen:
                failures.append("the screen could not be read (uiautomator): %d texts" % len(texts))
        else:
            if not any(l == "RESULT client: made" for l in gate):
                failures.append("the Client was not made")
            if not any(l.startswith("RESULT download: ") for l in gate):
                failures.append("no download line")
            if not any(l.startswith("RESULT model: ") for l in gate):
                failures.append("the model was not opened")
            for l in gate:
                m = re.match(r"RESULT take (\S+) %s: .* tokens ([0-9,]*)$" % CONTEXT, l)
                if m:
                    takes[m.group(1)] = ([int(x) for x in m.group(2).split(",") if x], "ended [SOURCE_END]" in l and "final true" in l)
            right = [c for c, (tokens, ended) in takes.items() if expected.get(c) == tokens and ended]
            result["takes"] = "%d of %d sample clips gave the expected tokens at %s" % (len(right), len(expected), CONTEXT)
            if sorted(right) != sorted(expected):
                failures.append("takes: %s; differing or missing: %s" % (result["takes"], sorted(set(expected) - set(right))))
            d = next((l[len("RESULT diagnose: "):] for l in gate if l.startswith("RESULT diagnose: ")), None)
            if d is None:
                failures.append("no diagnose() line")
            else:
                report = json.loads(d)
                result["diagnose"] = report
                wrong = []
                if report["key"] != {"accepted": True, "reason": None}:
                    wrong.append("key %s" % report["key"])
                if report["config"] != "fresh":
                    wrong.append("config %s" % report["config"])
                if not (report["model"] or {}).get("verified"):
                    wrong.append("model %s" % report["model"])
                if report["lastError"] is not None:
                    wrong.append("lastError %s" % report["lastError"])
                if report["droppedChunks"] != 0:
                    wrong.append("droppedChunks %s" % report["droppedChunks"])
                if wrong:
                    failures.append("diagnose() is not clean: " + ", ".join(wrong))
            if not any(l == "RESULT done: ok" for l in gate):
                failures.append("the gate's last line is not `RESULT done: ok`")

        # ---- the phone's own verifier over every class of the app
        since_verify = phone.now()
        out = phone.shell("cmd package compile -m verify -f " + PACKAGE, timeout=600)
        time.sleep(3)
        dex = [l for l in phone.log_since(since_verify).splitlines() if "dex2oat" in l]
        said = [l for l in dex if re.search(r"Verification error|failed to verify|failed lock verification|Verifier rejected", l)]
        ours = [scrub(l.split(": ", 1)[-1]) for l in said if "ai.quranlab" in l or "ai/quranlab" in l]
        with open(os.path.join(a.out, "verifier.log"), "w", encoding="utf-8") as f:
            f.write("\n".join(scrub(l) for l in said) + "\n")
        result["verifier"] = "%s; %d dex2oat lines, %d verifier lines, %d of them about the SDK" % (out.strip().splitlines()[-1] if out.strip() else "?", len(dex), len(said), len(ours))
        print("verifier: " + result["verifier"])
        if "Success" not in out or not dex:
            failures.append("the phone's verifier did not run: " + out.strip()[-200:])
        if ours:
            failures.append("the phone's verifier names the SDK: " + " | ".join(ours[:3]))

        # ---- the example's own screen
        if not a.rehearse:
            phone.shell("am start -n %s/.ExampleActivity" % PACKAGE)
            status = ""
            for _ in range(24):
                time.sleep(5)
                texts = screen_texts(phone)
                status = next((t for t in texts if t.startswith("Ready: model") or "sdk.quranlab.ai/docs/errors#" in t), "")
                if status:
                    buttons = [t for t in texts if t in ("Play sample", "Recite", "Stop", "Diagnose")]
                    result["screen"] = "status line \"%s\", buttons %s" % (scrub(status), buttons)
                    break
            print("the example's screen: " + result.get("screen", "no status line was read"))
            if not status.startswith("Ready: model"):
                failures.append("the example's own screen did not reach \"Ready: model\"")
    finally:
        # ---- always: the phone as it was, and nothing left that holds the key
        if installed:
            code, out = phone.run("uninstall", PACKAGE)
            print("uninstall: " + out.strip().splitlines()[-1])
        if not was_awake and phone.awake():
            phone.shell("input keyevent KEYCODE_SLEEP")
        after = phone.packages()
        same = after == before
        print("the phone's packages are what they were: %s (%d); the screen was %s before and is %s now" %
              (same, len(after), "on" if was_awake else "off", "on" if phone.awake() else "off"))
        if not same:
            failures.append("the phone's package list changed: " + str(sorted(set(after) ^ set(before))))
        shutil.rmtree(build_dir, ignore_errors=True)
        print("the example's build folder is deleted: %s" % (not os.path.exists(build_dir)))

    print()
    for k in ("takes", "verifier", "screen"):
        if k in result:
            print("%s: %s" % (k, result[k]))
    if "diagnose" in result:
        print("diagnose(): " + scrub(json.dumps(result["diagnose"])))
    if failures:
        print("GATE FAILED:")
        for f in failures:
            print("  " + f)
        sys.exit(1)
    print("GATE PASSED" if not a.rehearse else "REHEARSAL PASSED (no key, no server, nothing of the SDK proven)")


if __name__ == "__main__":
    main()
