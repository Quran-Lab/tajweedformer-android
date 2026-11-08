# libtajweedformer_opus: the Opus encoder (and decoder, for checks) of recitation sharing, JNI over
# libopus 1.6.1 (native/third_party/opus-1.6.1, BSD-3-Clause; scripts/opus/vendor_opus.sh recreates
# that folder from the pinned release tarball and its SHA-256). Built by native/CMakeLists.txt for
# Android (per ABI) and standalone for the desktop JVM tests (scripts/opus/build_opus_host.sh).
#
# The same build choices as the Swift SDK's Opus.xcframework (scripts/opus/build_opus_xcframework.sh
# there): float build, no DNN features (DRED, OSCE, deep PLC), NEON intrinsics presumed on arm64.
cmake_minimum_required(VERSION 3.22)
project(tajweedformer_opus C)

set(TJ_OPUS_SRC "${CMAKE_CURRENT_LIST_DIR}/../third_party/opus-1.6.1")
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(OPUS_FIXED_POINT OFF CACHE BOOL "" FORCE)
set(OPUS_DRED OFF CACHE BOOL "" FORCE)
set(OPUS_OSCE OFF CACHE BOOL "" FORCE)
set(OPUS_DEEP_PLC OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
if(CMAKE_SYSTEM_PROCESSOR MATCHES "^(aarch64|arm64|ARM64)$")
  # every arm64 CPU has NEON: call the NEON kernels directly, as the Apple build does
  set(OPUS_MAY_HAVE_NEON ON CACHE BOOL "" FORCE)
  set(OPUS_PRESUME_NEON ON CACHE BOOL "" FORCE)
endif()
add_subdirectory("${TJ_OPUS_SRC}" opus EXCLUDE_FROM_ALL)

add_library(tajweedformer_opus SHARED opus_jni.c)
target_compile_options(tajweedformer_opus PRIVATE -O2 -Wall -Wextra -Werror)
target_link_libraries(tajweedformer_opus PRIVATE opus)
if(WIN32)
  set_target_properties(tajweedformer_opus PROPERTIES PREFIX "")
endif()
if(WIN32 AND NOT MSVC)
  target_link_options(tajweedformer_opus PRIVATE -static-libgcc -static)
endif()
if(ANDROID)
  target_link_options(tajweedformer_opus PRIVATE -Wl,--exclude-libs,ALL -Wl,--gc-sections)
else()
  find_package(JNI REQUIRED COMPONENTS JVM)
  target_include_directories(tajweedformer_opus PRIVATE ${JNI_INCLUDE_DIRS})
endif()
