#!/bin/bash
# Recreates native/third_party/opus-1.6.1 from the pinned libopus release: the tarball's SHA-256 is
# checked, and only what the float, no-DNN build compiles is kept (celt, silk, src, include, cmake,
# the source lists, CMakeLists.txt, configure.ac and Makefile.am for its version strings, and the
# licence, and from dnn only its headers and the small arm/x86 kernels libopus's CMake lists
# unconditionally). The dnn weights (about 23 MB) are left out: DRED, OSCE and deep PLC are off.
# Same release and checksum as the Swift SDK's Opus.xcframework.
#   scripts/opus/vendor_opus.sh [tarball]
set -euo pipefail
VERSION=1.6.1
SHA256=6ffcb593207be92584df15b32466ed64bbec99109f007c82205f0194572411a1
URL=https://downloads.xiph.org/releases/opus/opus-$VERSION.tar.gz
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
TAR=${1:-$WORK/opus-$VERSION.tar.gz}
[ -f "$TAR" ] || curl -sfL -o "$TAR" "$URL"
echo "$SHA256  $TAR" | sha256sum -c - >/dev/null || { echo "checksum mismatch for $TAR" >&2; exit 1; }
tar -xzf "$TAR" -C "$WORK"
SRC=$WORK/opus-$VERSION
DST=$ROOT/native/third_party/opus-$VERSION
rm -rf "$DST" && mkdir -p "$DST"
(cd "$SRC" && cp -r celt silk src include cmake CMakeLists.txt configure.ac Makefile.am ./*.mk COPYING AUTHORS README package_version "$DST/")
mkdir -p "$DST/dnn" && cp "$SRC"/dnn/*.h "$DST/dnn/" && cp -r "$SRC/dnn/arm" "$SRC/dnn/x86" "$DST/dnn/"
echo "$DST"
