@echo off
setlocal
cd /d "%~dp0"
echo [INFO] Packing nexus_models.zip (ensure models/sense-voice + vits-zh-ll are filled)...
python pack_zip.py
if errorlevel 1 exit /b 1
echo [SUCCESS] nexus_models.zip
echo          Large zip is expected when models are included.
endlocal
