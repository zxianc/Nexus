# Build sherpa-onnx-offline for Android arm64 on Windows (NDK + cmake/ninja)
# Doc: doc/05_sherpa_android_build.md
$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..\..")).Path
$Root = Join-Path $RepoRoot "tmp\nexus_stt"
$Src = Join-Path $Root "src\sherpa-onnx"
$Ndk = if ($env:ANDROID_NDK) { $env:ANDROID_NDK } else { "E:\android\SDK\ndk\30.0.15729638" }
$CmakeBin = if ($env:ANDROID_CMAKE_BIN) { $env:ANDROID_CMAKE_BIN } else { "E:\android\SDK\cmake\4.1.2\bin" }
$Cmake = Join-Path $CmakeBin "cmake.exe"
$Ninja = Join-Path $CmakeBin "ninja.exe"
$OrtVer = "1.27.0"
$Build = Join-Path $Src "build-android-arm64-v8a"

New-Item -ItemType Directory -Force -Path (Join-Path $Root "src") | Out-Null
if (-not (Test-Path (Join-Path $Src ".git"))) {
  Write-Host "Cloning sherpa-onnx v1.13.4 ..."
  git clone --depth 1 --branch v1.13.4 https://github.com/k2-fsa/sherpa-onnx.git $Src
}

$OrtZip = Join-Path $Build "$OrtVer\onnxruntime-android-$OrtVer.zip"
$OrtDir = Join-Path $Build $OrtVer
New-Item -ItemType Directory -Force -Path $OrtDir | Out-Null
if (-not (Test-Path (Join-Path $OrtDir "jni\arm64-v8a\libonnxruntime.so"))) {
  Write-Host "Downloading onnxruntime-android $OrtVer ..."
  $url = "https://github.com/csukuangfj/onnxruntime-libs/releases/download/v$OrtVer/onnxruntime-android-$OrtVer.zip"
  $tmp = Join-Path $OrtDir "ort.zip"
  Invoke-WebRequest -Uri $url -OutFile $tmp
  Expand-Archive -Path $tmp -DestinationPath $OrtDir -Force
  Remove-Item $tmp -Force
}

$env:SHERPA_ONNXRUNTIME_LIB_DIR = (Resolve-Path (Join-Path $OrtDir "jni\arm64-v8a")).Path
$env:SHERPA_ONNXRUNTIME_INCLUDE_DIR = (Resolve-Path (Join-Path $OrtDir "headers")).Path
Write-Host "ORT lib: $($env:SHERPA_ONNXRUNTIME_LIB_DIR)"
Write-Host "ORT inc: $($env:SHERPA_ONNXRUNTIME_INCLUDE_DIR)"

New-Item -ItemType Directory -Force -Path $Build | Out-Null
Set-Location $Build

& $Cmake -G Ninja `
  -DCMAKE_TOOLCHAIN_FILE="$Ndk\build\cmake\android.toolchain.cmake" `
  -DCMAKE_MAKE_PROGRAM="$Ninja" `
  -DANDROID_ABI=arm64-v8a `
  -DANDROID_PLATFORM=android-21 `
  -DCMAKE_BUILD_TYPE=Release `
  -DBUILD_SHARED_LIBS=ON `
  -DSHERPA_ONNX_ENABLE_BINARY=ON `
  -DSHERPA_ONNX_ENABLE_TTS=OFF `
  -DSHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF `
  -DSHERPA_ONNX_ENABLE_PYTHON=OFF `
  -DSHERPA_ONNX_ENABLE_TESTS=OFF `
  -DSHERPA_ONNX_ENABLE_CHECK=OFF `
  -DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF `
  -DSHERPA_ONNX_ENABLE_JNI=OFF `
  -DSHERPA_ONNX_ENABLE_C_API=OFF `
  -DSHERPA_ONNX_LINK_LIBSTDCPP_STATICALLY=OFF `
  -DBUILD_PIPER_PHONMIZE_EXE=OFF `
  -DBUILD_PIPER_PHONMIZE_TESTS=OFF `
  -DBUILD_ESPEAK_NG_EXE=OFF `
  -DBUILD_ESPEAK_NG_TESTS=OFF `
  -DCMAKE_INSTALL_PREFIX="$Build\install" `
  ..

if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

& $Ninja -j 8
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

& $Cmake --install . --strip
Copy-Item -Force (Join-Path $env:SHERPA_ONNXRUNTIME_LIB_DIR "libonnxruntime.so") (Join-Path $Build "install\lib\")

Write-Host "=== binaries ==="
Get-ChildItem (Join-Path $Build "install\bin") -ErrorAction SilentlyContinue | Format-Table Name, Length
Get-ChildItem (Join-Path $Build "install\lib") -ErrorAction SilentlyContinue | Format-Table Name, Length
