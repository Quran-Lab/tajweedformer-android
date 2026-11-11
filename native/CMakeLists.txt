# libtajweedformer_jni: the JNI bridge over the engine's zf_tajweed.h (TajweedFormer V4), linked with
# the engine (the submodule at ../engine) built in its exact release configuration. The same file
# builds the Android library (from Gradle, per ABI) and the host library the JVM tests load
# (scripts/build_host.sh). SDK 3.0.0 links the engine's inference-only target: there is no scorer.
cmake_minimum_required(VERSION 3.24)
project(tajweedformer_jni C)

set(ZF_WRAPPER_ROOT "${CMAKE_CURRENT_LIST_DIR}/..")
set(ZF_ENGINE_DIR "${ZF_WRAPPER_ROOT}/engine" CACHE PATH "zipformer-sdk checkout")
if(NOT EXISTS "${ZF_ENGINE_DIR}/CMakeLists.txt")
  message(FATAL_ERROR "no engine in ${ZF_ENGINE_DIR}: run git submodule update --init")
endif()

# The engine's CMakeLists finds kissfft and the ONNX Runtime C API headers (the API types only; no
# runtime is linked) under ${CMAKE_SOURCE_DIR}/third_party, which built from here is this directory:
# third_party/kissfft is a link into the engine, third_party/onnxruntime the headers it was built
# against (v1.28.2).

# What an app links: the exact configuration fixed at compile time, the ZF_* environment ignored.
set(ZF_RELEASE ON)
# Off ARM (the desktop JVM host library) the engine is exact only through its ARMv7 NEON
# emulation; its portable C paths are not the reference arithmetic.
if(NOT CMAKE_SYSTEM_PROCESSOR MATCHES "^(arm|aarch64|ARM64)")
  set(ZF_NEON_EMU ON CACHE BOOL "" FORCE)
endif()
set(ZF_ENGINE_ONLY OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_subdirectory("${ZF_ENGINE_DIR}" engine EXCLUDE_FROM_ALL)
# libtajweedformer_opus, recitation sharing's Ogg Opus encoder (a library of its own)
add_subdirectory(opus)

if(NOT TARGET zf_tajweed_inference)
  message(FATAL_ERROR "the engine in ${ZF_ENGINE_DIR} has no zf_tajweed_inference target (the 3.0 engine, inference only)")
endif()
add_library(tajweedformer_jni SHARED tj_jni.c litert_npu.c)
target_compile_options(tajweedformer_jni PRIVATE -O2 -Wall -Wextra -Werror -ffp-contract=off)
# the engine's resampler and fbank for the NPU backend's host side (tj_jni.c "frontend")
target_compile_definitions(tajweedformer_jni PRIVATE TJ_WITH_FRONTEND=1)
target_link_libraries(tajweedformer_jni PRIVATE zf_tajweed_inference)
if(WIN32)
  # tajweedformer_jni.dll, the name System.loadLibrary("tajweedformer_jni") looks for on Windows
  set_target_properties(tajweedformer_jni PROPERTIES PREFIX "")
endif()
if(WIN32 AND NOT MSVC)
  # MinGW GCC (the engine's exact Windows build, docs/windows.md): zf_tajweed.h's visibility
  # attribute is ignored with a warning, and libgcc / winpthreads are linked in so the JNI library
  # needs no DLL beside it
  target_compile_options(tajweedformer_jni PRIVATE -Wno-attributes)
  target_link_options(tajweedformer_jni PRIVATE -static-libgcc -static)
endif()

if(ANDROID)
  # The engine's own symbols stay inside the library; only the JNI entry points are exported.
  target_link_options(tajweedformer_jni PRIVATE -Wl,--exclude-libs,ALL -Wl,--gc-sections)
  # litert_npu.c loads libLiteRt.so with dlopen (only when an NPU model is opened)
  target_link_libraries(tajweedformer_jni PRIVATE dl)
else()
  find_package(JNI REQUIRED COMPONENTS JVM)
  target_include_directories(tajweedformer_jni PRIVATE ${JNI_INCLUDE_DIRS})
endif()
