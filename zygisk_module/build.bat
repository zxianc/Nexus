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
if exist cpp\build64 rmdir /s /q cpp\build64
if exist cpp\build32 rmdir /s /q cpp\build32
if exist out rmdir /s /q out
mkdir cpp\build64
mkdir cpp\build32
mkdir out\zygisk
mkdir out\system\lib64
mkdir out\system\vendor\lib
mkdir out\vendor\lib
mkdir out\bin

echo [INFO] Configure arm64-v8a...
cd cpp\build64
cmake -G Ninja ^
      -DCMAKE_TOOLCHAIN_FILE="%TOOLCHAIN%" ^
      -DANDROID_ABI=arm64-v8a ^
      -DANDROID_PLATFORM=android-30 ^
      -DANDROID_STL=c++_static ^
      -DCMAKE_BUILD_TYPE=Release ^
      ..
if errorlevel 1 exit /b 1
echo [INFO] Build arm64-v8a...
ninja
if errorlevel 1 exit /b 1
cd ..\..

echo [INFO] Configure armeabi-v7a...
cd cpp\build32
cmake -G Ninja ^
      -DCMAKE_TOOLCHAIN_FILE="%TOOLCHAIN%" ^
      -DANDROID_ABI=armeabi-v7a ^
      -DANDROID_PLATFORM=android-30 ^
      -DANDROID_STL=c++_static ^
      -DCMAKE_BUILD_TYPE=Release ^
      ..
if errorlevel 1 exit /b 1
echo [INFO] Build armeabi-v7a...
ninja
if errorlevel 1 exit /b 1
cd ..\..

echo [INFO] Stage module tree...
copy /Y cpp\build64\libai_hook.so out\system\lib64\
copy /Y cpp\build64\libzygisk_mod.so out\zygisk\arm64-v8a.so
copy /Y cpp\build64\inject out\bin\inject
copy /Y cpp\build32\libai_hook.so out\system\vendor\lib\
copy /Y cpp\build32\libai_hook.so out\vendor\lib\
copy /Y cpp\build32\inject out\bin\inject32
copy /Y module.prop out\
copy /Y service.sh out\
if exist customize.sh copy /Y customize.sh out\
if exist sepolicy.rule copy /Y sepolicy.rule out\
if exist post-fs-data.sh copy /Y post-fs-data.sh out\

echo [INFO] Pack zip with forward slashes (Python)...
python pack_zip.py
if errorlevel 1 exit /b 1

echo [SUCCESS] Output: nexus_audio_hook_zygisk.zip
echo          lib64 = audioserver (optional), lib = HAL 32-bit pcm hooks
echo          Enable Zygisk, install zip, reboot.
echo          Uninstall old module id ai_audio_hook if still present.
echo          Then: adb logcat -d -s AI_Audio_Hook:I AI_Inject:I
endlocal
exit /b 0
