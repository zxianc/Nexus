@echo off
setlocal
cd /d "%~dp0"
echo [INFO] Packing nexus_runtime.zip (ensure bin/ + lib/ are filled)...
python pack_zip.py
if errorlevel 1 exit /b 1
echo [SUCCESS] nexus_runtime.zip
echo          Install with Magisk after nexus_models (models) and nexus_audio_hook (HAL).
endlocal
