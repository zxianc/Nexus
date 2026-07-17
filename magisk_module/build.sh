#!/bin/bash
# 你的 NDK 绝对路径，请根据实际情况修改E:\android\SDK\ndk\30.0.15729638\build\cmake\android.toolchain.cmake
NDK_PATH="E:\android\SDK\ndk\30.0.15729638\"
TOOLCHAIN="$NDK_PATH/build/cmake/android.toolchain.cmake"

echo "🧹 清理旧的构建文件..."
rm -rf cpp/build
rm -rf system

echo "🔨 正在使用 NDK 交叉编译 ARM64 动态库..."
mkdir -p cpp/build
cd cpp/build

# 核心 CMake 命令，指定编译为安卓 arm64-v8a 架构
cmake -DCMAKE_TOOLCHAIN_FILE=$TOOLCHAIN \
      -DANDROID_ABI="arm64-v8a" \
      -DANDROID_PLATFORM=android-30 \
      ..

make -j4
cd ../../

echo "📁 正在组装 Magisk 目录结构..."
# Magisk 要求把库放在 system/lib64 下，挂载后才会出现在手机的 /system/lib64 里
mkdir -p system/lib64
cp cpp/build/libai_hook.so system/lib64/

echo "📦 正在打包 Magisk 模块..."
rm -f ai_call_agent_module.zip
# 将所需文件打包成 zip，排除源码和构建缓存
zip -r ai_call_agent_module.zip module.prop customize.sh service.sh system/

echo "✅ 编译打包完成！产物: ai_call_agent_module.zip"