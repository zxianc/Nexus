@echo off
setlocal
cd /d "%~dp0"
set CGO_ENABLED=0
go test ./...
if errorlevel 1 exit /b 1
set GOOS=linux
set GOARCH=arm64
go build -o ai_call_arm64 .
if errorlevel 1 exit /b 1
echo [OK] ai_call_arm64
dir ai_call_arm64
