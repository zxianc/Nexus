# AI Call Agent - Magisk 纯 Native 模块开发手记 (一)
**日期:** 2026-07-18
**模块目标:** 编译纯 C++ 动态库，并通过 Magisk 的 OverlayFS 无损挂载到系统 `/system/lib64/` 目录。
**避坑核心:** Windows 下的交叉编译环境配置、反斜杠路径灾难、以及 Magisk 的正确物理验证。

---

## 1. 编译与打包标准流程

在 Windows 环境下进行 NDK 交叉编译并打包 Magisk 模块，必须严格遵守以下流程以避免路径和换行符报错。

### 1.1 自动编译脚本 (`build.bat`)
为了避免手动敲击 CMake 命令出错，我们在根目录下使用以下批处理脚本。
*注意：脚本中严禁使用中文输出，以避免控制台乱码导致编译中断。*

\`\`\`bat
@echo off
setlocal
echo [INFO] Cleaning up old build files...
if exist cpp\build rmdir /s /q cpp\build
if exist system rmdir /s /q system

echo [INFO] Preparing build directories...
mkdir cpp\build
cd cpp\build

REM 设置 NDK 路径 (需根据实际环境修改)
set NDK_PATH=E:\android\SDK\ndk\30.0.15729638
set TOOLCHAIN=%NDK_PATH%\build\cmake\android.toolchain.cmake

if not exist "%TOOLCHAIN%" (
    echo [ERROR] CMake toolchain not found at %TOOLCHAIN%
    cd ..\..
    exit /b 1
)

echo [INFO] Configuring CMake for ARM64...
cmake -G "Ninja" ^
      -DCMAKE_TOOLCHAIN_FILE="%TOOLCHAIN%" ^
      -DANDROID_ABI="arm64-v8a" ^
      -DANDROID_PLATFORM=android-30 ^
      ..

if %ERRORLEVEL% neq 0 (
    echo [ERROR] CMake configuration failed!
    cd ..\..
    exit /b %ERRORLEVEL%
)

echo [INFO] Compiling with Ninja...
ninja
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Compilation failed!
    cd ..\..
    exit /b %ERRORLEVEL%
)
cd ..\..

echo [INFO] Assembling Magisk module structure...
mkdir system\lib64
copy cpp\build\libai_hook.so system\lib64\
echo [SUCCESS] Build complete! 
\`\`\`

### 1.2 【极其重要】手动纯净打包
**血泪教训：** 在 Windows 下，**千万不要使用 PowerShell 的 `Compress-Archive` 或其它批处理命令行进行 ZIP 打包**。这会导致 ZIP 内部的路径分隔符变为反斜杠 `\`。当模块刷入安卓 (Linux) 时，Magisk 会将 `system\lib64\libai_hook.so` 识别为一个带着反斜杠的超长文件名，从而导致 `system` 文件夹根本未创建，挂载彻底失败。

**正确的打包方式：**
1. 运行完 `build.bat` 生成 `system/lib64/libai_hook.so` 后。
2. 鼠标框选项目根目录的 4 个核心文件/文件夹：`module.prop`、`customize.sh`、`service.sh`、`system`。
3. 右键手动压缩为 `.zip` 格式（命名为 `ai_call_agent_module.zip`）。

---

## 2. 推送与刷入

1. 通过 ADB 推送至手机：
   \`\`\`bash
   adb push ai_call_agent_module.zip /sdcard/Download/
   \`\`\`
2. 手机打开 Magisk，点击“模块” -> “从本地安装”。
3. 刷入完成后，**必须重启手机**。

---

## 3. 标准化三级验证流程 (The Validation Protocol)

模块刷入并开机后，需要通过 ADB Root Shell 进行严格的三级验证。

### 3.1 验证第一层：Magisk 物理落地
确认 ZIP 里的文件是否被正确解压到了 Magisk 的模块存放区。
\`\`\`bash
adb shell
su
ls -l /data/adb/modules/ai_audio_hook/system/lib64/
\`\`\`
*   **期望结果**：看到 `libai_hook.so` 文件。
*   **异常排查**：如果提示 `No such file or directory`，或者看到类似 `system\lib64\libai_hook.so` 的畸形单文件，说明打包工具造成了路径错乱，请返回步骤 1.2 重新手动打包。

### 3.2 验证第二层：OverlayFS 挂载生效
确认 Magisk 的魔法是否生效，系统是否已经将动态库视为原生的系统文件。
\`\`\`bash
adb shell
su
ls -l /system/lib64/libai_hook.so
\`\`\`
*   **期望结果**：正常输出文件属性，例如：`-rw-r--r-- 1 root root 9168 2026-07-18 02:13 /system/lib64/libai_hook.so`
*   **异常排查**：如果在 3.1 中物理落地成功，但这一步却报 `No such file`，说明模块可能在 Magisk 界面中未被启用。前往 Magisk 模块界面开启开关，或卸载重装一次。

### 3.3 验证第三层：前置检查结论
完成前两步，标志着 **模块的物理结构与挂载链路完全正常**。
需要注意的是，此时使用 `logcat` 是看不到日志的，因为我们只是放置了文件，还没有在 `audioserver` 中配置触发加载该动态库的机制（即缺乏 Wrapper/LD_PRELOAD 引导层），这是下一阶段的开发目标。