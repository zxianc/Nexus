cmake_minimum_required(VERSION 3.10.2)
project(ai_audio_zygisk)

set(CMAKE_CXX_STANDARD 17)

# Payload loaded into audioserver
add_library(ai_hook SHARED audio_hook.cpp)
target_link_libraries(ai_hook log)

# Standalone CLI injector (service.sh backup path)
add_executable(inject inject_main.cpp inject.cpp)
target_link_libraries(inject log dl)

# Zygisk module library (renamed to arm64-v8a.so when packaging)
add_library(zygisk_mod SHARED zygisk_main.cpp inject.cpp)
target_include_directories(zygisk_mod PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(zygisk_mod log dl)
# Avoid C++ STL in zygote; we only use headers that are fine with system libstdc++
target_compile_options(zygisk_mod PRIVATE -fno-exceptions -fno-rtti)
