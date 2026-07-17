cmake_minimum_required(VERSION 3.10)
project(ai_audio_hook)

# Build Dobby as static lib and link into ai_hook.so (single .so for Magisk)
set(DOBBY_GENERATE_SHARED OFF CACHE BOOL "" FORCE)
set(DOBBY_DEBUG OFF CACHE BOOL "" FORCE)
add_subdirectory(Dobby)

add_library(ai_hook SHARED audio_hook.cpp)
target_include_directories(ai_hook PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/Dobby/include)
target_link_libraries(ai_hook dobby_static log)
