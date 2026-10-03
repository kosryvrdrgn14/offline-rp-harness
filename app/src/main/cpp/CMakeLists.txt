cmake_minimum_required(VERSION 3.22.1)
project(harness)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

add_library(harness SHARED native-lib.cpp)

target_link_libraries(harness
        PRIVATE
        llama
        android
        log)