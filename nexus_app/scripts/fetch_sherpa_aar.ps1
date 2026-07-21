# Download sherpa-onnx Android AAR 1.13.4 into app/libs/
$ErrorActionPreference = "Stop"
$ver = "1.13.4"
$outDir = Join-Path $PSScriptRoot "..\app\libs"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$out = Join-Path $outDir "sherpa-onnx-$ver.aar"
if (Test-Path $out) {
    Write-Host "Already exists: $out"
    exit 0
}
$urls = @(
    "https://ghfast.top/https://github.com/k2-fsa/sherpa-onnx/releases/download/v$ver/sherpa-onnx-$ver.aar",
    "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$ver/sherpa-onnx-$ver.aar"
)
foreach ($u in $urls) {
    try {
        Write-Host "Downloading $u"
        Invoke-WebRequest -Uri $u -OutFile $out -UseBasicParsing
        Write-Host "Saved $out"
        exit 0
    } catch {
        Write-Warning $_.Exception.Message
    }
}
throw "Failed to download sherpa-onnx AAR $ver"
