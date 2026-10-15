# ASCII-only body for stop-gateway.ps1 (see README note and start-gateway.ps1).
# Reason: Windows PowerShell 5.1 decodes BOM-less .ps1 files as the system ANSI
# code page (GBK on this machine), where multi-byte UTF-8 sequences can break the
# tokenizer. Pure ASCII removes that whole class of failures.

[CmdletBinding()]
param(
  [switch]$Force
)
$ErrorActionPreference = 'Stop'
$StateDir = Join-Path $PSScriptRoot '.state'
$StateFile = Join-Path $StateDir 'gateway.json'

function Fail($msg) { Write-Host "[gateway] ERROR: $msg" -ForegroundColor Red; exit 1 }

if (-not (Test-Path $StateFile)) { Fail "cannot find $StateFile (the gateway may never have started)" }
$st = Get-Content $StateFile -Raw -Encoding UTF8 | ConvertFrom-Json
$pid0 = [int]$st.pid
$port = [int]$st.port

$proc = Get-Process -Id $pid0 -ErrorAction SilentlyContinue
if (-not $proc) {
  Write-Host "[gateway] process $pid0 is already gone; cleaning the state file." -ForegroundColor Yellow
  Remove-Item $StateFile -Force -ErrorAction SilentlyContinue
} else {
  Write-Host "[gateway] stopping PID $pid0 ..."
  # The cmd.exe launcher owns a node.exe child, so stop the child first.
  $kids = Get-CimInstance Win32_Process -Filter "ParentProcessId=$pid0" -ErrorAction SilentlyContinue
  if ($Force) {
    Stop-Process -Id $pid0 -Force -ErrorAction SilentlyContinue
  } else {
    foreach ($k in $kids) { Stop-Process -Id ([int]$k.ProcessId) -Force -ErrorAction SilentlyContinue }
    Stop-Process -Id $pid0 -Force -ErrorAction SilentlyContinue
  }
}

# Wait for the port to be released.
$deadline = (Get-Date).AddSeconds(15)
$still = $true
while ((Get-Date) -lt $deadline) {
  $c = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
  if (-not $c) { $still = $false; break }
  Start-Sleep -Milliseconds 500
}
if ($still) {
  $owner = @((Get-NetTCPConnection -LocalPort $port -State Listen))[0].OwningProcess
  Write-Host "[gateway] port $port is still held by PID $owner; force killing it." -ForegroundColor Yellow
  Stop-Process -Id $owner -Force -ErrorAction SilentlyContinue
  Start-Sleep -Seconds 1
}

Remove-Item $StateFile -Force -ErrorAction SilentlyContinue
Write-Host "[gateway] STOPPED, port $port released." -ForegroundColor Green
exit 0
