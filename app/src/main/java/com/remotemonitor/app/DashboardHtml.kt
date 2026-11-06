package com.remotemonitor.app

object DashboardHtml {
    const val CONTENT = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>RemoteMonitor Dashboard</title>
<style>
  :root {
    --bg: #0b0f19;
    --card: #151d30;
    --card-border: #22304d;
    --text: #f1f5f9;
    --muted: #94a3b8;
    --accent: #3b82f6;
    --success: #10b981;
    --danger: #ef4444;
    --warning: #f59e0b;
    --purple: #8b5cf6;
  }
  * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
  body { background: var(--bg); color: var(--text); padding: 20px; line-height: 1.5; }
  .container { max-width: 900px; margin: 0 auto; display: flex; flex-direction: column; gap: 20px; }
  header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 10px; padding-bottom: 10px; border-bottom: 1px solid var(--card-border); }
  h1 { font-size: 1.5rem; font-weight: 700; color: #fff; display: flex; align-items: center; gap: 10px; }
  .badge { padding: 4px 12px; border-radius: 999px; font-size: 0.85rem; font-weight: 600; text-transform: uppercase; letter-spacing: 0.5px; display: inline-flex; align-items: center; gap: 6px; }
  .badge-idle { background: rgba(148,163,184,0.15); color: var(--muted); border: 1px solid var(--muted); }
  .badge-streaming { background: rgba(239,68,68,0.2); color: #fca5a5; border: 1px solid var(--danger); animation: pulse 2s infinite; }
  .badge-starting { background: rgba(245,158,11,0.2); color: #fde68a; border: 1px solid var(--warning); }
  @keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.6; } }
  .dot { width: 8px; height: 8px; border-radius: 50%; background: currentColor; }
  
  .card { background: var(--card); border: 1px solid var(--card-border); border-radius: 12px; padding: 18px; display: flex; flex-direction: column; gap: 14px; }
  .card-title { font-size: 0.95rem; font-weight: 600; text-transform: uppercase; letter-spacing: 0.5px; color: var(--muted); display: flex; justify-content: space-between; align-items: center; }

  .grid-stats { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 12px; }
  .stat-box { background: rgba(255,255,255,0.02); border: 1px solid rgba(255,255,255,0.05); padding: 10px 12px; border-radius: 8px; }
  .stat-label { font-size: 0.75rem; color: var(--muted); text-transform: uppercase; }
  .stat-val { font-size: 1.15rem; font-weight: 700; color: #fff; margin-top: 2px; }

  .stream-banner { background: rgba(59,130,246,0.1); border: 1px dashed var(--accent); border-radius: 8px; padding: 12px; display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 10px; }
  .stream-url { font-family: monospace; font-size: 0.95rem; color: #93c5fd; word-break: break-all; }
  
  .grid-form { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 14px; }
  .form-group { display: flex; flex-direction: column; gap: 6px; }
  label { font-size: 0.8rem; font-weight: 500; color: var(--muted); }
  input[type="text"], input[type="password"], input[type="number"], select {
    background: #0f172a; border: 1px solid var(--card-border); color: #fff; padding: 9px 12px; border-radius: 8px; font-size: 0.9rem; outline: none; transition: border-color 0.2s;
  }
  input:focus, select:focus { border-color: var(--accent); }
  
  .btn-row { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 4px; }
  button {
    flex: 1 1 130px; padding: 11px 16px; border: none; border-radius: 8px; font-size: 0.9rem; font-weight: 600; cursor: pointer; transition: all 0.15s ease; display: inline-flex; justify-content: center; align-items: center; gap: 6px; color: #fff;
  }
  button:active { transform: scale(0.98); }
  .btn-start { background: var(--success); }
  .btn-start:hover { background: #059669; }
  .btn-stop { background: var(--danger); }
  .btn-stop:hover { background: #dc2626; }
  .btn-switch { background: var(--purple); }
  .btn-switch:hover { background: #7c3aed; }
  .btn-torch-on { background: var(--warning); color: #000; }
  .btn-torch-on:hover { background: #d97706; }
  .btn-torch-off { background: #334155; }
  .btn-torch-off:hover { background: #475569; }
  .btn-secondary { background: #1e293b; border: 1px solid var(--card-border); color: var(--muted); flex: 0 0 auto; }
  .btn-secondary:hover { color: #fff; border-color: var(--muted); }

  .console-box { background: #070b13; border: 1px solid var(--card-border); border-radius: 8px; padding: 10px 14px; font-family: monospace; font-size: 0.82rem; color: #38bdf8; max-height: 140px; overflow-y: auto; white-space: pre-wrap; }
</style>
</head>
<body>
<div class="container">
  <header>
    <h1>📹 RemoteMonitor <span>Kiosk</span></h1>
    <div id="statusBadge" class="badge badge-idle"><span class="dot"></span><span id="badgeText">IDLE</span></div>
  </header>

  <!-- Auth Configuration -->
  <div class="card">
    <div class="card-title">
      <span>Authentication</span>
      <span style="font-size: 0.75rem; color: var(--muted);">Auto-saved locally</span>
    </div>
    <div class="form-group">
      <label for="tokenInput">Secret Auth Token</label>
      <input type="password" id="tokenInput" placeholder="Enter auth token..." autocomplete="off">
    </div>
  </div>

  <!-- Live Telemetry -->
  <div class="card">
    <div class="card-title">
      <span>Live Device Status</span>
      <button class="btn-secondary" style="padding: 4px 10px; font-size: 0.75rem;" onclick="fetchStatus()">Refresh</button>
    </div>
    <div class="grid-stats">
      <div class="stat-box"><div class="stat-label">State</div><div class="stat-val" id="statState">--</div></div>
      <div class="stat-box"><div class="stat-label">Battery</div><div class="stat-val" id="statBattery">--%</div></div>
      <div class="stat-box"><div class="stat-label">Remaining</div><div class="stat-val" id="statRemaining">--s</div></div>
      <div class="stat-box"><div class="stat-label">Camera</div><div class="stat-val" id="statCamera">--</div></div>
      <div class="stat-box"><div class="stat-label">Torch</div><div class="stat-val" id="statTorch">OFF</div></div>
      <div class="stat-box"><div class="stat-label">Wi-Fi IP</div><div class="stat-val" id="statWifi" style="font-size:0.95rem;">--</div></div>
    </div>
    <div id="streamBanner" class="stream-banner" style="display:none;">
      <div>
        <div style="font-size: 0.75rem; color: var(--muted); text-transform: uppercase;">Active Stream URL</div>
        <a id="streamLink" class="stream-url" href="#" target="_blank">--</a>
      </div>
      <button class="btn-secondary" style="padding: 6px 12px; font-size: 0.8rem;" onclick="copyStreamUrl()">Copy</button>
    </div>
  </div>

  <!-- Live Monitor -->
  <div class="card" id="liveMonitorCard" style="display:none;">
    <div class="card-title">
      <span>🔴 Live Monitor</span>
      <span id="snapshotMeta" style="font-size:0.75rem; color: var(--muted);">--</span>
    </div>
    <div style="position:relative; background:#000; border-radius:8px; overflow:hidden; display:flex; align-items:center; justify-content:center;">
      <img id="liveSnapshot" style="width:100%; display:block; max-height:480px; object-fit:contain; background:#000;" alt="live snapshot">
    </div>
    <audio id="liveAudio" controls style="width:100%;"></audio>
    <div class="btn-row">
      <button class="btn-secondary" id="btnMuteAudio" onclick="toggleMute()">🔊 Mute Live Audio</button>
      <button class="btn-secondary" onclick="saveSnapshot()">📸 Save Snapshot</button>
      <button class="btn-secondary" onclick="goFullscreen()">🖥️ Fullscreen</button>
      <select id="selRefreshRate" onchange="restartSnapshotLoop()" style="flex: 0 0 110px;">
        <option value="500">2 fps</option>
        <option value="1000" selected>1 fps</option>
        <option value="2000">0.5 fps</option>
      </select>
    </div>
  </div>

  <!-- Stream Controls -->
  <div class="card">
    <div class="card-title">Stream Controls & Configuration</div>
    <div class="grid-form">
      <div class="form-group">
        <label for="selMode">Capture Mode</label>
        <select id="selMode">
          <option value="BOTH" selected>BOTH (Video + Audio)</option>
          <option value="VIDEO_ONLY">VIDEO_ONLY (Mute Mic)</option>
          <option value="AUDIO_ONLY">AUDIO_ONLY (Mic Only)</option>
        </select>
      </div>
      <div class="form-group">
        <label for="selCamera">Camera</label>
        <select id="selCamera">
          <option value="BACK" selected>BACK Camera</option>
          <option value="FRONT">FRONT Camera</option>
        </select>
      </div>
      <div class="form-group">
        <label for="selRes">Resolution</label>
        <select id="selRes">
          <option value="720p" selected>720p (HD)</option>
          <option value="1080p">1080p (FHD)</option>
          <option value="480p">480p (SD)</option>
        </select>
      </div>
      <div class="form-group">
        <label for="numBitrate">Video Bitrate (Kbps)</label>
        <input type="number" id="numBitrate" value="1500" min="200" max="8000" step="100">
      </div>
      <div class="form-group">
        <label for="numFps">FPS</label>
        <input type="number" id="numFps" value="30" min="10" max="60" step="5">
      </div>
      <div class="form-group">
        <label for="numDuration">Duration (Seconds, 0 = Indefinite)</label>
        <input type="number" id="numDuration" value="0" min="0" step="10">
      </div>
      <div class="form-group" style="grid-column: 1 / -1;">
        <label for="txtTarget">Push Stream Target URL (Leave empty for Built-in RTSP Server on :8554)</label>
        <input type="text" id="txtTarget" placeholder="rtsp://... or rtmp://... (optional)">
      </div>
    </div>

    <div class="btn-row">
      <button class="btn-start" onclick="sendAction('START')">▶ START Stream</button>
      <button class="btn-stop" onclick="sendAction('STOP')">⏹ STOP Stream</button>
      <button class="btn-switch" onclick="sendAction('SWITCH_CAMERA')">🔄 Switch Cam</button>
      <button class="btn-torch-on" onclick="sendAction('TORCH_ON')">🔦 Torch ON</button>
      <button class="btn-torch-off" onclick="sendAction('TORCH_OFF')">🔦 Torch OFF</button>
    </div>
  </div>

  <!-- Activity Console -->
  <div class="card">
    <div class="card-title">Activity Console</div>
    <div id="consoleLog" class="console-box">[Ready] Enter your Auth Token above. Polling device status every 2s...</div>
  </div>
</div>

<script>
  const tokenInput = document.getElementById('tokenInput');
  const savedToken = localStorage.getItem('rm_auth_token') || 'changeme-secret-token';
  tokenInput.value = savedToken;
  tokenInput.addEventListener('input', () => {
    localStorage.setItem('rm_auth_token', tokenInput.value);
  });

  function logMsg(msg) {
    const el = document.getElementById('consoleLog');
    const ts = new Date().toLocaleTimeString();
    el.innerText = `[${'$'}{ts}] ${'$'}{msg}\n` + el.innerText.split('\n').slice(0, 15).join('\n');
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
        document.getElementById('statusBadge').className = 'badge badge-idle';
        logMsg('HTTP 401 Unauthorized: Invalid Auth Token');
        return;
      }
      if (!res.ok) {
        logMsg('Status check failed: HTTP ' + res.status);
        return;
      }
      const data = await res.json();
      updateUi(data);
    } catch (e) {
      document.getElementById('badgeText').innerText = 'OFFLINE';
      document.getElementById('statusBadge').className = 'badge badge-idle';
    }
  }

  function updateUi(d) {
    const state = d.state || 'IDLE';
    const badge = document.getElementById('statusBadge');
    const text = document.getElementById('badgeText');
    text.innerText = state;
    badge.className = 'badge ' + (state === 'STREAMING' ? 'badge-streaming' : (state === 'STARTING' ? 'badge-starting' : 'badge-idle'));

    document.getElementById('statState').innerText = state;
    document.getElementById('statBattery').innerText = (d.battery_percent ?? '--') + '%' + (d.charging ? ' ⚡' : '');
    document.getElementById('statRemaining').innerText = (d.remaining_seconds ?? 0) > 0 ? (d.remaining_seconds + 's') : (state === 'STREAMING' ? '∞' : '0s');
    document.getElementById('statCamera').innerText = d.camera || (d.active_camera || '--');
    document.getElementById('statTorch').innerText = d.torch_active ? 'ON 💡' : 'OFF';
    document.getElementById('statWifi').innerText = d.ips?.wifi || d.ips?.best || '--';

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
    // not just a fresh START click.
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
    document.getElementById('btnMuteAudio').innerText = audioMuted ? '🔇 Unmute Live Audio' : '🔊 Mute Live Audio';
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

  async function sendAction(action) {
    const token = tokenInput.value.trim();
    if (!token) {
      alert('Please enter your Auth Token first!');
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

    logMsg(`Sending action: ${'$'}{action}...`);
    try {
      const res = await fetch('/api/control', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });
      const data = await res.json();
      if (res.ok && data.ok) {
        logMsg(`Action ${'$'}{action} accepted! State: ${'$'}{data.state}`);
        if (action === 'START' && data.state === 'STREAMING') {
          startLiveMonitor();
        } else if (action === 'STOP') {
          stopLiveMonitor();
        }
      } else {
        logMsg(`Action ${'$'}{action} rejected: ${'$'}{data.error || 'Unknown error'}`);
      }
      fetchStatus();
    } catch (e) {
      logMsg(`Error sending ${'$'}{action}: ${'$'}{e.message}`);
    }
  }

  function copyStreamUrl() {
    const link = document.getElementById('streamLink').innerText;
    if (link && link !== '--') {
      navigator.clipboard.writeText(link).then(() => {
        logMsg('Copied stream URL to clipboard: ' + link);
      });
    }
  }

  fetchStatus();
  setInterval(fetchStatus, 2000);
</script>
</body>
</html>"""
}

