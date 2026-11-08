package com.remotemonitor.app

object DashboardHtml {
    const val CONTENT = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>RemoteMonitor Console</title>
<style>
  :root {
    --bg: #090a0f;
    --surface: #11141d;
    --surface-elevated: #161a26;
    --border: #1f2536;
    --border-subtle: #171c2b;
    --border-focus: #3b82f6;
    --text-primary: #f1f5f9;
    --text-secondary: #94a3b8;
    --text-muted: #64748b;
    --live: #10b981;
    --live-bg: rgba(16, 185, 129, 0.08);
    --live-border: rgba(16, 185, 129, 0.25);
    --danger: #ef4444;
    --danger-bg: rgba(239, 68, 68, 0.08);
    --danger-border: rgba(239, 68, 68, 0.25);
  }

  * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif; }
  body {
    background: var(--bg);
    color: var(--text-primary);
    min-height: 100vh;
    padding: 24px 16px;
    -webkit-font-smoothing: antialiased;
  }
  .app-shell {
    max-width: 900px;
    margin: 0 auto;
    display: flex;
    flex-direction: column;
    gap: 16px;
  }

  header {
    display: flex;
    justify-content: space-between;
    align-items: center;
    padding-bottom: 16px;
    border-bottom: 1px solid var(--border);
  }
  .brand-title {
    font-size: 1.1rem;
    font-weight: 600;
    letter-spacing: -0.01em;
    color: var(--text-primary);
  }
  .brand-sub {
    font-size: 0.74rem;
    color: var(--text-muted);
    font-weight: 400;
    margin-top: 2px;
  }
  .status-badge {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    padding: 4px 12px;
    border-radius: 6px;
    font-size: 0.74rem;
    font-weight: 600;
    letter-spacing: 0.05em;
    text-transform: uppercase;
    font-variant-numeric: tabular-nums;
    background: var(--surface);
    border: 1px solid var(--border);
    color: var(--text-muted);
  }
  .status-badge.streaming {
    background: var(--live-bg);
    border-color: var(--live-border);
    color: var(--live);
  }
  .status-badge.starting {
    background: rgba(245, 158, 11, 0.08);
    border-color: rgba(245, 158, 11, 0.25);
    color: #fbbf24;
  }
  .status-dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: currentColor;
  }
  .status-badge.streaming .status-dot {
    animation: pulse 2s cubic-bezier(0.4, 0, 0.6, 1) infinite;
  }
  @keyframes pulse {
    0%, 100% { opacity: 1; }
    50% { opacity: 0.35; }
  }

  .card {
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 18px 20px;
    display: flex;
    flex-direction: column;
    gap: 14px;
  }
  .card-header {
    display: flex;
    justify-content: space-between;
    align-items: center;
  }
  .card-label {
    font-size: 0.74rem;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--text-secondary);
  }

  .telemetry-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(130px, 1fr));
    gap: 10px;
  }
  .metric-cell {
    background: var(--surface-elevated);
    border: 1px solid var(--border-subtle);
    border-radius: 6px;
    padding: 10px 12px;
    display: flex;
    flex-direction: column;
    gap: 3px;
  }
  .metric-label {
    font-size: 0.68rem;
    font-weight: 500;
    color: var(--text-muted);
    text-transform: uppercase;
    letter-spacing: 0.04em;
  }
  .metric-value {
    font-size: 1.05rem;
    font-weight: 600;
    color: var(--text-primary);
    font-variant-numeric: tabular-nums;
  }

  .stream-banner {
    background: var(--surface-elevated);
    border: 1px solid var(--border);
    border-left: 3px solid var(--live);
    border-radius: 6px;
    padding: 12px 14px;
    display: flex;
    justify-content: space-between;
    align-items: center;
    flex-wrap: wrap;
    gap: 10px;
  }
  .stream-info {
    display: flex;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
  }
  .stream-caption {
    font-size: 0.68rem;
    color: var(--text-muted);
    text-transform: uppercase;
    letter-spacing: 0.05em;
    font-weight: 500;
  }
  .stream-url-text {
    font-family: ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace;
    font-size: 0.85rem;
    color: var(--text-primary);
    text-decoration: none;
    word-break: break-all;
  }
  .stream-url-text:hover { color: #93c5fd; }

  .form-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
    gap: 12px;
  }
  .form-item {
    display: flex;
    flex-direction: column;
    gap: 5px;
  }
  .form-label {
    font-size: 0.74rem;
    font-weight: 500;
    color: var(--text-secondary);
  }
  input[type="text"], input[type="password"], input[type="number"], select {
    background: var(--surface-elevated);
    border: 1px solid var(--border);
    color: var(--text-primary);
    font-size: 0.84rem;
    padding: 8px 12px;
    border-radius: 6px;
    outline: none;
    transition: border-color 0.15s ease;
  }
  input:focus, select:focus {
    border-color: var(--border-focus);
  }

  .action-deck {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    padding-top: 4px;
  }
  button {
    font-size: 0.82rem;
    font-weight: 500;
    padding: 8px 14px;
    border-radius: 6px;
    border: 1px solid var(--border);
    background: var(--surface-elevated);
    color: var(--text-primary);
    cursor: pointer;
    display: inline-flex;
    align-items: center;
    gap: 6px;
    transition: background 0.12s ease, border-color 0.12s ease;
    user-select: none;
  }
  button:hover {
    background: #1e2436;
    border-color: #2b334a;
  }
  button:active {
    transform: translateY(1px);
  }
  button svg {
    width: 14px;
    height: 14px;
    stroke: currentColor;
    fill: none;
    flex-shrink: 0;
  }
  .btn-start {
    background: #f8fafc;
    color: #090a0f;
    border-color: #f8fafc;
    font-weight: 600;
  }
  .btn-start:hover {
    background: #e2e8f0;
    border-color: #e2e8f0;
  }
  .btn-stop {
    background: var(--danger-bg);
    border-color: var(--danger-border);
    color: #fca5a5;
  }
  .btn-stop:hover {
    background: rgba(239, 68, 68, 0.16);
    border-color: rgba(239, 68, 68, 0.35);
  }
  .btn-active {
    border-color: #3b82f6;
    color: #93c5fd;
    background: rgba(59, 130, 246, 0.12);
  }
  .btn-secondary {
    font-size: 0.74rem;
    padding: 4px 10px;
    color: var(--text-muted);
  }
  .btn-secondary:hover {
    color: var(--text-primary);
  }

  .console-box {
    background: #05070a;
    border: 1px solid var(--border);
    border-radius: 6px;
    padding: 10px 12px;
    font-family: ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace;
    font-size: 0.74rem;
    color: var(--text-secondary);
    max-height: 130px;
    overflow-y: auto;
    white-space: pre-wrap;
    line-height: 1.45;
  }
