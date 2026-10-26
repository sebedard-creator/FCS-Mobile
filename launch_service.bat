@echo off
echo FCS Mobile
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0manage_service.ps1" -Action Start
pause
