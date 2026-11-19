"""Writes docs/api.md and NAMES.md of the Android SDK: every public declaration, read from the sources.

  python scripts/make_api_doc.py [--check]

The library is in Kotlin's explicit API mode, so what is public says `public`. For each source file with a public
top-level declaration this lists the type's signature line and its public members' signature lines, as written in
the code (annotations and bodies left out). PublicContractTest fails when docs/api.md lacks the name of a
public declaration, so the page cannot fall behind the code. NAMES.md is the short form: every public type with the
names of its public functions and properties, one row each. --check only reports differences.
"""
import os
import re
import sys

WT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SETS = [("tajweedformer/src/main/kotlin", "Android and the desktop JVM"),
        ("tajweedformer/src/android/kotlin", "Android only"),
        ("tajweedformer/src/jvm/kotlin", "Desktop JVM only"),
        ("tajweedformer-compose/src/main/kotlin", "Jetpack Compose (`ai.quranlab:tajweedformer-compose`)")]
ORDER = ["TajweedClient", "TajweedClientAndroid", "TajweedClientJvm", "TajweedOptions", "TajweedModel", "TajweedSession",
         "TajweedChunk", "TajweedHeads", "TajweedEvent", "TajweedBackend", "TajweedModelData", "TajweedCalibration",
         "TajweedSharing", "TajweedDiagnosis", "TajweedError", "Microphone", "SharingViews", "SharingCompose"]


def signature(lines, i):
    """The declaration that starts at line i on one line: a type by its kind and name, a member up to its body."""
    first = re.sub(r"^(@\w+(\([^)]*\))? )+", "", lines[i].strip())
    m = re.match(r"^public (?:(?:data|sealed|abstract|open|enum|annotation|fun|value|companion) )*(?:class|object|interface)(?: \w+)?", first)
    if m:
        return m.group(0)
    text, depth, out = "", 0, []
    for l in lines[i:i + 10]:
        t = re.sub(r"\s*//.*$", "", l.strip())
        text = (text + " " + t).strip()
        depth += t.count("(") - t.count(")")
        if depth <= 0:
            break
    text = re.sub(r"^(@\w+(\([^)]*\))? )+", "", text)
    # cut the body: the first " {" or " = " outside parentheses
    depth = 0
    for k, ch in enumerate(text):
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        elif depth == 0 and (text[k:k + 2] == " {" or text[k:k + 3] == " = "):
            text = text[:k]
            break
    text = re.sub(r"\s+get\(\)$", "", text)
    text = re.sub(r"\s+(by lazy|by)$", "", text)
    text = re.sub(r"\s*=$", "", text)
    return re.sub(r"\s+", " ", text).strip()


