#!/usr/bin/env python3
"""The bundle dry run builds what the publish workflow would upload, so its steps must be the publish workflow's.

  python3 scripts/check_bundle_steps.py

Fails (exit 1) unless
- .github/workflows/publish.yml and .github/workflows/bundle-dry-run.yml each have exactly one block of steps between
  the lines `# bundle-steps:begin` and `# bundle-steps:end`, and the two blocks are the same text, byte for byte;
- the dry run names nothing of the upload: no Central Portal secret, no Portal address, no request with curl, and
  no secret at all but the signing key;
- the dry run's triggers are exactly a push to a release-* branch and a start by hand: no pull request, no tag,
  nothing else.

CI runs it on every push, and the dry run runs it before it builds anything.
"""
import difflib
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BEGIN, END = "# bundle-steps:begin", "# bundle-steps:end"


def text(name):
    with open(os.path.join(ROOT, ".github", "workflows", name), encoding="utf-8", newline="") as f:
        return f.read().replace("\r\n", "\n")


def block(name, s):
    lines = s.split("\n")
    begins = [i for i, l in enumerate(lines) if l.strip() == BEGIN]
    ends = [i for i, l in enumerate(lines) if l.strip() == END]
    if len(begins) != 1 or len(ends) != 1 or ends[0] <= begins[0]:
        sys.exit("%s: expected one block between '%s' and '%s'" % (name, BEGIN, END))
    return lines[begins[0] + 1:ends[0]]


publish, dry = text("publish.yml"), text("bundle-dry-run.yml")
a, b = block("publish.yml", publish), block("bundle-dry-run.yml", dry)
failed = False
if a != b:
    failed = True
    print("the bundle steps of publish.yml and bundle-dry-run.yml differ:")
    for l in difflib.unified_diff(a, b, "publish.yml", "bundle-dry-run.yml", lineterm=""):
        print(l)
if not any("centralBundle" in l for l in a):
    failed = True
    print("the shared steps do not build the bundle (no centralBundle task between the markers)")
for word in ("MAVEN_CENTRAL", "central.sonatype.com", "curl ", "publisher/upload", "publishingType"):
    if word in dry:
        failed = True
        print("bundle-dry-run.yml names '%s': the dry run must have nothing of the upload" % word.strip())
for secret in sorted(set(re.findall(r"secrets\.([A-Za-z0-9_]+)", dry))):
    if secret != "SIGNING_KEY":
        failed = True
        print("bundle-dry-run.yml names the secret '%s': the dry run may name the signing key and no other" % secret)

# the triggers: the lines from `on:` to the next top-level key, comments and empty lines left out
lines = dry.split("\n")
starts = [i for i, l in enumerate(lines) if l.rstrip() == "on:"]
TRIGGERS = ["on:", "  push:", "    branches: ['release-*']", "  workflow_dispatch:"]
if len(starts) != 1:
    failed = True
    print("bundle-dry-run.yml: expected one top-level 'on:' line, found %d" % len(starts))
else:
    found = ["on:"]
    for l in lines[starts[0] + 1:]:
        if l.strip() == "" or l.lstrip().startswith("#"):
            continue
        if not l.startswith(" "):
            break
        found.append(l.rstrip())
    if found != TRIGGERS:
        failed = True
        print("bundle-dry-run.yml: its triggers must be exactly a push to release-* branches and a start by hand:")
        for l in difflib.unified_diff(TRIGGERS, found, "expected", "found", lineterm=""):
            print(l)
if failed:
    sys.exit(1)
print("the %d lines of bundle steps are the same in publish.yml and bundle-dry-run.yml; the dry run names nothing of the "
      "upload and no secret but the signing key; its triggers are a push to release-* and a start by hand" % len(a))
