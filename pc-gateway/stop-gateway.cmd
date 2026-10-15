@echo off
REM pc-gateway\stop-gateway.cmd
REM Wrapper: calls stop-gateway.ps1 with -ExecutionPolicy Bypass (policy not modified).
REM Usage: pc-gateway\stop-gateway.cmd [-Force]

setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0stop-gateway.ps1" %*
set "RC=%ERRORLEVEL%"
if not "%RC%"=="0" echo [stop-gateway.cmd] stop failed, exit code %RC%
exit /b %RC%
