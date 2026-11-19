# SDK 4.0: the native libraries of the AAR besides the Core.
#
#   libtajweedformer_jni   the JNI bridge to the Quran Lab Core (core_jni.c). It does not link the Core: it opens
#                          libqlcore.so at run time (the Core ships as its own file, built and stripped by the Core's
#                          release tool, so its integrity digest is that of the file Quran Lab released). Only
#                          JNI_OnLoad is exported.
#   libtajweedformer_opus  recitation sharing's Ogg Opus encoder (a library of its own, unchanged since 3.x).
#
# The Core's public header is native/core/qlcore.h (quranlab-core 932f419, API version 1). The engine, the model's
# formats and the key handling are inside the Core; nothing of them is built here. The same file builds the desktop
# JVM's host bridge for the tests (scripts/build_host_core.sh), against the Core's Windows or Linux build.
cmake_minimum_required(VERSION 3.22)
project(tajweedformer_jni C)

set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# a shared machine: at most 4 compile jobs and 2 links at a time, whatever the build tool would start
set_property(GLOBAL PROPERTY JOB_POOLS compile_pool=4 link_pool=2)
set(CMAKE_JOB_POOL_COMPILE compile_pool)
set(CMAKE_JOB_POOL_LINK link_pool)

add_library(tajweedformer_jni SHARED core_jni.c)
target_include_directories(tajweedformer_jni PRIVATE ${CMAKE_CURRENT_LIST_DIR}/core)
target_compile_options(tajweedformer_jni PRIVATE -O2 -Wall -Wextra -Werror -fvisibility=hidden)
if(WIN32)
  # tajweedformer_jni.dll, the name System.loadLibrary("tajweedformer_jni") looks for on Windows
  set_target_properties(tajweedformer_jni PROPERTIES PREFIX "")
  target_link_libraries(tajweedformer_jni PRIVATE bcrypt)
  if(NOT MSVC)
    target_link_options(tajweedformer_jni PRIVATE -static-libgcc -static)
  endif()
endif()

if(ANDROID)
  # only JNI_OnLoad leaves the library; nothing else is exported
  target_link_options(tajweedformer_jni PRIVATE "-Wl,--version-script=${CMAKE_CURRENT_LIST_DIR}/core_jni.map" -Wl,--gc-sections)
  target_link_libraries(tajweedformer_jni PRIVATE dl)
else()
  find_package(JNI REQUIRED COMPONENTS JVM)
  target_include_directories(tajweedformer_jni PRIVATE ${JNI_INCLUDE_DIRS})
  if(UNIX)
    target_link_libraries(tajweedformer_jni PRIVATE dl pthread)
  endif()
endif()

# libtajweedformer_opus, recitation sharing's Ogg Opus encoder (a library of its own)
if(ANDROID)
  add_subdirectory(opus)
endif()
