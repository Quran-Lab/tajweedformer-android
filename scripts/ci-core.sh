#!/bin/bash
# CI helper (SDK 4.0): the Quran Lab Core's Android binaries of this release, in the folder prepareCore reads.
#   scripts/ci-core.sh VERSION DIR
# Downloads qlcore-VERSION-android-<abi>.tar.gz for each ABI from the release's folder on cdn.quranlab.ai (beside the
# AAR), checks every qlcore.so against native/core/CORE_RELEASE, and writes DIR/ship/android-<abi>/qlcore.so and
# DIR/record.json (the release and each file's SHA-256): the folder that -PcoreShip=DIR names. In a workflow it also
# sets ORG_GRADLE_PROJECT_coreShip, so every Gradle run of the job takes it. Nothing of the Core is built here.
set -euo pipefail
V=${1:?usage: ci-core.sh VERSION DIR}
D=${2:?usage: ci-core.sh VERSION DIR}
PIN=native/core/CORE_RELEASE
BASE="https://cdn.quranlab.ai/sdk/maven/ai/quranlab/tajweedformer/$V"
release=$(awk '$1 == "release" { print $2 }' "$PIN")
[ -n "$release" ] || { echo "$PIN names no release" >&2; exit 1; }
tmp=$(mktemp -d)
files=""
for abi in arm64-v8a armeabi-v7a x86_64; do
  t="android-$abi"
  want=$(awk -v t="$t" '$1 == t { print $3 }' "$PIN")
  [ -n "$want" ] || { echo "$PIN names no $t" >&2; exit 1; }
  curl --fail --retry 3 --retry-delay 10 -sSL -o "$tmp/$t.tar.gz" "$BASE/qlcore-$V-$t.tar.gz"
  mkdir -p "$tmp/$t" "$D/ship/$t"
  tar -xzf "$tmp/$t.tar.gz" -C "$tmp/$t"
  so=$(find "$tmp/$t" -type f -name qlcore.so | head -n 1)
  [ -n "$so" ] || { echo "qlcore-$V-$t.tar.gz has no qlcore.so" >&2; exit 1; }
  got=$(sha256sum "$so" | cut -d ' ' -f 1)
  [ "$got" = "$want" ] || { echo "$t: qlcore.so is $got, $PIN names $want" >&2; exit 1; }
  cp "$so" "$D/ship/$t/qlcore.so"
  files="$files${files:+, }\"ship/$t/qlcore.so\": \"$got\""
  echo "Core $release $t: $got"
done
printf '{"release": "%s", "files": {%s}}\n' "$release" "$files" > "$D/record.json"
rm -rf "$tmp"
if [ -n "${GITHUB_ENV:-}" ]; then echo "ORG_GRADLE_PROJECT_coreShip=$D" >> "$GITHUB_ENV"; fi
