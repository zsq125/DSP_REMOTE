@echo off
REM pc-gateway\start-gateway.cmd
REM Wrapper: calls start-gateway.ps1 with -ExecutionPolicy Bypass.
REM Reason: this machine has ExecutionPolicy=RemoteSigned, so running the .ps1
REM       directly is rejected ("not digitally signed"). This affects ONLY this
REM       process; the machine-wide execution policy is NOT modified.
REM Usage: pc-gateway\start-gateway.cmd [-Port 8765] [-DshPort 3099] [-Profile web]
REM                                       [-LanHost <ip>] [-TestHooks]
REM   -LanHost <ip>  force the LAN address written into the QR code and gateway.json
REM                   (useful when auto-detection picks a wrong adapter)

setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-gateway.ps1" %*
set "RC=%ERRORLEVEL%"
if not "%RC%"=="0" echo [start-gateway.cmd] start failed, exit code %RC%
exit /b %RC%
