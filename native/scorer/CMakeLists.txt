# libtajweedformer_jni with the V4 scorer only: tj_jni.c over the engine's tajweed library built with
# its stub glue (engine_stub.c: no streams), for scoring heads that come from elsewhere and for the
# JVM test against the reference scorer's vectors. Any desktop C compiler (GCC, Clang, MinGW):
#   cmake -S native/scorer -B build/scorer -DTJ_TAJWEED_DIR=<engine checkout>/tajweed
# scripts/build_scorer_host.sh does this.
cmake_minimum_required(VERSION 3.24)
project(tajweedformer_jni_scorer C)
set(CMAKE_C_STANDARD 11)

set(TJ_TAJWEED_DIR "${CMAKE_CURRENT_LIST_DIR}/../../engine/tajweed" CACHE PATH "the engine's tajweed folder")
if(NOT EXISTS "${TJ_TAJWEED_DIR}/include/zf_tajweed.h")
  message(FATAL_ERROR "no zf_tajweed.h in ${TJ_TAJWEED_DIR}: point TJ_TAJWEED_DIR at the engine's tajweed folder")
endif()

# the scorer's bits must not depend on FMA (tajweed/CMakeLists.txt sets this only when it is the top project)
if(NOT MSVC)
  add_compile_options($<$<COMPILE_LANGUAGE:C>:-ffp-contract=off>)
endif()
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_subdirectory("${TJ_TAJWEED_DIR}" tajweed EXCLUDE_FROM_ALL)

find_package(JNI REQUIRED COMPONENTS JVM)
add_library(tajweedformer_jni SHARED ../tj_jni.c)
target_include_directories(tajweedformer_jni PRIVATE ${JNI_INCLUDE_DIRS})
target_link_libraries(tajweedformer_jni PRIVATE zf_tajweed)
if(MSVC)
  target_compile_options(tajweedformer_jni PRIVATE /W4)
else()
  target_compile_options(tajweedformer_jni PRIVATE -O2 -Wall -Wextra -Werror)
  if(WIN32)
    # MinGW ignores zf_tajweed.h's visibility attribute with a warning; and no libgcc DLL beside the JNI library
    target_compile_options(tajweedformer_jni PRIVATE -Wno-attributes)
    target_link_options(tajweedformer_jni PRIVATE -static-libgcc -static)
  endif()
endif()
