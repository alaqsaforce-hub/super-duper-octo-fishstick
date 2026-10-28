# VoiceDock offline speech: whisper.cpp (MIT, vendored v1.9.5, CPU backend only) + a tiny JNI bridge.
cmake_minimum_required(VERSION 3.22.1)
project(vdwhisper C CXX)
set(CMAKE_CXX_STANDARD 17)
set(W ${CMAKE_SOURCE_DIR}/whisper.cpp)

file(READ "${W}/CMakeLists.txt" MAIN_CMAKE)
string(REGEX MATCH "project\\(\"whisper\\.cpp\" VERSION ([0-9]+\\.[0-9]+\\.[0-9]+)\\)" _m "${MAIN_CMAKE}")
set(WHISPER_VERSION "${CMAKE_MATCH_1}")

# Generic armv8-a code (no -march=native, no fp16-only instructions) so it runs on every arm64 phone.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
add_subdirectory(${W}/ggml ggml)

add_library(vdwhisper SHARED
    ${W}/src/whisper.cpp
    ${W}/src/aneforge/whisper-aneforge.cpp
    whisper_jni.c)
target_include_directories(vdwhisper PRIVATE ${W}/include ${W}/src ${W}/ggml/include)
target_compile_definitions(vdwhisper PRIVATE WHISPER_VERSION="${WHISPER_VERSION}" GGML_USE_CPU)

# Always optimise (debug APKs too) — unoptimised whisper is unusably slow on a phone.
foreach(t vdwhisper ggml ggml-base ggml-cpu)
    if (TARGET ${t})
        target_compile_options(${t} PRIVATE -O3 -DNDEBUG -ffunction-sections -fdata-sections)
    endif()
endforeach()
target_compile_options(vdwhisper PRIVATE -fvisibility=hidden)
target_link_options(vdwhisper PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)

find_library(LOG_LIB log)
target_link_libraries(vdwhisper ggml ${LOG_LIB} android)
