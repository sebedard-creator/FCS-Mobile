@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0manage_service.ps1" -Action Setup
pause
