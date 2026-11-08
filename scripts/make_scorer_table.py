#!/usr/bin/env python3
"""The model folder the JVM scorer test reads (ScorerVectorsTest): the reference vectors' expected rows as a
test table, next to the calibration they were made with and tokens.txt.

  python -X utf8 scripts/make_scorer_table.py --engine <engine checkout> --vectors <scorer_ref/vectors> \
      --tokens <tokens.txt> --out build/scorer-table

The vectors' rows are not all ayahs of the expected table (learner clips, L2-KSU sentences), so every row goes in
as surah 1, ayah k (k = its 1-based place in MANIFEST.json's clips), converted by the engine's own
tools/zfexpected.py, exactly as the engine's tajweed/tests/cal_layout.py does. clips.json records which vector
is which ayah. The calibration is MANIFEST.json's, checked against its sha256.
"""
import argparse
import gzip
import hashlib
import json
import os
import shutil
import sys


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--engine", required=True, help="the engine checkout (tools/zfexpected.py)")
    ap.add_argument("--vectors", required=True, help="scorer_ref/vectors (MANIFEST.json)")
    ap.add_argument("--tokens", required=True, help="tj3 tokens.txt")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    sys.path.insert(0, os.path.join(a.engine, "tools"))
    import zfexpected

    man = json.load(open(os.path.join(a.vectors, "MANIFEST.json"), encoding="utf-8"))
    cal_path = man["calibration"]
    raw = open(cal_path, "rb").read()
    sha = hashlib.sha256(raw).hexdigest()
    if sha != man["calibration_sha256"]:
        sys.exit(f"{cal_path} is {sha}, MANIFEST.json says {man['calibration_sha256']}")
    cal = json.loads(raw)
    clips = [c for c in man["clips"] if c["context"] in cal["contexts"]]
    os.makedirs(a.out, exist_ok=True)
    rows, index = [], []
    for k, c in enumerate(clips, 1):
        r = json.load(open(os.path.join(a.vectors, c["row"]), encoding="utf-8"))
        rows.append(dict(r, surah=1, ayah=k, key=f"1:{k}"))
        index.append({"ayah": k, "context": c["context"], "name": c["name"], "heads": c["heads"],
                      "heads_sha256": c.get("heads_sha256"),
                      "verdicts": {p: v["file"] for p, v in c["verdicts"].items()}})
    with gzip.open(os.path.join(a.out, "vectors.jsonl.gz"), "wt", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    open(os.path.join(a.out, "calibration.json"), "wb").write(raw)
    shutil.copyfile(a.tokens, os.path.join(a.out, "tokens.txt"))
    zfexpected.convert(os.path.join(a.out, "vectors.jsonl.gz"), os.path.join(a.out, "tokens.txt"),
                       os.path.join(a.out, "expected_tj3.zfx"), allow_partial=True)
    json.dump({"vectors": os.path.abspath(a.vectors), "calibration_sha256": sha, "presets": man["presets"], "clips": index},
              open(os.path.join(a.out, "clips.json"), "w", encoding="utf-8"), indent=1)
    print(f"{a.out}: {len(index)} clips")


if __name__ == "__main__":
    main()
