# RemoteMonitorApp

A secure, native Android surveillance and streaming application that turns an Android phone into a remotely controllable, kiosk-dimmed RTSP/RTMP audio-video streamer with redundant multi-channel control.

---

## Features

- **Android 11+ (SDK 30+) Background Survivability:**
  - **Always-On Dimmed Kiosk Mode:** Keeps the screen awake (`FLAG_KEEP_SCREEN_ON`) while automatically lowering brightness to `0.01f` with a pure black UI during `IDLE` or `STREAMING` states to save power and protect OLED/LCD displays. Tap anywhere to wake the UI controls.
  - **Foreground Service with Overlay Privileges:** `MonitorService` runs with `foregroundServiceType="camera|microphone|dataSync"` and `SYSTEM_ALERT_WINDOW` permission to reliably start camera and microphone capture even if the app loses foreground focus.
  - **Thread-Safe State Machine:** Governed by Kotlin `Mutex` (`IDLE`, `STARTING`, `STREAMING`, `STOPPING`). Restarting with new parameters dynamically and safely restarts the pipeline.

- **PedroSG94 RootEncoder Streaming Engine:**
  - **Built-in RTSP Server:** Runs locally on `rtsp://<phone_ip>:8554/live` protected by HTTP Digest / Basic authentication (`admin` / `auth_token`).
  - **Push Client Mode:** Directly streams out to any remote `rtsp://`, `rtsps://`, `rtmp://`, or `rtmps://` server.
  - **Strict Resource Management:**
    - `AUDIO_ONLY`: Initializes AAC audio capture only — camera hardware remains closed.
    - `VIDEO_ONLY`: Initializes H.264 camera hardware only (front or back) — microphone remains closed.
    - `BOTH`: Synchronized H.264 video and AAC audio.
  - **Coroutine Watchdog:** Automatic timer based on `duration_seconds` that terminates the stream when expired and safely releases all camera/mic hardware and `PARTIAL_WAKE_LOCK` / `WifiLock` locks.

- **Redundant 3-Channel Control Architecture:**
  - **Channel A — Local / VPN REST API (NanoHTTPD):** Listens on port `5000`. Supports `POST /api/control` and `GET /api/status` with Bearer token authentication.
  - **Channel B — Ntfy.sh SSE Push Stream:** Background Server-Sent Events (SSE) connection to `https://ntfy.sh/<secret-topic>/json` with exponential backoff auto-reconnect, enabling remote control behind CGNAT / mobile firewalls.
  - **Channel C — MQTT over TLS & ADB Broadcast:**
    - TLS-secured MQTT client automatically connecting to brokers (e.g. `ssl://broker:8883`) subscribing to `remotemonitor/control`.
    - Local `BroadcastReceiver` listening to `com.remotemonitor.ACTION_CONTROL`.

---

## Control Payload Specification

All control channels accept the following JSON payload:

```json
{
  "auth_token": "YOUR_SECRET_TOKEN",
  "action": "START",
  "mode": "BOTH",
  "camera": "BACK",
  "video_Resolution": "720p",
  "duration_seconds": 3600,
  "stream_target": "rtsp://my-server:8554/live"
}
```

### Parameter Reference

| Field | Type | Values | Description |
|---|---|---|---|
| `auth_token` | `string` | any | Plaintext token or its SHA-256 hash. Must match the configured token. |
| `action` | `string` | `"START"`, `"STOP"`, `"STATUS"` | Command action to execute. |
| `mode` | `string` | `"BOTH"`, `"AUDIO_ONLY"`, `"VIDEO_ONLY"` | Stream capture mode. |
| `camera` | `string` | `"BACK"`, `"FRONT"` | Selected camera sensor. |
| `video_Resolution` | `string` | `"720p"`, `"1080p"`, `"480p"` | Video resolution preset. |
| `duration_seconds` | `number` | integer (`0` = indefinite) | Auto-stop watchdog timeout in seconds. |
| `stream_target` | `string` | URL or `""` | Target URL for Push mode (`rtsp://`, `rtmp://`). If empty (`""`), launches built-in RTSP server on `:8554`. |

