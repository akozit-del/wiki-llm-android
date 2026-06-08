cmake_minimum_required(VERSION 3.22.1)
project("llm")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Disable all the heavy / Android-incompatible bits of llama.cpp.
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(LLAMA_NATIVE         OFF CACHE BOOL "" FORCE)

set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE       OFF CACHE BOOL "" FORCE)
set(GGML_BLAS            OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN          OFF CACHE BOOL "" FORCE)
set(GGML_CUDA            OFF CACHE BOOL "" FORCE)
set(GGML_HIPBLAS         OFF CACHE BOOL "" FORCE)
set(GGML_METAL           OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE          OFF CACHE BOOL "" FORCE)

# Sprint 4 (build-71): arm64 CPU speedups.
#
# - GGML_CPU_AARCH64=ON enables ggml's runtime Q4_0 online repack into the
#   4x8 / 8x8 layouts the CPU dot-product kernels expect. With this on,
#   a plain Q4_0 GGUF loaded via mmap is repacked once at startup into the
#   optimal layout for the host CPU (FEAT_DotProd / FEAT_I8MM).
# - GGML_CPU_KLEIDIAI=ON pulls in Arm's KleidiAI micro-kernels (i8mm/sme2),
#   a large bump on Snapdragon 8 Gen 3/Elite (S26 = i8mm), smaller on
#   Snapdragon 8 Gen 2 (S23 = dotprod only).
# - `-march=armv8.7a` enables the compile-time codegen path for i8mm/bf16
#   on capable CPUs. ggml still runtime-checks before dispatching, so a
#   single APK runs safely on both S23 (8 Gen 2) and S26 (8 Elite/Gen4).
if(ANDROID AND CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
    add_compile_options(-march=armv8.7a)
endif()
set(GGML_CPU_KLEIDIAI    ON  CACHE BOOL "" FORCE)
set(GGML_CPU_AARCH64     ON  CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        b9296
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama_cpp)

add_library(llm SHARED llm_jni.cpp)

# Only link the public `llama` target plus Android system libs.
# `common` is intentionally NOT linked – we don't include common.h.
target_link_libraries(llm
    PRIVATE
        llama
        android
        log)
