cmake_minimum_required(VERSION 3.22.1)
project(ice_scribe CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 只要推理库；示例/工具/服务全关，缩短编译时间也减小体积
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)
# llamafile 的 sgemm 面向 x86，ARM 上不需要，关掉更省事
set(GGML_LLAMAFILE         OFF CACHE BOOL "" FORCE)

# 本地已有 whisper.cpp 源码时可直接指定，避免每次拉取
set(WHISPER_CPP_DIR "" CACHE PATH "本地 whisper.cpp 源码目录（可选）")

if(WHISPER_CPP_DIR AND EXISTS "${WHISPER_CPP_DIR}/CMakeLists.txt")
    message(STATUS "使用本地 whisper.cpp: ${WHISPER_CPP_DIR}")
    add_subdirectory("${WHISPER_CPP_DIR}" "${CMAKE_BINARY_DIR}/whisper_cpp" EXCLUDE_FROM_ALL)
else()
    message(STATUS "通过 FetchContent 拉取 whisper.cpp v1.9.4")
    include(FetchContent)
    FetchContent_Declare(whisper_cpp
        GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
        GIT_TAG        v1.9.4
        GIT_SHALLOW    TRUE)
    FetchContent_MakeAvailable(whisper_cpp)
endif()

add_library(ice_scribe SHARED whisper_bridge.cpp)
target_link_libraries(ice_scribe PRIVATE whisper log)

# 桥接层只调用 API、不含 SIMD 内核，刻意不加 -march：
# 保持基线 armv8-a，任何 arm64 机型都能加载