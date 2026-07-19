# Build nexus_engine for Android API 28 against existing sherpa-onnx static libs.
# Prereq: sherpa TTS build (build_sherpa_tts_api28.ps1) completed.
$ErrorActionPreference = "Stop"
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$SrcRoot = Join-Path $RepoRoot "tmp\nexus_stt\src\sherpa-onnx"
$Build = Join-Path $SrcRoot "build-android-arm64-v8a"
$Ndk = if ($env:ANDROID_NDK) { $env:ANDROID_NDK } else { "E:\android\SDK\ndk\30.0.15729638" }
$Clang = Join-Path $Ndk "toolchains\llvm\prebuilt\windows-x86_64\bin\clang++.exe"
$Sysroot = Join-Path $Ndk "toolchains\llvm\prebuilt\windows-x86_64\sysroot"
$OrtLib = Join-Path $Build "1.27.0\jni\arm64-v8a"
$OutDir = Join-Path $Build "install\bin"
$Obj = Join-Path $Build "nexus_engine.o"
$Out = Join-Path $OutDir "nexus_engine"
$Main = Join-Path $PSScriptRoot "main.cc"
$Lib = Join-Path $Build "lib"

if (-not (Test-Path $Clang)) { throw "clang++ not found: $Clang" }
if (-not (Test-Path (Join-Path $Lib "libsherpa-onnx-core.a"))) {
  throw "Missing sherpa libs under $Lib — run build_sherpa_tts_api28.ps1 first"
}

New-Item -ItemType Directory -Force -Path $OutDir, (Join-Path $Build "install\lib") | Out-Null

# Match sherpa CLI flags; -z,max-page-size=16384 fixes Bionic TLS underalign.
$compile = @(
  "--target=aarch64-none-linux-android28",
  "--sysroot=$Sysroot",
  "-O3", "-DNDEBUG", "-fPIC", "-std=c++17", "-flto=thin",
  "-DANDROID", "-ffunction-sections", "-fdata-sections",
  "-I$SrcRoot",
  "-I$Build",
  "-c", $Main, "-o", $Obj
)
& $Clang @compile
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$link = @(
  "--target=aarch64-none-linux-android28",
  "--sysroot=$Sysroot",
  "-O3", "-DNDEBUG", "-flto=thin", "-static-libstdc++",
  "-Wl,--gc-sections", "-Wl,--build-id=sha1", "-Wl,--no-rosegment",
  "-Wl,--no-undefined", "-Wl,-z,max-page-size=16384",
  $Obj,
  "-L$Lib", "-L$OrtLib",
  '-Wl,-rpath,$ORIGIN',
  "-lsherpa-onnx-core",
  "-lkaldi-native-fbank-core",
  "-lkissfft-float",
  "-lkaldi-decoder-core",
  "-lsherpa-onnx-kaldifst-core",
  "-lssentencepiece_core",
  "-lonnxruntime",
  "-lsherpa-onnx-fstfar",
  "-lsherpa-onnx-fst",
  "-lpiper_phonemize",
  "-lespeak-ng",
  "-lucd",
  "-landroid", "-llog", "-ldl", "-lm", "-latomic",
  "-o", $Out
)
& $Clang @link
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Copy-Item -Force (Join-Path $OrtLib "libonnxruntime.so") (Join-Path $Build "install\lib\libonnxruntime.so")
Get-Item $Out | Format-Table Name, Length, FullName
Write-Host "[OK] $Out"
