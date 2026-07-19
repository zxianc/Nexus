# Rebuild sherpa-onnx-offline for Android API 28 (current device binary)
# Doc: doc/05_sherpa_android_build.md
# Prereq: run build_sherpa_android.ps1 once (clone + ORT).
$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
$Src = Join-Path $RepoRoot "tmp\nexus_stt\src\sherpa-onnx"
$Ndk = if ($env:ANDROID_NDK) { $env:ANDROID_NDK } else { "E:\android\SDK\ndk\30.0.15729638" }
$CmakeBin = if ($env:ANDROID_CMAKE_BIN) { $env:ANDROID_CMAKE_BIN } else { "E:\android\SDK\cmake\4.1.2\bin" }
$Cmake = Join-Path $CmakeBin "cmake.exe"
$Ninja = Join-Path $CmakeBin "ninja.exe"
$Build = Join-Path $Src "build-android-arm64-v8a"
$OrtDir = Join-Path $Build "1.27.0"
$env:SHERPA_ONNXRUNTIME_LIB_DIR = (Resolve-Path (Join-Path $OrtDir "jni\arm64-v8a")).Path
$env:SHERPA_ONNXRUNTIME_INCLUDE_DIR = (Resolve-Path (Join-Path $OrtDir "headers")).Path
Set-Location $Build

& $Cmake -G Ninja `
  "-DCMAKE_TOOLCHAIN_FILE=$Ndk\build\cmake\android.toolchain.cmake" `
  "-DCMAKE_MAKE_PROGRAM=$Ninja" `
  "-DANDROID_ABI=arm64-v8a" `
  "-DANDROID_PLATFORM=android-28" `
  "-DCMAKE_BUILD_TYPE=Release" `
  "-DBUILD_SHARED_LIBS=ON" `
  "-DSHERPA_ONNX_ENABLE_BINARY=ON" `
  "-DSHERPA_ONNX_ENABLE_TTS=OFF" `
  "-DSHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF" `
  "-DSHERPA_ONNX_ENABLE_PYTHON=OFF" `
  "-DSHERPA_ONNX_ENABLE_TESTS=OFF" `
  "-DSHERPA_ONNX_ENABLE_CHECK=OFF" `
  "-DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF" `
  "-DSHERPA_ONNX_ENABLE_JNI=OFF" `
  "-DSHERPA_ONNX_ENABLE_C_API=OFF" `
  "-DSHERPA_ONNX_LINK_LIBSTDCPP_STATICALLY=OFF" `
  "-DBUILD_PIPER_PHONMIZE_EXE=OFF" `
  "-DBUILD_PIPER_PHONMIZE_TESTS=OFF" `
  "-DBUILD_ESPEAK_NG_EXE=OFF" `
  "-DBUILD_ESPEAK_NG_TESTS=OFF" `
  "-DCMAKE_INSTALL_PREFIX=$Build\install" `
  ..
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

& $Ninja -j 8 sherpa-onnx-offline
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

New-Item -ItemType Directory -Force -Path "$Build\install\bin", "$Build\install\lib" | Out-Null
Copy-Item -Force "$Build\bin\sherpa-onnx-offline" "$Build\install\bin\"
Copy-Item -Force "$($env:SHERPA_ONNXRUNTIME_LIB_DIR)\libonnxruntime.so" "$Build\install\lib\"
Get-ChildItem "$Build\install\bin", "$Build\install\lib" | Format-Table Name, Length
