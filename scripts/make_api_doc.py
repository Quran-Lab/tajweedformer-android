"""Writes docs/api.md of the Android SDK: every public declaration, read from the sources.

  python scripts/make_api_doc.py [--check]

The library is in Kotlin's explicit API mode, so what is public says `public`. For each source file with a public
top-level declaration this lists the type's signature line and its public members' signature lines, as written in
the code (annotations and bodies left out). PublicContractTest fails when docs/api.md lacks the name of a
public declaration, so the page cannot fall behind the code. --check only reports differences.
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


text = build()
path = os.path.join(WT, "docs", "api.md")
if "--check" in sys.argv:
    same = os.path.exists(path) and open(path, encoding="utf-8").read().replace("\r\n", "\n") == text
    print("docs/api.md is current" if same else "docs/api.md differs from the sources")
    sys.exit(0 if same else 1)
open(path, "w", encoding="utf-8", newline="\n").write(text)
print(len(text.splitlines()), "lines")
