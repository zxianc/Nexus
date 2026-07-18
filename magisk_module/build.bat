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
mkdir system\bin

if not exist "cpp\build\libai_hook.so" (
    echo [ERROR] libai_hook.so was not generated! Check your C++ code.
    exit /b 1
)
if not exist "cpp\build\audioserver" (
    echo [ERROR] audioserver binary was not generated! Check your C++ code.
    exit /b 1
)

copy /Y cpp\build\libai_hook.so system\lib64\
copy /Y cpp\build\audioserver system\bin\

echo [INFO] Zipping the Magisk module...
if exist ai_call_agent_module.zip del ai_call_agent_module.zip
powershell -NoProfile -Command "Compress-Archive -Path module.prop,customize.sh,service.sh,system -DestinationPath ai_call_agent_module.zip -Force"

echo [SUCCESS] Build complete! Output: ai_call_agent_module.zip
endlocal
exit /b 0
