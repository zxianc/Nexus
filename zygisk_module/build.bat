@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "ANDROID_SDK=E:\android\SDK"
set "NDK_PATH=%ANDROID_SDK%\ndk\30.0.15729638"
set "CMAKE_BIN=%ANDROID_SDK%\cmake\4.1.2\bin"
set "TOOLCHAIN=%NDK_PATH%\build\cmake\android.toolchain.cmake"

if not exist "%TOOLCHAIN%" (
    echo [ERROR] NDK toolchain missing: %TOOLCHAIN%
    exit /b 1
)
if not exist "%CMAKE_BIN%\cmake.exe" (
    echo [ERROR] cmake missing under %CMAKE_BIN%
    exit /b 1
)

set "PATH=%CMAKE_BIN%;%PATH%"

echo [INFO] Clean...
if exist cpp\build rmdir /s /q cpp\build
if exist out rmdir /s /q out
mkdir cpp\build
mkdir out\zygisk
mkdir out\system\lib64
mkdir out\bin

cd cpp\build
echo [INFO] Configure...
cmake -G Ninja ^
      -DCMAKE_TOOLCHAIN_FILE="%TOOLCHAIN%" ^
      -DANDROID_ABI=arm64-v8a ^
      -DANDROID_PLATFORM=android-30 ^
      -DANDROID_STL=c++_static ^
      -DCMAKE_BUILD_TYPE=Release ^
      ..
if errorlevel 1 exit /b 1

echo [INFO] Build...
ninja
if errorlevel 1 exit /b 1
cd ..\..

echo [INFO] Stage module tree...
copy /Y cpp\build\libai_hook.so out\system\lib64\
copy /Y cpp\build\libzygisk_mod.so out\zygisk\arm64-v8a.so
copy /Y cpp\build\inject out\bin\
copy /Y module.prop out\
copy /Y service.sh out\
if exist customize.sh copy /Y customize.sh out\
if exist sepolicy.rule copy /Y sepolicy.rule out\
if exist post-fs-data.sh copy /Y post-fs-data.sh out\

echo [INFO] Pack zip with forward slashes (Python)...
python pack_zip.py
if errorlevel 1 exit /b 1

echo [SUCCESS] Output: ai_audio_hook_zygisk.zip
echo          Enable Zygisk in Magisk, install zip, reboot.
echo          Then: adb logcat -s AI_Zygisk:I AI_Inject:I AI_Audio_Hook:I
endlocal
exit /b 0
