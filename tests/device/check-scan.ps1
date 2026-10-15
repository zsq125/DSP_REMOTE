# tests/device/check-scan.ps1 - CONN-D6 scan/QR feature verification on a REAL device.
#
# Defect: on HONOR ADT-AN00 (Android 14), tapping "Open Scanner" after granting CAMERA
#         produced NO UI change; `dumpsys media.camera` Active Camera Clients stayed `[]`
#         and logcat had no CameraX/Camera2 lines. The app never opened the camera.
# Core criterion: CONN-D6-c - is the camera actually opened by our app? (machine-checkable)
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File tests\device\check-scan.ps1 `
#       -Serial <device-serial> -Package com.deepseek.dshremote
#
# NOTE: executable literals are ASCII on purpose (Windows PowerShell reads .ps1 as ANSI).
# NOTE: this script REVOKES then GRANTS the CAMERA permission and restarts the app.
#       It does NOT click the button for you unless you pass -OpenScanX/-OpenScanY,
#       because coordinates depend on screen resolution and hardcoding them risks false results.
param(
  [string]$Serial = '',
  [string]$Package = 'com.deepseek.dshremote',
  [int]$OpenScanX = -1,      # center X of the scan button (-1 = ask a human to tap)
  [int]$OpenScanY = -1,
  [switch]$SkipGrant
)

$ErrorActionPreference = 'Continue'
$adb = $null
if ($env:ANDROID_HOME) { $p = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'; if (Test-Path $p) { $adb = $p } }
if (-not $adb) {
  $fallback = 'E:\system\android-sdk\platform-tools\adb.exe'
  if (Test-Path $fallback) { $adb = $fallback }
}
if (-not $adb) { Write-Host 'adb not found'; exit 2 }

$s = @(); if ($Serial) { $s = @('-s', $Serial) }
function A { & $adb @s @args }

$script:Pass = 0; $script:Fail = 0; $script:Info = 0
function Ok([string]$id, [string]$name, [bool]$cond, [string]$d = '') {
  if ($cond) { $script:Pass++; Write-Host "[PASS] $id $name" }
  else { $script:Fail++; Write-Host "[FAIL] $id $name :: $d" }
}
function Note([string]$m) { $script:Info++; Write-Host "[INFO] $m" }

# ---------- preconditions ----------
$devList = (A devices) -join "`n"
Ok 'ENV-01' 'device online' ($devList -match '\sdevice\s*$' -or $devList -match 'device') $devList
$sdk = ((A shell getprop ro.build.version.sdk) -join '').Trim()
Ok 'ENV-02' 'Android SDK >= 30' ([int]$sdk -ge 30) "sdk=$sdk"
if ($script:Fail -gt 0) { exit 2 }

# ---------- prepare: start from "permission not yet granted" ----------
Note 'revoking CAMERA and restarting app (precondition for CONN-D6-a)'
A shell pm revoke $Package android.permission.CAMERA | Out-Null
A shell am force-stop $Package | Out-Null
A logcat -c | Out-Null
A shell am start -n "$Package/.ui.MainActivity" | Out-Null
Start-Sleep 4

$permLine = { ((A shell dumpsys package $Package | Select-String 'android.permission.CAMERA: granted=') -join '') }

# ---------- CONN-D6-a : first tap must go through the permission request path ----------
if ($OpenScanX -gt 0 -and $OpenScanY -gt 0) {
  Note 'TAP #1 on the scan button (expect the permission dialog)'
  A shell input tap $OpenScanX $OpenScanY | Out-Null
  Start-Sleep 3
} else {
  Note '>>> TAP the scan button ONCE manually (expect a permission dialog) <<<'
}
$g1 = & $permLine
Ok 'CONN-D6-a' 'first tap uses the permission-request path (CAMERA still not granted)' ($g1 -match 'granted=false') $g1

# ---------- grant ----------
if (-not $SkipGrant) {
  Note 'granting CAMERA'
  A shell pm grant $Package android.permission.CAMERA | Out-Null
  Start-Sleep 2
}
$g2 = & $permLine
Ok 'CONN-D6-pre' 'CAMERA is granted now' ($g2 -match 'granted=true') $g2

# ---------- KEY: the SECOND tap ----------
if ($OpenScanX -gt 0 -and $OpenScanY -gt 0) {
  Note 'TAP #2 on the scan button (easy to miss: must tap again after granting)'
  A shell input tap $OpenScanX $OpenScanY | Out-Null
} else {
  Note '>>> TAP the scan button ONE MORE TIME manually <<<'
}
Start-Sleep 6

# ---------- CONN-D6-b : no crash / no ANR ----------
$crash = (A logcat -d -b crash 2>&1 | Select-String $Package | Measure-Object).Count
Ok 'CONN-D6-b' 'no crash and no ANR' ($crash -eq 0) "crashLines=$crash"

# ---------- CONN-D6-c : CORE - camera actually opened ----------
$clients = (A shell "dumpsys media.camera | grep -A4 'Active Camera Clients'" 2>&1 | Out-String)
$hit = ($clients -match [regex]::Escape($Package))
Ok 'CONN-D6-c' 'camera actually opened (Active Camera Clients contains our app)' $hit ($clients.Trim() -replace "`r?`n", ' | ')

# ---------- CONN-D6-d : supporting evidence ----------
$log = (A logcat -d 2>&1 | Select-String 'CameraX|Camera2|camera2|CameraDevice|CameraCaptureSession|UseCaseAttach' | Measure-Object).Count
Ok 'CONN-D6-d' 'logcat shows CameraX/Camera2 binding' ($log -gt 0) "matches=$log"

Write-Host ''
Write-Host "== CONN-D6 device check: PASS=$script:Pass FAIL=$script:Fail (INFO=$script:Info)"
if ($script:Fail -eq 0) { Write-Host 'RESULT: D6 fixed.'; exit 0 }
else { Write-Host 'RESULT: D6 NOT fixed - see CONN-D6-c.'; exit 1 }
