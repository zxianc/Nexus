cmake_minimum_required(VERSION 3.10.2)
project(AI_Audio_Hook)

# 1. 编译替身可执行文件 (生成名字就叫 audioserver)
add_executable(audioserver wrapper.cpp)

# 2. 编译我们要注入的动态库 (生成 libai_hook.so)
add_library(ai_hook SHARED audio_hook.cpp)
target_link_libraries(ai_hook log)