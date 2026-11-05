Write-Host "=======================================================" -ForegroundColor Cyan
Write-Host "RemoteMonitorApp - Deploy, Permissions & Test (PowerShell)" -ForegroundColor Cyan
Write-Host "=======================================================" -ForegroundColor Cyan

Write-Host "[1/5] Waiting for device..." -ForegroundColor Yellow
adb wait-for-device
adb devices

Write-Host "[2/5] Installing APK..." -ForegroundColor Yellow
adb install -r app\build\outputs\apk\debug\app-debug.apk

Write-Host "[3/5] Granting Zero-Touch Permissions & Samsung Optimizations..." -ForegroundColor Yellow
adb shell pm grant com.remotemonitor.app android.permission.CAMERA
adb shell pm grant com.remotemonitor.app android.permission.RECORD_AUDIO
adb shell appops set com.remotemonitor.app SYSTEM_ALERT_WINDOW allow
adb shell appops set com.remotemonitor.app RUN_IN_BACKGROUND allow
adb shell appops set com.remotemonitor.app RUN_ANY_IN_BACKGROUND allow
adb shell dumpsys deviceidle whitelist +com.remotemonitor.app

Write-Host "[4/5] Launching MainActivity and Setting up Port Forwards..." -ForegroundColor Yellow
adb shell am start -n com.remotemonitor.app/.MainActivity
adb forward tcp:5000 tcp:5000
adb forward tcp:8554 tcp:8554

Start-Sleep -Seconds 3

Write-Host "[5/5] Testing REST API Control (15s BOTH stream test)..." -ForegroundColor Yellow
$postRes = curl.exe -s -X POST http://127.0.0.1:5000/api/control -H "Content-Type: application/json" -d '{"auth_token":"changeme-secret-token","action":"START","mode":"BOTH","duration_seconds":15}'
Write-Host "API POST Response: $postRes" -ForegroundColor Green

$statusRes = curl.exe -s -H "Authorization: Bearer changeme-secret-token" http://127.0.0.1:5000/api/status
Write-Host "API Status Response: $statusRes" -ForegroundColor Green
Write-Host "=======================================================" -ForegroundColor Cyan