</style>
</head>
<body>
<div class="app-shell">
  <header>
    <div>
      <div class="brand-title">Remote Monitor Console</div>
      <div class="brand-sub">Samsung SM-A405FN · Android 11 Kiosk</div>
    </div>
    <div id="statusBadge" class="status-badge">
      <span class="status-dot"></span>
      <span id="badgeText">IDLE</span>
    </div>
  </header>

  <!-- Authentication -->
  <div class="card">
    <div class="card-header">
      <span class="card-label">Authentication</span>
      <span style="font-size:0.7rem; color:var(--text-muted);">Stored locally in browser</span>
    </div>
    <div class="form-item">
      <label class="form-label" for="tokenInput">Bearer Auth Token</label>
      <input type="password" id="tokenInput" placeholder="Enter auth token..." autocomplete="off">
    </div>
  </div>

  <!-- Telemetry -->
  <div class="card">
    <div class="card-header">
      <span class="card-label">Device Telemetry</span>
      <button class="btn-secondary" onclick="fetchStatus()">
        <svg viewBox="0 0 24 24" stroke-width="2"><path d="M21.5 2v6h-6M21.34 15.57a10 10 0 1 1-.57-8.38l5.67-5.67"/></svg>
        Refresh
      </button>
    </div>
    <div class="telemetry-grid">
      <div class="metric-cell">
        <span class="metric-label">Status</span>
        <span class="metric-value" id="statState">--</span>
      </div>
      <div class="metric-cell">
        <span class="metric-label">Battery</span>
        <span class="metric-value" id="statBattery">--</span>
      </div>
      <div class="metric-cell">
        <span class="metric-label">Remaining</span>
        <span class="metric-value" id="statRemaining">--</span>
      </div>
      <div class="metric-cell">
        <span class="metric-label">Active Sensor</span>
        <span class="metric-value" id="statCamera">--</span>
      </div>
      <div class="metric-cell">
        <span class="metric-label">Torch</span>
        <span class="metric-value" id="statTorch">OFF</span>
      </div>
      <div class="metric-cell">
        <span class="metric-label">Primary IP</span>
        <span class="metric-value" id="statIp" style="font-size:0.88rem;">--</span>
      </div>
      <div class="metric-cell">
        <span class="metric-label">Temperature</span>
        <span class="metric-value" id="statTemp">--</span>
      </div>
      <div class="metric-cell">
        <span class="metric-label">Recording</span>
        <span class="metric-value" id="statRecording">OFF</span>
      </div>
    </div>

    <div id="streamBanner" class="stream-banner" style="display:none;">
      <div class="stream-info">
        <span class="stream-caption">RTSP Live Stream</span>
        <a id="streamLink" class="stream-url-text" href="#" target="_blank">--</a>
      </div>
      <button id="copyBtn" class="btn-secondary" onclick="copyStreamUrl()">
        <svg viewBox="0 0 24 24" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
        <span id="copyBtnText">Copy URL</span>
      </button>
    </div>
  </div>

  <!-- Live Monitor -->
  <div class="card" id="liveMonitorCard" style="display:none;">
    <div class="card-header">
      <span class="card-label">Live Monitor</span>
      <span id="snapshotMeta" style="font-size:0.7rem; color:var(--text-muted);">--</span>
    </div>
    <div style="background:#000; border-radius:6px; overflow:hidden; border:1px solid var(--border); display:flex; align-items:center; justify-content:center;">
      <img id="liveSnapshot" style="width:100%; display:block; max-height:480px; object-fit:contain; background:#000;" alt="live snapshot">
    </div>
    <audio id="liveAudio" controls style="width:100%;"></audio>
    <div class="action-deck">
      <button class="btn-secondary" id="btnMuteAudio" onclick="toggleMute()">Mute Audio</button>
      <button class="btn-secondary" onclick="saveSnapshot()">Save Snapshot</button>
      <button class="btn-secondary" onclick="goFullscreen()">Fullscreen</button>
      <select id="selRefreshRate" onchange="restartSnapshotLoop()" style="flex: 0 0 110px;">
        <option value="200">5 fps</option>
        <option value="333">3 fps</option>
        <option value="500">2 fps</option>
        <option value="1000" selected>1 fps</option>
        <option value="2000">0.5 fps</option>
      </select>
    </div>
  </div>

  <!-- Stream Controls -->
  <div class="card">
    <div class="card-header">
      <span class="card-label">Configuration & Control</span>
    </div>
    <div class="form-grid">
      <div class="form-item">
        <label class="form-label" for="selMode">Capture Mode</label>
        <select id="selMode">
          <option value="BOTH" selected>Video + Audio</option>
          <option value="VIDEO_ONLY">Video Only</option>
          <option value="AUDIO_ONLY">Audio Only</option>
        </select>
      </div>
      <div class="form-item">
        <label class="form-label" for="selCamera">Sensor</label>
        <select id="selCamera">
          <option value="BACK" selected>Rear Camera</option>
          <option value="FRONT">Front Camera</option>
        </select>
      </div>
      <div class="form-item">
        <label class="form-label" for="selRes">Resolution</label>
        <select id="selRes">
          <option value="720p" selected>720p HD</option>
          <option value="1080p">1080p FHD</option>
          <option value="480p">480p SD</option>
        </select>
      </div>
      <div class="form-item">
        <label class="form-label" for="numBitrate">Bitrate (Kbps)</label>
        <input type="number" id="numBitrate" value="1500" min="200" max="8000" step="100">
      </div>
      <div class="form-item">
        <label class="form-label" for="numFps">Frame Rate (FPS)</label>
        <input type="number" id="numFps" value="30" min="10" max="60" step="5">
      </div>
      <div class="form-item">
        <label class="form-label" for="numDuration">Duration (Seconds, 0 = Continuous)</label>
        <input type="number" id="numDuration" value="0" min="0" step="10">
      </div>
      <div class="form-item" style="grid-column: 1 / -1;">
        <label class="form-label" for="txtTarget">Push Target URL (Leave empty for Built-in RTSP Server on :8554)</label>
        <input type="text" id="txtTarget" placeholder="rtsp://... or rtmp://... (optional)">
      </div>
      <div class="form-item">
        <label class="form-label" for="numVox">VOX Threshold (dB, 0 = off)</label>
        <input type="number" id="numVox" value="0" min="-60" max="0" step="1">
      </div>
      <div class="form-item">
        <label class="form-label" for="numMotion">Motion Sensitivity (0 = off, 1-100)</label>
        <input type="number" id="numMotion" value="0" min="0" max="100" step="1">
      </div>
      <div class="form-item" style="flex-direction:row; align-items:center; gap:8px;">
        <input type="checkbox" id="chkRecordLocally" style="width:auto;">
        <label class="form-label" for="chkRecordLocally" style="margin:0;">Record locally on Start</label>
      </div>
    </div>

    <div class="action-deck">
      <button class="btn-start" onclick="sendAction('START')">
        <svg viewBox="0 0 24 24" stroke-width="2"><polygon points="5 3 19 12 5 21 5 3"/></svg>
        Start Stream
      </button>
      <button class="btn-stop" onclick="sendAction('STOP')">
        <svg viewBox="0 0 24 24" stroke-width="2"><rect x="4" y="4" width="16" height="16" rx="2"/></svg>
        Stop Stream
      </button>
      <button onclick="sendAction('SWITCH_CAMERA')">
        <svg viewBox="0 0 24 24" stroke-width="2"><path d="M20 16v5h-5M4 8V3h5M16 4l5 5M8 20l-5-5M20 9a9 9 0 0 0-14.7-4.7M4 15a9 9 0 0 0 14.7 4.7"/></svg>
        Switch Sensor
      </button>
      <button id="torchBtn" onclick="toggleTorch()">
        <svg viewBox="0 0 24 24" stroke-width="2"><path d="M18 6h-2a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2H6a2 2 0 0 0-2 2v2l3 3v7a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2v-7l3-3V8a2 2 0 0 0-2-2z"/></svg>
        <span id="torchBtnText">Torch ON</span>
      </button>
      <button onclick="sendAction('AUTOFOCUS')">Autofocus</button>
      <button id="recordBtn" onclick="toggleRecording()">Record Start</button>
    </div>
    <div class="form-item">
      <label class="form-label" for="rngZoom">Zoom: <span id="zoomVal">1.0x</span></label>
      <input type="range" id="rngZoom" min="1" max="8" step="0.1" value="1" oninput="onZoomInput(this.value)">
    </div>
  </div>

  <!-- Push-to-Talk -->
  <div class="card">
    <div class="card-header">
      <span class="card-label">Push-to-Talk</span>
      <span id="talkStatus" style="font-size:0.7rem; color:var(--text-muted);">Idle</span>
    </div>
    <div class="action-deck">
      <button id="talkBtn" class="btn-start" onmousedown="startTalk()" onmouseup="stopTalk()" onmouseleave="stopTalk()" ontouchstart="startTalk()" ontouchend="stopTalk()">
        Hold to Talk
      </button>
    </div>
  </div>

  <!-- Recordings -->
  <div class="card">
    <div class="card-header">
      <span class="card-label">Recordings</span>
      <button class="btn-secondary" onclick="loadRecordings()">Refresh</button>
    </div>
    <div id="recordingsList" class="console-box" style="max-height:200px;">No recordings loaded.</div>
  </div>

  <!-- Activity Console -->
  <div class="card">
    <div class="card-header">
      <span class="card-label">Activity Log</span>
    </div>
    <div id="consoleLog" class="console-box">[00:00:00] Console initialized. Polling device status every 2s.</div>
  </div>