def declarations(path):
    lines = open(path, encoding="utf-8").read().replace("\r\n", "\n").split("\n")
    out = []
    for i, l in enumerate(lines):
        t = l.strip()
        if not re.match(r"^(@\w+(\([^)]*\))? )*public ", t):
            continue
        indent = len(l) - len(l.lstrip())
        out.append((indent // 4, signature(lines, i)))
    return out


def build():
    doc = ["# The public API\n",
           "Every public declaration of the SDK, read from the sources (the library is in Kotlin's explicit API mode: what",
           "is not listed here is internal). One package, `ai.quranlab.tajweedformer`; the Compose pieces are in",
           "`ai.quranlab.tajweedformer.compose`. Written by `scripts/make_api_doc.py`; `PublicContractTest` fails when a public name is missing here.\n"]
    for folder, title in SETS:
        files = []
        for d, _, names in os.walk(os.path.join(WT, folder)):
            for n in names:
                if n.endswith(".kt"):
                    decl = declarations(os.path.join(d, n))
                    if decl:
                        files.append((n[:-3], decl))
        if not files:
            continue
        files.sort(key=lambda f: (ORDER.index(f[0]) if f[0] in ORDER else 99, f[0]))
        doc.append(f"## {title}\n")
        for name, decl in files:
            doc.append("```kotlin")
            for depth, sig in decl:
                doc.append("    " * depth + sig)
            doc.append("```\n")
    return "\n".join(doc).rstrip() + "\n"


TYPE = re.compile(r"^public (?:(?:data|sealed|abstract|open|enum|annotation|fun|value|companion) )*(class|object|interface)(?: (\w+))?")


def kind_of(sig, m):
    if sig.startswith("public companion"):
        return "companion object"
    for k in ("enum", "data", "sealed", "annotation", "abstract", "fun"):
        if f" {k} " in f" {sig} ".replace("public ", "", 1):
            return f"{k} {m.group(1)}" if k != "fun" else "fun interface"
    return m.group(1)


def names():
    """NAMES.md: per source set, one row per public type: its kind, its public functions and its properties."""
    doc = ["# Public names\n",
           "Every public type and function of the Android SDK, read from the sources by `scripts/make_api_doc.py` (the",
           "signatures are in [docs/api.md](docs/api.md)). Package `ai.quranlab.tajweedformer` unless the row names a",
           "sub-package; a nested type is written `Outer.Inner`. Anything not listed is internal: the library is in Kotlin's",
           "explicit API mode, and the Quran Lab Core inside it has no public Kotlin name at all.\n"]
    for folder, title in SETS:
        rows = []
        for d, _, ns in os.walk(os.path.join(WT, folder)):
            for n in ns:
                if not n.endswith(".kt"):
                    continue
                pkg = ""
                for l in open(os.path.join(d, n), encoding="utf-8"):
                    if l.startswith("package "):
                        pkg = l.split()[1].strip()
                        break
                stack = []
                top = {"kind": "top-level functions", "name": n, "funs": [], "props": [], "pkg": pkg}
                for depth, sig in declarations(os.path.join(d, n)):
                    del stack[depth:]
                    m = TYPE.match(sig)
                    if m:
                        name = m.group(2) or "Companion"
                        row = {"kind": kind_of(sig, m), "name": (stack[-1]["name"] + "." if stack else "") + name,
                               "funs": [], "props": [], "pkg": pkg}
                        rows.append(row)
                        stack.append(row)
                        continue
                    owner = stack[-1] if stack else top
                    f = re.search(r"\bfun (?:<[^>]+> )?([\w.]+)\(", sig)
                    p = re.search(r"\b(?:val|var) (?:<[^>]+> )?([\w.]+)\s*:", sig)
                    if sig.startswith("public constructor"):
                        owner["funs"].append("constructor")
                    elif f:
                        owner["funs"].append(f.group(1))
                    elif p:
                        owner["props"].append(p.group(1))
                if top["funs"] or top["props"]:
                    rows.append(top)
        if not rows:
            continue
        rows.sort(key=lambda r: (r["pkg"], r["name"].lower()))
        doc.append(f"## {title}\n")
        doc.append("| Type | Kind | Functions | Properties |")
        doc.append("|---|---|---|---|")
        for r in rows:
            sub = r["pkg"].replace("ai.quranlab.tajweedformer", "").lstrip(".")
            name = f"`{r['name']}`" + (f" ({sub})" if sub else "")
            fs = ", ".join(f"`{x}`" for x in dict.fromkeys(r["funs"]))
            ps = ", ".join(f"`{x}`" for x in dict.fromkeys(r["props"]))
            doc.append(f"| {name} | {r['kind']} | {fs} | {ps} |")
        doc.append("")
    return "\n".join(doc).rstrip() + "\n"


outputs = [(os.path.join(WT, "docs", "api.md"), build()), (os.path.join(WT, "NAMES.md"), names())]
if "--check" in sys.argv:
    ok = True
    for path, text in outputs:
        same = os.path.exists(path) and open(path, encoding="utf-8").read().replace("\r\n", "\n") == text
        print(os.path.relpath(path, WT), "is current" if same else "differs from the sources")
        ok = ok and same
    sys.exit(0 if ok else 1)
for path, text in outputs:
    open(path, "w", encoding="utf-8", newline="\n").write(text)
    print(os.path.relpath(path, WT), len(text.splitlines()), "lines")
