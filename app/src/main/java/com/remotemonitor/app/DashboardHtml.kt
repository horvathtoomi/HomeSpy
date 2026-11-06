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
    </div>
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

    const banner = document.getElementById('streamBanner');
    const link = document.getElementById('streamLink');
    if (state === 'STREAMING' && d.stream_url) {
      banner.style.display = 'flex';
      link.href = d.stream_url;
      link.innerText = d.stream_url;
    } else {
      banner.style.display = 'none';
    }
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
      stream_target: document.getElementById('txtTarget').value.trim()
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

  fetchStatus();
  setInterval(fetchStatus, 2000);
</script>
</body>
</html>"""
}
