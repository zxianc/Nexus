@echo off
setlocal
cd /d "%~dp0"
set CGO_ENABLED=0
set GOOS=
set GOARCH=
go test ./...
if errorlevel 1 exit /b 1
set GOOS=linux
set GOARCH=arm64
go build -o nexus_callpolicy_arm64 .
if errorlevel 1 exit /b 1
echo [OK] nexus_callpolicy_arm64