---

## API Endpoints (Port 5000)

### 1. Send Command: `POST /api/control`
- **Headers:** `Content-Type: application/json`
- **Body:** Control payload JSON.
- **Response:**
  ```json
  {
    "ok": true,
    "accepted": "START",
    "state": "STREAMING"
  }
  ```

### 2. Device Status: `GET /api/status`
- **Headers:** `Authorization: Bearer <auth_token>` (or `?auth_token=...`)
- **Response:**
  ```json
  {
    "ok": true,
    "state": "STREAMING",
    "mode": "BOTH",
    "camera": "BACK",
    "stream_url": "rtsp://192.168.0.185:8554/live",
    "battery_percent": 100,
    "charging": true,
    "remaining_seconds": 15,
    "failed_auth_count": 0,
    "overlay_allowed": true,
    "ips": {
      "wifi": "192.168.0.185",
      "vpn": "100.85.12.3",
      "best": "192.168.0.185",
      "all": "192.168.0.185,100.85.12.3"
    }
  }
  ```

### 3. Health Check: `GET /health`
- **Response:** `{"ok": true}`

---

## Quick Start & ADB Deployment

### 1. Build the APK
Prerequisites: JDK 17 (recommended: Eclipse Temurin 17), Android SDK Platform 34.

```bash
# Windows
.\gradlew.bat assembleDebug

# Linux / macOS
./gradlew assembleDebug
```

The compiled APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

### 2. Zero-Touch Installation & Permissions
Connect the phone via USB with USB Debugging enabled, then run:

```bash
# Install APK
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Grant required permissions & background exemptions
adb shell pm grant com.remotemonitor.app android.permission.CAMERA
adb shell pm grant com.remotemonitor.app android.permission.RECORD_AUDIO
adb shell appops set com.remotemonitor.app SYSTEM_ALERT_WINDOW allow
adb shell appops set com.remotemonitor.app RUN_IN_BACKGROUND allow
adb shell appops set com.remotemonitor.app RUN_ANY_IN_BACKGROUND allow
adb shell dumpsys deviceidle whitelist +com.remotemonitor.app

# Start the application
adb shell am start -n com.remotemonitor.app/.MainActivity
```

*(Note: On Windows, you can simply run `.\test_deploy.bat` or `.\test_deploy.ps1` to perform build, deployment, permission granting, and a 15-second self-test automatically).*

---

## Remote Control Examples

### Via cURL (Local Network / VPN):
```bash
# Start a 60-second camera + audio stream on the built-in RTSP server:
curl -X POST http://<PHONE_IP>:5000/api/control \
  -H "Content-Type: application/json" \
  -d '{"auth_token":"changeme-secret-token","action":"START","mode":"BOTH","duration_seconds":60}'

# Stop the stream immediately:
curl -X POST http://<PHONE_IP>:5000/api/control \
  -H "Content-Type: application/json" \
  -d '{"auth_token":"changeme-secret-token","action":"STOP"}'
```

### Via Ntfy.sh (No port forwarding / NAT traversal):
```bash
curl -d '{"auth_token":"changeme-secret-token","action":"START","mode":"BOTH","duration_seconds":300}' \
  https://ntfy.sh/<your-secret-topic>
```

### Via ADB Broadcast:
```bash
adb shell am broadcast -a com.remotemonitor.ACTION_CONTROL \
  --es payload '{"auth_token":"changeme-secret-token","action":"START","mode":"AUDIO_ONLY","duration_seconds":30}'
```

---

## Viewing the Live Stream

When running in built-in RTSP server mode, open the stream in VLC, mpv, or ffmpeg:
```
rtsp://admin:<auth_token>@<PHONE_IP>:8554/live
```

---

## License

This project is licensed under the [MIT License](LICENSE).
