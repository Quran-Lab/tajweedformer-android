#!/bin/bash
# libtajweedformer_jni with the V4 scorer only (native/scorer), for this machine:
#   scripts/build_scorer_host.sh TAJWEED_DIR [build dir]     (default build/scorer)
# TAJWEED_DIR is the engine checkout's tajweed folder (zf_tajweed.h and its sources). Works on Linux,
# macOS and Windows (Git Bash with MinGW GCC and Ninja). Prints the library's path.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
TJ=${1:?usage: build_scorer_host.sh TAJWEED_DIR [build dir]}
OUT=${2:-$ROOT/build/scorer}
GEN=()
command -v ninja >/dev/null && GEN=(-G Ninja)
cmake -S "$ROOT/native/scorer" -B "$OUT" ${GEN[@]+"${GEN[@]}"} -DCMAKE_BUILD_TYPE=Release -DTJ_TAJWEED_DIR="$TJ" >/dev/null
cmake --build "$OUT" --target tajweedformer_jni
ls "$OUT"/*tajweedformer_jni.* | grep -E '\.(dll|so|dylib)$'
