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
