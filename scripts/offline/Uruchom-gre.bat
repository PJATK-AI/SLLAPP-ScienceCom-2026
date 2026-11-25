@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Obsluga-offline.ps1" start
set "sllapp_result=%errorlevel%"
if not "%sllapp_result%"=="0" pause
exit /b %sllapp_result%
