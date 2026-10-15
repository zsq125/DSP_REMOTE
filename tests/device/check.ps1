# tests/device/README.md — REAL DEVICE checklist (NEEDS USER, cannot be automated here)
#
# WHY THIS FILE EXISTS:
#   This PC has NO WHPX/Hyper-V (HypervisorPresent: False), so no Android emulator.
#   All "App actually runs on a device" cases must be executed BY THE USER on a real phone.
#   The test subagent does NOT sign off on these; paste raw command output / screenshots back.
#
# PREREQUISITES
#   - USB debugging enabled on phone, phone connected to PC, `adb devices` lists it as `device`
#   - APK built: F:\project\deepseekharness_remote_control\android-app\app\build\outputs\apk\debug\app-debug.apk
#   - PC gateway running: pc-gateway\start-gateway.ps1  (token in pc-gateway\.state\token.txt)
#
# HOW TO RUN
#   PowerShell:  powershell -NoProfile -ExecutionPolicy Bypass -File tests\device\check.ps1
#   It prints each step; run it on YOUR phone, paste the whole output back to the test subagent.
#
#   Set environment first:
#     $env:ANDROID_HOME='E:\system\android-sdk'
#     $env:JAVA_HOME='E:\system\java17'

$ErrorActionPreference = 'Continue'
$ADB = Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$APK = 'F:\project\deepseekharness_remote_control\android-app\app\build\outputs\apk\debug\app-debug.apk'
$PKG = 'com.deepseek.dshremote'

Write-Host "### STEP 0: device connected"
& $ADB devices

Write-Host "`n### STEP 1: install APK"
& $ADB install -r $APK
Write-Host "expected: Success"

Write-Host "`n### STEP 2: package installed (MAN-01..06)"
& $ADB shell pm list packages | Select-String $PKG
& $ADB shell dumpsys package $PKG | Select-String 'versionCode|minSdk|targetSdk'
& $ADB shell pm dump $PKG | Select-String -Context 2 'Service'

Write-Host "`n### STEP 3: token NOT stored in plaintext (SEC-01)"
Write-Host "--- list app private files ---"
& $ADB shell run-as $PKG ls -R files shared_prefs 2>&1
Write-Host "--- grep for token prefix (replace XXXXXXXX with real token[:8]) ---"
& $ADB shell "run-as $PKG sh -c 'grep -r . files shared_prefs 2>/dev/null'" | Select-String 'REPLACE_WITH_FP'
Write-Host "expected: NO full token found on disk"

Write-Host "`n### STEP 4: battery optimization whitelist (BAT-01)"
& $ADB shell dumpsys deviceidle whitelist | Select-String $PKG
Write-Host "expected: appears in list if user granted it"

Write-Host "`n### STEP 5: notification priority / ongoing (FGS-03, FGS-04)"
Write-Host "lock the phone screen FIRST so foreground service notification is visible"
& $ADB shell dumpsys notification --noredact | Select-String -Context 6 $PKG
Write-Host "expected: channel importance HIGH(3/4), flags include ongoing"

Write-Host "`n### STEP 6: Doze (NET/FGS survival) -- MANUAL"
Write-Host "1) in App: pair and open a task detail page"
Write-Host "2) enter Doze:  adb shell dumpsys deviceidle force-idle"
Write-Host "3) wait 5 min; from PC send a prompt to that session"
Write-Host "4) measure how long until phone shows the new event"
& $ADB shell dumpsys deviceidle force-idle
Write-Host "   ... wait and observe ... then unforce:"
& $ADB shell dumpsys deviceidle unforce

Write-Host "`n### STEP 7: screen-off keepalive -- MANUAL"
Write-Host "1) keep App in task detail, lock phone screen"
Write-Host "2) wait 10 minutes"
Write-Host "3) unlock; App should show new events WITHOUT manual reconnect"

Write-Host "`n### STEP 8: network switch (NET-02) -- MANUAL"
Write-Host "toggle Wi-Fi off (use cellular), wait ~10s, then back on"
Write-Host "expected: App auto-recovers to CONNECTED"

Write-Host "`n### STEP 9: scan QR pairing (PAIR, UI-06)"
Write-Host "on PC run: pc-gateway\start-gateway.ps1  (shows ASCII QR in terminal)"
Write-Host "in App: tap Scan, point camera at the terminal QR"
Write-Host "expected: parses and auto-enters task list; shown fp == token[:8]"

Write-Host "`n### STEP 10: vendor auto-start guide (BAT-03)"
Write-Host "for MIUI/EMUI/ColorOS: open the vendor settings page the App links to, screenshot"

Write-Host "`n### DONE. Paste ALL raw output above back to the test subagent."