</div>

<script>
  let lastTorchActive = false;
  const tokenInput = document.getElementById('tokenInput');
  const savedToken = localStorage.getItem('rm_auth_token') || 'changeme-secret-token';
  tokenInput.value = savedToken;
  tokenInput.addEventListener('input', () => {
    localStorage.setItem('rm_auth_token', tokenInput.value);
  });

  function logMsg(msg, type = 'info') {
    const el = document.getElementById('consoleLog');
    const ts = new Date().toTimeString().split(' ')[0];
    const prefix = type === 'err' ? '[ERR]' : (type === 'ok' ? '[OK]' : '[INFO]');
    const line = `[${'$'}{ts}] ${'$'}{prefix} ${'$'}{msg}\n`;
    el.innerText = line + el.innerText.split('\n').slice(0, 16).join('\n');
  }

  async function fetchStatus() {
    const token = tokenInput.value.trim();
    if (!token) return;
    try {
      const res = await fetch('/api/status', {
        headers: { 'Authorization': 'Bearer ' + token }
      });
      if (res.status === 401) {
        document.getElementById('badgeText').innerText = 'UNAUTHORIZED';
        document.getElementById('statusBadge').className = 'status-badge';
        logMsg('Authentication rejected: invalid token', 'err');
        return;
      }
      if (!res.ok) {
        logMsg('Telemetry query returned HTTP ' + res.status, 'err');
        return;
      }
      const data = await res.json();
      updateUi(data);
    } catch (e) {
      document.getElementById('badgeText').innerText = 'UNREACHABLE';
      document.getElementById('statusBadge').className = 'status-badge';
    }
  }

  function updateUi(d) {
    const state = d.state || 'IDLE';
    const badge = document.getElementById('statusBadge');
    const badgeText = document.getElementById('badgeText');
    badgeText.innerText = state;
    badge.className = 'status-badge ' + (state === 'STREAMING' ? 'streaming' : (state === 'STARTING' ? 'starting' : ''));

    document.getElementById('statState').innerText = state;
    const battText = (d.battery_percent ?? '--') + '%' + (d.charging ? ' (AC)' : '');
    document.getElementById('statBattery').innerText = battText;

    const remSec = d.remaining_seconds ?? 0;
    document.getElementById('statRemaining').innerText = remSec > 0 ? (remSec + 's') : (state === 'STREAMING' ? 'Continuous' : '--');

    const cam = d.camera || d.active_camera || '--';
    document.getElementById('statCamera').innerText = cam === 'FRONT' ? 'Front' : (cam === 'BACK' ? 'Rear' : cam);

    lastTorchActive = !!d.torch_active;
    document.getElementById('statTorch').innerText = lastTorchActive ? 'ON' : 'OFF';

    const torchBtn = document.getElementById('torchBtn');
    const torchBtnText = document.getElementById('torchBtnText');
    if (lastTorchActive) {
      torchBtn.classList.add('btn-active');
      torchBtnText.innerText = 'Torch OFF';
    } else {
      torchBtn.classList.remove('btn-active');
      torchBtnText.innerText = 'Torch ON';
    }

    const ip = d.ips?.wifi || d.ips?.best || '--';
    document.getElementById('statIp').innerText = ip;

    const temp = d.temperature_c;
    document.getElementById('statTemp').innerText = (temp !== undefined && temp !== null) ? temp.toFixed(1) + ' C' : '--';

    const recording = !!d.is_recording;
    document.getElementById('statRecording').innerText = recording ? 'ON' : 'OFF';
    const recordBtn = document.getElementById('recordBtn');
    if (recordBtn) {
      recordBtn.innerText = recording ? 'Record Stop' : 'Record Start';
      recordBtn.classList.toggle('btn-active', recording);
    }

    if (typeof d.zoom_level === 'number' && !zoomSliderDragging) {
      document.getElementById('rngZoom').value = d.zoom_level;
      document.getElementById('zoomVal').innerText = d.zoom_level.toFixed(1) + 'x';
    }

    const banner = document.getElementById('streamBanner');
    const link = document.getElementById('streamLink');
    if (state === 'STREAMING' && d.stream_url) {
      banner.style.display = 'flex';
      link.href = d.stream_url;
      link.innerText = d.stream_url;
    } else {
      banner.style.display = 'none';
    }

    // Keep the Live Monitor (snapshot + continuous audio) in sync with the real device
    // state, so opening/reloading the dashboard mid-stream picks it up automatically too,
    // not just a fresh Start Stream click.
    if (state === 'STREAMING' && !liveMonitorActive) {
      startLiveMonitor();
    } else if (state !== 'STREAMING' && liveMonitorActive) {
      stopLiveMonitor();
    }
  }

  // ---- Live Monitor: snapshot polling + continuous audio ----
  let liveMonitorActive = false;
  let snapshotTimer = null;
  let snapshotFrameCount = 0;
  let audioMuted = false;

  function startLiveMonitor() {
    if (liveMonitorActive) return;
    liveMonitorActive = true;
    document.getElementById('liveMonitorCard').style.display = 'flex';
    const token = tokenInput.value.trim();
    const audio = document.getElementById('liveAudio');
    audio.src = '/api/audio.wav?auth_token=' + encodeURIComponent(token) + '&t=' + Date.now();
    audio.muted = audioMuted;
    audio.play().catch(() => { /* autoplay may need a user gesture on some browsers */ });
    snapshotFrameCount = 0;
    loadSnapshot();
  }

  function stopLiveMonitor() {
    liveMonitorActive = false;
    document.getElementById('liveMonitorCard').style.display = 'none';
    const audio = document.getElementById('liveAudio');
    audio.pause();
    audio.removeAttribute('src');
    audio.load();
    if (snapshotTimer) { clearTimeout(snapshotTimer); snapshotTimer = null; }
  }

  function restartSnapshotLoop() {
    if (snapshotTimer) { clearTimeout(snapshotTimer); snapshotTimer = null; }
    if (liveMonitorActive) loadSnapshot();
  }

  function loadSnapshot() {
    if (!liveMonitorActive) return;
    const token = tokenInput.value.trim();
    if (!token) return;
    const rate = parseInt(document.getElementById('selRefreshRate').value, 10) || 1000;
    const next = new Image();
    next.onload = () => {
      document.getElementById('liveSnapshot').src = next.src;
      snapshotFrameCount++;
      document.getElementById('snapshotMeta').innerText =
        'Frame #' + snapshotFrameCount + ' @ ' + new Date().toLocaleTimeString();
      if (liveMonitorActive) snapshotTimer = setTimeout(loadSnapshot, rate);
    };
    next.onerror = () => {
      if (liveMonitorActive) snapshotTimer = setTimeout(loadSnapshot, rate);
    };
    next.src = '/api/snapshot.jpg?auth_token=' + encodeURIComponent(token) + '&t=' + Date.now();
  }

  function toggleMute() {
    const audio = document.getElementById('liveAudio');
    audioMuted = !audio.muted;
    audio.muted = audioMuted;
    document.getElementById('btnMuteAudio').innerText = audioMuted ? 'Unmute Audio' : 'Mute Audio';
  }

  function saveSnapshot() {
    const img = document.getElementById('liveSnapshot');
    if (!img.src) return;
    const a = document.createElement('a');
    a.href = img.src;
    a.download = 'snapshot_' + Date.now() + '.jpg';
    document.body.appendChild(a);
    a.click();
    a.remove();
  }

  function goFullscreen() {
    const img = document.getElementById('liveSnapshot');
    if (img.requestFullscreen) img.requestFullscreen();
  }

  function toggleTorch() {
    sendAction(lastTorchActive ? 'TORCH_OFF' : 'TORCH_ON');
  }

  async function sendAction(action) {
    const token = tokenInput.value.trim();
    if (!token) {
      logMsg('Token field is empty', 'err');
      return;
    }
    const payload = {
      auth_token: token,
      action: action,
      mode: document.getElementById('selMode').value,
      camera: document.getElementById('selCamera').value,
      video_Resolution: document.getElementById('selRes').value,
      video_bitrate_kbps: parseInt(document.getElementById('numBitrate').value, 10) || 1500,
      fps: parseInt(document.getElementById('numFps').value, 10) || 30,
      duration_seconds: parseInt(document.getElementById('numDuration').value, 10) || 0,
      stream_target: document.getElementById('txtTarget').value.trim(),
      vox_threshold_db: parseInt(document.getElementById('numVox').value, 10) || 0,
      motion_sensitivity: parseInt(document.getElementById('numMotion').value, 10) || 0,
      record_locally: document.getElementById('chkRecordLocally').checked
      // web_audio_enabled intentionally omitted (defaults false): when stream_target is a
      // remote RTSP/RTMP push, DirectWebStreamer's AudioRecord must stay off to avoid mic
      // contention with RootEncoder's own prepareAudio(). It's a no-op for the DIRECT/built-in
      // path, which always gets local audio regardless of this flag.
    };

    logMsg(`Dispatching ${'$'}{action}...`, 'info');
    try {
      const res = await fetch('/api/control', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });
      const data = await res.json();
      if (res.ok && data.ok) {
        logMsg(`Action ${'$'}{action} acknowledged (state: ${'$'}{data.state})`, 'ok');
        if (action === 'START' && data.state === 'STREAMING') {
          startLiveMonitor();
        } else if (action === 'STOP') {
          stopLiveMonitor();
        }
      } else {
        logMsg(`Action ${'$'}{action} declined: ${'$'}{data.error || 'Server error'}`, 'err');
      }
      fetchStatus();
    } catch (e) {
      logMsg(`Network failure: ${'$'}{e.message}`, 'err');
    }
  }

  function copyStreamUrl() {
    const link = document.getElementById('streamLink').innerText;
    if (link && link !== '--') {
      navigator.clipboard.writeText(link).then(() => {
        const text = document.getElementById('copyBtnText');
        text.innerText = 'Copied';
        setTimeout(() => { text.innerText = 'Copy URL'; }, 1500);
      });
    }
  }

  // ---- Zoom ----
  let zoomSliderDragging = false;
  let zoomDebounceTimer = null;
  document.getElementById('rngZoom').addEventListener('pointerdown', () => { zoomSliderDragging = true; });
  document.getElementById('rngZoom').addEventListener('pointerup', () => { zoomSliderDragging = false; });

  function onZoomInput(val) {
    document.getElementById('zoomVal').innerText = parseFloat(val).toFixed(1) + 'x';
    if (zoomDebounceTimer) clearTimeout(zoomDebounceTimer);
    zoomDebounceTimer = setTimeout(() => sendZoom(parseFloat(val)), 150);
  }

  async function sendZoom(level) {
    const token = tokenInput.value.trim();
    if (!token) return;
    try {
      await fetch('/api/control', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ auth_token: token, action: 'SET_ZOOM', zoom_level: level })
      });
    } catch (e) {
      logMsg('Zoom command failed: ' + e.message, 'err');
    }
  }

  // ---- Recording ----
  function toggleRecording() {
    const recording = document.getElementById('recordBtn').classList.contains('btn-active');
    sendAction(recording ? 'RECORD_STOP' : 'RECORD_START');
  }

  async function loadRecordings() {
    const token = tokenInput.value.trim();
    if (!token) return;
    const box = document.getElementById('recordingsList');
    try {
      const res = await fetch('/api/recordings', { headers: { 'Authorization': 'Bearer ' + token } });
      if (!res.ok) { box.innerText = 'Failed to load (HTTP ' + res.status + ')'; return; }
      const data = await res.json();
      const files = data.files || [];
      if (files.length === 0) { box.innerText = 'No recordings yet.'; return; }
      box.innerHTML = '';
      files.slice().reverse().forEach((f) => {
        const row = document.createElement('div');
        const sizeMb = (f.size_bytes / (1024 * 1024)).toFixed(1);
        const when = new Date(f.modified_ms).toLocaleString();
        row.style.display = 'flex';
        row.style.justifyContent = 'space-between';
        row.style.gap = '8px';
        row.style.padding = '4px 0';
        const label = document.createElement('span');
        label.innerText = f.name + ' (' + sizeMb + ' MB, ' + when + ')';
        const actions = document.createElement('span');
        const dl = document.createElement('a');
        dl.innerText = 'Download';
        dl.href = '/api/recordings/' + encodeURIComponent(f.name) + '?auth_token=' + encodeURIComponent(token);
        dl.style.marginRight = '10px';
        dl.style.color = '#93c5fd';
        const del = document.createElement('a');
        del.innerText = 'Delete';
        del.href = '#';
        del.style.color = '#fca5a5';
        del.onclick = (e) => { e.preventDefault(); deleteRecording(f.name); };
        actions.appendChild(dl);
        actions.appendChild(del);
        row.appendChild(label);
        row.appendChild(actions);
        box.appendChild(row);
      });
    } catch (e) {
      box.innerText = 'Network error: ' + e.message;
    }
  }

  async function deleteRecording(name) {
    const token = tokenInput.value.trim();
    if (!token) return;
    try {
      await fetch('/api/recordings/' + encodeURIComponent(name), {
        method: 'DELETE',
        headers: { 'Authorization': 'Bearer ' + token }
      });
      logMsg('Deleted recording ' + name, 'ok');
      loadRecordings();
    } catch (e) {
      logMsg('Delete failed: ' + e.message, 'err');
    }
  }

  // ---- Push-to-Talk ----
  let talkCtx = null, talkStream = null, talkProcessor = null, talkSource = null;
  let talkChunks = [];
  let talkSampleRate = 16000;
  let talkActive = false;

  async function startTalk() {
    if (talkActive) return;
    talkActive = true;
    try {
      talkStream = await navigator.mediaDevices.getUserMedia({ audio: true });
      talkCtx = new (window.AudioContext || window.webkitAudioContext)();
      talkSampleRate = talkCtx.sampleRate;
      talkSource = talkCtx.createMediaStreamSource(talkStream);
      talkProcessor = talkCtx.createScriptProcessor(4096, 1, 1);
      talkChunks = [];
      talkProcessor.onaudioprocess = (e) => {
        talkChunks.push(new Float32Array(e.inputBuffer.getChannelData(0)));
      };
      talkSource.connect(talkProcessor);
      talkProcessor.connect(talkCtx.destination);
      document.getElementById('talkBtn').classList.add('btn-active');
      document.getElementById('talkStatus').innerText = 'Recording...';
    } catch (e) {
      talkActive = false;
      logMsg('Mic access failed: ' + e.message, 'err');
    }
  }

  async function stopTalk() {
    if (!talkActive) return;
    talkActive = false;
    try {
      talkProcessor.disconnect();
      talkSource.disconnect();
      talkStream.getTracks().forEach((t) => t.stop());
      const srcRate = talkSampleRate;
      const merged = mergeFloat32(talkChunks);
      await talkCtx.close();
      talkCtx = null;
      document.getElementById('talkBtn').classList.remove('btn-active');
      document.getElementById('talkStatus').innerText = 'Sending...';
      const pcm16 = downsampleTo16kMono(merged, srcRate);
      const wavBlob = encodeWav(pcm16, 16000);
      const token = tokenInput.value.trim();
      const res = await fetch('/api/talk', {
        method: 'POST',
        headers: { 'Authorization': 'Bearer ' + token, 'Content-Type': 'audio/wav' },
        body: wavBlob
      });
      document.getElementById('talkStatus').innerText = 'Idle';
      if (res.ok) logMsg('Talk clip sent', 'ok'); else logMsg('Talk send failed: HTTP ' + res.status, 'err');
    } catch (e) {
      document.getElementById('talkStatus').innerText = 'Idle';
      logMsg('Talk send error: ' + e.message, 'err');
    }
  }

  function mergeFloat32(chunks) {
    let len = 0;
    chunks.forEach((c) => { len += c.length; });
    const out = new Float32Array(len);
    let offset = 0;
    chunks.forEach((c) => { out.set(c, offset); offset += c.length; });
    return out;
  }

  function downsampleTo16kMono(float32, srcRate) {
    const dstRate = 16000;
    if (srcRate === dstRate) return floatTo16BitPCM(float32);
    const ratio = srcRate / dstRate;
    const newLen = Math.round(float32.length / ratio);
    const result = new Float32Array(newLen);
    let offsetResult = 0, offsetBuffer = 0;
    while (offsetResult < newLen) {
      const nextOffsetBuffer = Math.round((offsetResult + 1) * ratio);
      let accum = 0, count = 0;
      for (let i = offsetBuffer; i < nextOffsetBuffer && i < float32.length; i++) { accum += float32[i]; count++; }
      result[offsetResult] = count > 0 ? accum / count : 0;
      offsetResult++;
      offsetBuffer = nextOffsetBuffer;
    }
    return floatTo16BitPCM(result);
  }

  function floatTo16BitPCM(float32) {
    const out = new Int16Array(float32.length);
    for (let i = 0; i < float32.length; i++) {
      let s = Math.max(-1, Math.min(1, float32[i]));
      out[i] = s < 0 ? s * 0x8000 : s * 0x7FFF;
    }
    return out;
  }

  function encodeWav(pcm16, sampleRate) {
    const buffer = new ArrayBuffer(44 + pcm16.length * 2);
    const view = new DataView(buffer);
    function writeStr(offset, str) {
      for (let i = 0; i < str.length; i++) view.setUint8(offset + i, str.charCodeAt(i));
    }
    writeStr(0, 'RIFF');
    view.setUint32(4, 36 + pcm16.length * 2, true);
    writeStr(8, 'WAVE');
    writeStr(12, 'fmt ');
    view.setUint32(16, 16, true);
    view.setUint16(20, 1, true);
    view.setUint16(22, 1, true);
    view.setUint32(24, sampleRate, true);
    view.setUint32(28, sampleRate * 2, true);
    view.setUint16(32, 2, true);
    view.setUint16(34, 16, true);
    writeStr(36, 'data');
    view.setUint32(40, pcm16.length * 2, true);
    let offset = 44;
    for (let i = 0; i < pcm16.length; i++, offset += 2) { view.setInt16(offset, pcm16[i], true); }
    return new Blob([buffer], { type: 'audio/wav' });
  }

  fetchStatus();
  setInterval(fetchStatus, 2000);
  loadRecordings();
</script>
</body>
</html>"""
}
