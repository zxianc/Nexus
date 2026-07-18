cmake_minimum_required(VERSION 3.10.2)
project(ai_audio_zygisk)

set(CMAKE_CXX_STANDARD 17)

# Dobby (vendored under magisk_module, pinned known-good commit)
set(DOBBY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../magisk_module/cpp/Dobby)
set(DOBBY_GENERATE_SHARED OFF CACHE BOOL "" FORCE)
set(DOBBY_DEBUG OFF CACHE BOOL "" FORCE)
add_subdirectory(${DOBBY_DIR} ${CMAKE_CURRENT_BINARY_DIR}/dobby_build)

# Payload loaded into audioserver
add_library(ai_hook SHARED audio_hook.cpp)
target_include_directories(ai_hook PRIVATE ${DOBBY_DIR}/include)
target_link_libraries(ai_hook dobby_static log)
target_compile_options(ai_hook PRIVATE -fno-exceptions -fno-rtti)

# Standalone CLI injector (service.sh backup path)
add_executable(inject inject_main.cpp inject.cpp)
target_link_libraries(inject log dl)

# Zygisk module library (renamed to arm64-v8a.so when packaging)
add_library(zygisk_mod SHARED zygisk_main.cpp inject.cpp)
target_include_directories(zygisk_mod PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(zygisk_mod log dl)
target_compile_options(zygisk_mod PRIVATE -fno-exceptions -fno-rtti)
