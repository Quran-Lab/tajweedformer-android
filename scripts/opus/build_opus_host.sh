#!/bin/bash
# libtajweedformer_opus for this machine (the desktop JVM tests of recitation sharing):
#   scripts/opus/build_opus_host.sh [build dir]      (default build/host-opus)
# Linux, macOS, or Windows from Git for Windows' bash with MinGW GCC, CMake and Ninja on PATH.
# Prints the library's path; the tests find it through TAJWEEDFORMER_OPUS_LIBRARY or java.library.path.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
OUT=${1:-$ROOT/build/host-opus}
GEN=()
command -v ninja >/dev/null && GEN=(-G Ninja)
cmake -S "$ROOT/native/opus" -B "$OUT" ${GEN[@]+"${GEN[@]}"} -DCMAKE_BUILD_TYPE=Release >/dev/null
cmake --build "$OUT" --target tajweedformer_opus >/dev/null
ls "$OUT"/*tajweedformer_opus.* | grep -E '\.(dll|so|dylib)$'
