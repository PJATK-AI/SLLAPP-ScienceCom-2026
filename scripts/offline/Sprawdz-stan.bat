@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Obsluga-offline.ps1" status
set "sllapp_result=%errorlevel%"
pause
exit /b %sllapp_result%
