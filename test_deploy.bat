@echo off
echo =======================================================
echo RemoteMonitorApp - Deploy, Permissions & Test Script
echo =======================================================

echo [1/5] Checking connected ADB device...
adb wait-for-device
adb devices

echo [2/5] Installing APK...
adb install -r app\build\outputs\apk\debug\app-debug.apk
if %ERRORLEVEL% neq 0 (
    echo Installation failed!
    exit /b %ERRORLEVEL%
)

echo [3/5] Granting Zero-Touch Permissions & Samsung Optimizations...
adb shell pm grant com.remotemonitor.app android.permission.CAMERA
adb shell pm grant com.remotemonitor.app android.permission.RECORD_AUDIO
adb shell appops set com.remotemonitor.app SYSTEM_ALERT_WINDOW allow
adb shell appops set com.remotemonitor.app RUN_IN_BACKGROUND allow
adb shell appops set com.remotemonitor.app RUN_ANY_IN_BACKGROUND allow
adb shell dumpsys deviceidle whitelist +com.remotemonitor.app

echo [4/5] Launching MainActivity and Setting up Port Forwards...
adb shell am start -n com.remotemonitor.app/.MainActivity
adb forward tcp:5000 tcp:5000
adb forward tcp:8554 tcp:8554

echo Waiting 3 seconds for service initialization...
timeout /t 3 /nobreak >nul

echo [5/5] Testing REST API Control (15s BOTH stream test)...
curl -s -X POST http://127.0.0.1:5000/api/control -H "Content-Type: application/json" -d "{\"auth_token\":\"changeme-secret-token\",\"action\":\"START\",\"mode\":\"BOTH\",\"duration_seconds\":15}"
echo.
echo Status query:
curl -s -H "Authorization: Bearer changeme-secret-token" http://127.0.0.1:5000/api/status
echo.
echo =======================================================
echo Deployment and Test command completed!
echo =======================================================
