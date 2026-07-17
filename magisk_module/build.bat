@echo off
setlocal EnableExtensions

cd /d "%~dp0"

echo [INFO] Cleaning up old build files...
if exist cpp\build rmdir /s /q cpp\build
if exist system rmdir /s /q system

echo [INFO] Preparing build directories...
mkdir cpp\build
cd cpp\build

REM ---- toolchain paths (edit if your SDK layout differs) ----
set "ANDROID_SDK=E:\android\SDK"
set "NDK_PATH=%ANDROID_SDK%\ndk\30.0.15729638"
set "CMAKE_BIN=%ANDROID_SDK%\cmake\4.1.2\bin"
set "TOOLCHAIN=%NDK_PATH%\build\cmake\android.toolchain.cmake"

if not exist "%TOOLCHAIN%" (
    echo [ERROR] CMake toolchain not found at %TOOLCHAIN%
    cd /d "%~dp0"
    exit /b 1
)

if not exist "%CMAKE_BIN%\cmake.exe" (
    echo [ERROR] cmake.exe not found at %CMAKE_BIN%
    echo        Install "CMake" via Android SDK Manager, or update CMAKE_BIN in build.bat
    cd /d "%~dp0"
    exit /b 1
)

if not exist "%CMAKE_BIN%\ninja.exe" (
    echo [ERROR] ninja.exe not found at %CMAKE_BIN%
    cd /d "%~dp0"
    exit /b 1
)

set "PATH=%CMAKE_BIN%;%PATH%"

echo [INFO] Configuring CMake for ARM64...
cmake -G Ninja ^
      -DCMAKE_TOOLCHAIN_FILE="%TOOLCHAIN%" ^
      -DANDROID_ABI=arm64-v8a ^
      -DANDROID_PLATFORM=android-30 ^
      -DANDROID_STL=c++_static ^
      -DCMAKE_BUILD_TYPE=Release ^
      ..

if errorlevel 1 (
    echo [ERROR] CMake configuration failed!
    cd /d "%~dp0"
    exit /b 1
)

echo [INFO] Compiling with Ninja...
ninja

if errorlevel 1 (
    echo [ERROR] Compilation failed!
    cd /d "%~dp0"
    exit /b 1
)

cd /d "%~dp0"

echo [INFO] Assembling Magisk module structure...
mkdir system\lib64

if not exist "cpp\build\libai_hook.so" (
    echo [ERROR] libai_hook.so was not generated! Check your C++ code.
    exit /b 1
)

copy /Y cpp\build\libai_hook.so system\lib64\

echo [INFO] Zipping the Magisk module...
if exist ai_call_agent_module.zip del ai_call_agent_module.zip
powershell -NoProfile -Command "Compress-Archive -Path module.prop,customize.sh,service.sh,system -DestinationPath ai_call_agent_module.zip -Force"

echo [SUCCESS] Build complete! Output: ai_call_agent_module.zip
endlocal

:: 脚本有问题 
:: 你提供的第一行提示 system\\lib64\\libai_hook.so 是一个极其关键的线索！

:: 破案了！这是跨平台开发中一个非常隐蔽的坑：“反斜杠惨案”。

:: 你在 Windows 下使用 PowerShell 脚本（或者某些 Windows 压缩软件）打包 ZIP 时，压缩包内部记录的文件路径是用 Windows 的反斜杠 \ 隔开的。
:: 但是，当这个包送到 Android（基于 Linux）里面，Magisk 用内置的 unzip 去解压时，Linux 根本不认识反斜杠，它不认为这是一个目录层级，而是把它当成了一个带有反斜杠的“超长文件名”！

:: 所以，在手机的 /data/adb/modules/ai_audio_hook/ 目录下，根本没有 system 文件夹，而是凭空多出了一个名字叫 system\lib64\libai_hook.so 的奇葩文件！既然连 system 文件夹都没有，Magisk 自然也就不可能去挂载它了。