#!/bin/bash
# The JNI library for this machine (macOS arm64, Linux, or Windows from Git Bash with MinGW GCC and
# Ninja on PATH, as the engine's docs/windows.md), for the JVM tests:
#   scripts/build_host.sh [build dir]     (default build/host)
# The engine is built exactly as for Android: ZF_RELEASE, no fp contraction.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT=${1:-$ROOT/build/host}
GEN=()
command -v ninja >/dev/null && GEN=(-G Ninja)
# ZF_ENGINE_DIR names another engine checkout than the submodule (the variable of the same name)
ENG=()
[ -n "${ZF_ENGINE_DIR:-}" ] && ENG=(-DZF_ENGINE_DIR="$ZF_ENGINE_DIR")
cmake -S "$ROOT/native" -B "$OUT" ${GEN[@]+"${GEN[@]}"} ${ENG[@]+"${ENG[@]}"} -DCMAKE_BUILD_TYPE=Release -DCMAKE_OSX_DEPLOYMENT_TARGET=13.0 >/dev/null
cmake --build "$OUT" --target tajweedformer_jni -j "$(getconf _NPROCESSORS_ONLN)"
ls "$OUT"/*tajweedformer_jni.* | grep -E '\.(so|dylib|dll)$'
