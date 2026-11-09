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
    --bg: #07090e;
    --card: #0f131d;
    --card-elevated: #161b28;
    --border: #1e2638;
    --border-subtle: #171c2b;
    --border-focus: #3b82f6;
    --accent: #3b82f6;
    --accent-bg: rgba(59, 130, 246, 0.12);
    --text-primary: #f1f5f9;
    --text-secondary: #94a3b8;
    --text-muted: #64748b;
    --live: #10b981;
    --live-bg: rgba(16, 185, 129, 0.1);
    --live-border: rgba(16, 185, 129, 0.3);
    --danger: #ef4444;
    --danger-bg: rgba(239, 68, 68, 0.08);
    --danger-border: rgba(239, 68, 68, 0.25);
    --warn: #fbbf24;
  }
  * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif; }
  html, body { background: var(--bg); color: var(--text-primary); -webkit-font-smoothing: antialiased; }
  body { min-height: 100vh; }
  button svg, a svg { width: 14px; height: 14px; stroke: currentColor; fill: none; flex-shrink: 0; }
  .hidden { display: none !important; }

  header { position: sticky; top: 0; z-index: 20; display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 12px 20px; background: rgba(7,9,14,0.92); backdrop-filter: blur(8px); border-bottom: 1px solid var(--border); flex-wrap: wrap; }
  .brand-title { font-size: 1rem; font-weight: 700; letter-spacing: -0.01em; }
  .brand-sub { font-size: 0.72rem; color: var(--text-muted); margin-top: 1px; }
  .status-badge { display: inline-flex; align-items: center; gap: 8px; padding: 5px 12px; border-radius: 7px; font-size: 0.72rem; font-weight: 700; letter-spacing: 0.05em; text-transform: uppercase; background: var(--card-elevated); border: 1px solid var(--border); color: var(--text-muted); }
  .status-badge.streaming { background: var(--live-bg); border-color: var(--live-border); color: var(--live); }
  .status-badge.starting { background: rgba(251,191,36,0.1); border-color: rgba(251,191,36,0.3); color: var(--warn); }
  .status-dot { width: 7px; height: 7px; border-radius: 50%; background: currentColor; }
  .status-badge.streaming .status-dot { animation: pulse 2s cubic-bezier(0.4,0,0.6,1) infinite; }
  @keyframes pulse { 0%,100% { opacity: 1; } 50% { opacity: 0.35; } }

  button { font-size: 0.82rem; font-weight: 500; padding: 9px 14px; border-radius: 7px; border: 1px solid var(--border); background: var(--card-elevated); color: var(--text-primary); cursor: pointer; display: inline-flex; align-items: center; gap: 6px; transition: background .12s ease, border-color .12s ease; user-select: none; white-space: nowrap; }
  button:hover { background: #1e2436; border-color: #2b334a; }
  button:active { transform: translateY(1px); }
  .btn-start { background: #f8fafc; color: #07090e; border-color: #f8fafc; font-weight: 700; }
  .btn-start:hover { background: #e2e8f0; border-color: #e2e8f0; }
  .btn-stop { background: var(--danger-bg); border-color: var(--danger-border); color: #fca5a5; }
  .btn-stop:hover { background: rgba(239,68,68,0.18); border-color: rgba(239,68,68,0.4); }
  .btn-active { border-color: var(--accent); color: #93c5fd; background: var(--accent-bg); }
  .btn-rec.btn-active { border-color: var(--danger); color: #fca5a5; background: var(--danger-bg); }
  .btn-secondary { font-size: 0.72rem; padding: 5px 10px; color: var(--text-muted); background: transparent; border-color: var(--border-subtle); }
  .btn-secondary:hover { color: var(--text-primary); }
  .btn-icon-only { padding: 7px 9px; }

  input[type="text"], input[type="number"], select {
    background: var(--card-elevated); border: 1px solid var(--border); color: var(--text-primary);
    font-size: 0.84rem; padding: 8px 11px; border-radius: 7px; outline: none; width: 100%; transition: border-color .15s ease;
  }
  input:focus, select:focus { border-color: var(--border-focus); }
  .form-item { display: flex; flex-direction: column; gap: 5px; }
  .form-label { font-size: 0.72rem; font-weight: 500; color: var(--text-secondary); }
  .form-row { display: flex; align-items: center; gap: 8px; }
  .form-row input[type="checkbox"] { width: auto; }
  .form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }

  .workspace { max-width: 1440px; margin: 0 auto; padding: 18px 20px 40px; display: grid; grid-template-columns: 1fr 400px; gap: 18px; align-items: start; }
  @media (max-width: 960px) {
    .workspace { grid-template-columns: 1fr; padding: 14px; }
    header { padding: 10px 14px; }
  }

  .stage { display: flex; flex-direction: column; gap: 14px; }
  .sidebar { display: flex; flex-direction: column; gap: 12px; }

  .video-viewport { position: relative; background: #000; border-radius: 10px; overflow: hidden; border: 1px solid var(--border); min-height: 420px; max-height: 68vh; aspect-ratio: 16/9; display: flex; align-items: center; justify-content: center; }
  .video-viewport img { width: 100%; height: 100%; object-fit: contain; background: #000; display: block; }
  .viewport-placeholder { position: absolute; inset: 0; display: flex; align-items: center; justify-content: center; color: var(--text-muted); font-size: 0.86rem; background: #04050a; text-align: center; padding: 20px; }
  .hud-top-left, .hud-top-right { position: absolute; top: 10px; display: flex; gap: 6px; z-index: 5; }
  .hud-top-left { left: 10px; flex-wrap: wrap; max-width: 75%; }
  .hud-top-right { right: 10px; }
  .hud-badge { display: inline-flex; align-items: center; gap: 5px; padding: 4px 9px; border-radius: 6px; font-size: 0.68rem; font-weight: 600; background: rgba(7,9,14,0.75); backdrop-filter: blur(4px); border: 1px solid rgba(255,255,255,0.08); color: var(--text-secondary); white-space: nowrap; }
  .hud-status-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--text-muted); }
  .hud-status-dot.live { background: var(--live); animation: pulse 2s cubic-bezier(.4,0,.6,1) infinite; }
  .hud-status-dot.starting { background: var(--warn); }
  .hud-rec { color: #fca5a5; border-color: rgba(239,68,68,0.4); }
  .hud-rec .hud-status-dot { background: var(--danger); animation: pulse 1.2s cubic-bezier(.4,0,.6,1) infinite; }
  .hud-icon-btn { background: rgba(7,9,14,0.75); backdrop-filter: blur(4px); border: 1px solid rgba(255,255,255,0.08); border-radius: 6px; padding: 7px 8px; color: var(--text-secondary); }
  .hud-icon-btn:hover { color: var(--text-primary); background: rgba(7,9,14,0.9); }

  .card { background: var(--card); border: 1px solid var(--border); border-radius: 10px; padding: 14px 16px; display: flex; flex-direction: column; gap: 12px; }
  .action-bar { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
  .zoom-inline { display: flex; align-items: center; gap: 8px; flex: 1 1 160px; min-width: 160px; }
  .zoom-inline input[type="range"] { flex: 1; accent-color: var(--accent); }
  .zoom-inline span { font-size: 0.76rem; color: var(--text-secondary); font-variant-numeric: tabular-nums; width: 40px; text-align: right; }

  .talk-row { display: flex; align-items: center; gap: 10px; }
  #talkBtn.btn-active { background: var(--danger-bg); border-color: var(--danger); color: #fca5a5; animation: talkpulse 1.1s ease-in-out infinite; }
  @keyframes talkpulse { 0%,100% { box-shadow: 0 0 0 0 rgba(239,68,68,0.35); } 50% { box-shadow: 0 0 0 8px rgba(239,68,68,0); } }

  .audio-bar { display: flex; align-items: center; gap: 10px; }
  .audio-bar audio { flex: 1; height: 32px; }

  .tab-nav { display: flex; gap: 4px; background: var(--card); border: 1px solid var(--border); border-radius: 10px; padding: 4px; }
  .tab-btn { flex: 1; font-size: 0.72rem; font-weight: 600; padding: 7px 6px; border-radius: 7px; border: none; background: transparent; color: var(--text-muted); }
  .tab-btn.active { background: var(--card-elevated); color: var(--text-primary); border: 1px solid var(--border); }
  .tab-btn:hover { color: var(--text-primary); }
  .tab-panel { display: flex; flex-direction: column; gap: 12px; max-height: calc(100vh - 160px); overflow-y: auto; }

  .console-box { background: #04050a; border: 1px solid var(--border); border-radius: 7px; padding: 10px 12px; font-family: ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace; font-size: 0.72rem; color: var(--text-secondary); max-height: 320px; overflow-y: auto; white-space: pre-wrap; line-height: 1.5; }

  .rec-row { display: flex; justify-content: space-between; align-items: center; gap: 8px; padding: 6px 0; border-bottom: 1px solid var(--border-subtle); font-size: 0.78rem; }
  .rec-row:last-child { border-bottom: none; }
  .rec-name { word-break: break-all; }
  .rec-actions { display: flex; gap: 10px; flex-shrink: 0; }
  .rec-actions a { color: #93c5fd; font-size: 0.74rem; text-decoration: none; }
  .rec-actions a.danger { color: #fca5a5; }

  .section-label { font-size: 0.68rem; font-weight: 700; text-transform: uppercase; letter-spacing: 0.06em; color: var(--text-muted); margin-top: 2px; }
  .hint { font-size: 0.7rem; color: var(--text-muted); line-height: 1.5; }

  .info-row { display: flex; justify-content: space-between; align-items: center; gap: 8px; padding: 9px 11px; background: var(--card-elevated); border: 1px solid var(--border-subtle); border-radius: 7px; }
  .info-label { font-size: 0.68rem; color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.04em; }
  .info-value { font-size: 0.84rem; font-weight: 600; font-family: ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace; word-break: break-all; }
</style>
</head>
<body>

<header>
  <div>
    <div class="brand-title">RemoteMonitor Console</div>
    <div class="brand-sub">Samsung SM-A405FN &middot; Android 11 Kiosk</div>
  </div>
  <div id="statusBadge" class="status-badge">
    <span class="status-dot"></span>
    <span id="badgeText">IDLE</span>
  </div>
</header>

<div class="workspace">
  <!-- ================= LEFT COLUMN: PRIMARY STAGE ================= -->
  <div class="stage">
    <div class="video-viewport" id="videoViewport">
      <img id="liveSnapshot" class="hidden" alt="live stream">
      <div id="viewportPlaceholder" class="viewport-placeholder">Press Start Stream to begin.</div>

      <div class="hud-top-left">
        <span class="hud-badge"><span class="hud-status-dot" id="hudStatusDot"></span><span id="hudStateText">IDLE</span></span>
        <span class="hud-badge"><svg viewBox="0 0 24 24" stroke-width="2"><rect x="1" y="6" width="18" height="12" rx="2" ry="2"/><line x1="23" y1="10" x2="23" y2="14"/></svg><span id="hudBattery">--</span><svg id="hudChargeIcon" class="hidden" viewBox="0 0 24 24" stroke-width="2" fill="currentColor"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg></span>
        <span class="hud-badge"><svg viewBox="0 0 24 24" stroke-width="2"><path d="M14 4v10.54a4 4 0 1 1-4 0V4a2 2 0 0 1 4 0z"/></svg><span id="hudTemp">--</span></span>
        <span class="hud-badge"><svg viewBox="0 0 24 24" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg><span id="hudRemaining">--</span></span>
        <span class="hud-badge hud-rec hidden" id="hudRec"><span class="hud-status-dot"></span>REC</span>
      </div>
      <div class="hud-top-right">
        <button class="hud-icon-btn btn-icon-only" onclick="saveSnapshot()" title="Save snapshot">
          <svg viewBox="0 0 24 24" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
        </button>
        <button class="hud-icon-btn btn-icon-only" onclick="toggleFullscreen()" title="Fullscreen">
          <svg viewBox="0 0 24 24" stroke-width="2"><path d="M8 3H5a2 2 0 0 0-2 2v3m18 0V5a2 2 0 0 0-2-2h-3M3 16v3a2 2 0 0 0 2 2h3m13-5v3a2 2 0 0 0-2 2h-3"/></svg>
        </button>
      </div>
    </div>

    <div class="card">
      <div class="action-bar">
        <button class="btn-start" onclick="sendAction('START')"><svg viewBox="0 0 24 24" stroke-width="2"><polygon points="5 3 19 12 5 21 5 3"/></svg>Start Stream</button>
        <button class="btn-stop" onclick="sendAction('STOP')"><svg viewBox="0 0 24 24" stroke-width="2"><rect x="4" y="4" width="16" height="16" rx="2"/></svg>Stop Stream</button>
        <button onclick="sendAction('SWITCH_CAMERA')"><svg viewBox="0 0 24 24" stroke-width="2"><path d="M20 16v5h-5M4 8V3h5M16 4l5 5M8 20l-5-5M20 9a9 9 0 0 0-14.7-4.7M4 15a9 9 0 0 0 14.7 4.7"/></svg>Switch Cam</button>
        <button id="torchBtn" onclick="toggleTorch()"><svg viewBox="0 0 24 24" stroke-width="2"><path d="M18 6h-2a2 2 0 0 0-2-2h-4a2 2 0 0 0-2 2H6a2 2 0 0 0-2 2v2l3 3v7a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2v-7l3-3V8a2 2 0 0 0-2-2z"/></svg><span id="torchBtnText">Torch ON</span></button>
        <button onclick="sendAction('AUTOFOCUS')"><svg viewBox="0 0 24 24" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="22" y1="12" x2="18" y2="12"/><line x1="6" y1="12" x2="2" y2="12"/><line x1="12" y1="6" x2="12" y2="2"/><line x1="12" y1="22" x2="12" y2="18"/></svg>Autofocus</button>
        <button id="recordBtn" class="btn-rec" onclick="toggleRecording()"><svg viewBox="0 0 24 24" stroke-width="2" fill="currentColor"><circle cx="12" cy="12" r="8"/></svg><span id="recordBtnText">Record</span></button>
        <div class="zoom-inline">
          <svg viewBox="0 0 24 24" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/><line x1="8" y1="11" x2="14" y2="11"/></svg>
          <input type="range" id="rngZoom" min="1" max="8" step="0.1" value="1" oninput="onZoomInput(this.value)">
          <span id="zoomVal">1.0x</span>
        </div>
      </div>

      <div class="talk-row">
        <button id="talkBtn" class="btn-start" onpointerdown="startTalk()">
          <svg viewBox="0 0 24 24" stroke-width="2"><path d="M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3z"/><path d="M19 10v2a7 7 0 0 1-14 0v-2"/><line x1="12" y1="19" x2="12" y2="23"/><line x1="8" y1="23" x2="16" y2="23"/></svg>
          Hold to Talk
        </button>
        <span id="talkStatus" class="hint">Idle</span>
      </div>

      <div class="audio-bar">
        <svg viewBox="0 0 24 24" stroke-width="2"><path d="M11 5L6 9H2v6h4l5 4V5z"/></svg>
        <audio id="liveAudio" controls></audio>
        <button class="btn-secondary btn-icon-only" onclick="toggleMute()" title="Mute">
          <svg viewBox="0 0 24 24" stroke-width="2"><path d="M11 5L6 9H2v6h4l5 4V5z"/><line x1="23" y1="9" x2="17" y2="15"/><line x1="17" y1="9" x2="23" y2="15"/></svg>
        </button>
      </div>
    </div>
  </div>

  <!-- ================= RIGHT COLUMN: TABBED SIDEBAR ================= -->
  <div class="sidebar">
    <div class="tab-nav">
      <button class="tab-btn active" id="tabBtn1" onclick="switchTab(1)">Stream</button>
      <button class="tab-btn" id="tabBtn2" onclick="switchTab(2)">Device</button>
      <button class="tab-btn" id="tabBtn3" onclick="switchTab(3)">Recordings</button>
      <button class="tab-btn" id="tabBtn4" onclick="switchTab(4)">Log</button>
    </div>

    <!-- Tab 1: Stream & Sentinel -->
    <div class="card tab-panel" id="tabPanel1">
      <div class="section-label">Capture</div>
      <div class="form-grid">
        <div class="form-item">
          <label class="form-label" for="selMode">Mode</label>
          <select id="selMode"><option value="BOTH" selected>Video + Audio</option><option value="VIDEO_ONLY">Video Only</option><option value="AUDIO_ONLY">Audio Only</option></select>
        </div>
        <div class="form-item">
          <label class="form-label" for="selCamera">Sensor</label>
          <select id="selCamera"><option value="BACK" selected>Rear</option><option value="FRONT">Front</option></select>
        </div>
        <div class="form-item">
          <label class="form-label" for="selRes">Resolution</label>
          <select id="selRes"><option value="720p" selected>720p</option><option value="1080p">1080p</option><option value="480p">480p</option></select>
        </div>
        <div class="form-item">
          <label class="form-label" for="numBitrate">Bitrate (Kbps)</label>
          <input type="number" id="numBitrate" value="1500" min="200" max="8000" step="100">
        </div>
        <div class="form-item">
          <label class="form-label" for="numFps">FPS</label>
          <input type="number" id="numFps" value="30" min="10" max="60" step="5">
        </div>
        <div class="form-item">
          <label class="form-label" for="numDuration">Duration (s, 0=&infin;)</label>
          <input type="number" id="numDuration" value="0" min="0" step="10">
        </div>
      </div>
      <div class="form-item">
        <label class="form-label" for="txtTarget">Push Target URL (empty = built-in RTSP server on :8554)</label>
        <input type="text" id="txtTarget" placeholder="rtsp://... or rtmp://... (optional)">
      </div>
      <div class="section-label">Sentinel</div>
      <div class="form-grid">
        <div class="form-item">
          <label class="form-label" for="numVox">VOX Threshold (dB, 0=off)</label>
          <input type="number" id="numVox" value="0" min="-60" max="0" step="1">
        </div>
        <div class="form-item">
          <label class="form-label" for="numMotion">Motion Sensitivity (0-100)</label>
          <input type="number" id="numMotion" value="0" min="0" max="100" step="1">
        </div>
      </div>
      <div class="form-row">
        <input type="checkbox" id="chkRecordLocally">
        <label class="form-label" for="chkRecordLocally">Record locally on Start</label>
      </div>
      <button class="btn-start" onclick="sendAction('START')" style="justify-content:center;">Apply &amp; Restart Stream</button>
    </div>

    <!-- Tab 2: Device & Remote Access -->
    <div class="card tab-panel hidden" id="tabPanel2">
      <div class="section-label">Device Identity</div>
      <div class="info-row"><span class="info-label">Primary IP</span><span class="info-value" id="infoIp">--</span></div>
      <div class="info-row"><span class="info-label">Ntfy Topic</span><span class="info-value" id="infoNtfy">--</span></div>
      <div class="section-label">Remote Control</div>
      <div class="hint">Open the standalone remote_controller.html Command Center from any PC or phone browser and it auto-discovers this device on the LAN. To skip discovery, append this device's IP as a query parameter instead.</div>
      <div class="info-row"><span class="info-label">Query Suffix</span><span id="infoControllerLink" class="info-value">--</span></div>
      <button class="btn-secondary" onclick="copyControllerLink()"><span id="copyLinkBtnText">Copy</span></button>
      <div id="streamBanner" class="info-row hidden" style="border-left:3px solid var(--live);">
        <span class="info-label">Push Target</span>
        <a id="streamLink" class="info-value" href="#" target="_blank" style="color:#93c5fd; text-decoration:none;">--</a>
      </div>
    </div>

    <!-- Tab 3: Recordings -->
    <div class="card tab-panel hidden" id="tabPanel3">
      <div class="card-header" style="display:flex; justify-content:space-between; align-items:center;">
        <span class="section-label" style="margin:0;">Recordings</span>
        <button class="btn-secondary" onclick="loadRecordings()">Refresh</button>
      </div>
      <div id="recordingsList" class="console-box">No recordings loaded.</div>
    </div>

    <!-- Tab 4: Activity Log -->
    <div class="card tab-panel hidden" id="tabPanel4">
      <div class="card-header" style="display:flex; justify-content:space-between; align-items:center;">
        <span class="section-label" style="margin:0;">Activity Log</span>
        <button class="btn-secondary" onclick="clearLog()">Clear</button>
      </div>
      <div id="consoleLog" class="console-box">[00:00:00] Console initialized. Polling device status every 2s.</div>
    </div>
  </div>
</div>

<script>
  let lastTorchActive = false;
  let lastIsRecording = false;
  let liveActive = false;
  let mjpegActive = false;
  let snapshotTimer = null;
  let zoomSliderDragging = false;
  let zoomDebounceTimer = null;
  let audioMuted = false;

  function logMsg(msg, type) {
    type = type || 'info';
    var el = document.getElementById('consoleLog');
    var ts = new Date().toTimeString().split(' ')[0];
    var prefix = type === 'err' ? '[ERR]' : (type === 'ok' ? '[OK]' : '[INFO]');
    var line = '[' + ts + '] ' + prefix + ' ' + msg + '\n';
    el.innerText = line + el.innerText.split('\n').slice(0, 60).join('\n');
  }
  function clearLog() { document.getElementById('consoleLog').innerText = ''; }

  function switchTab(n) {
    for (var i = 1; i <= 4; i++) {
      document.getElementById('tabBtn' + i).classList.toggle('active', i === n);
      document.getElementById('tabPanel' + i).classList.toggle('hidden', i !== n);
    }
    if (n === 3) loadRecordings();
  }

  async function fetchStatus() {
    try {
      const res = await fetch('/api/status');
      if (!res.ok) {
        logMsg('Telemetry query returned HTTP ' + res.status, 'err');
        return;
      }
      const data = await res.json();
      updateUi(data);
    } catch (e) {
      document.getElementById('badgeText').innerText = 'UNREACHABLE';
      document.getElementById('statusBadge').className = 'status-badge';
      document.getElementById('hudStateText').innerText = 'UNREACHABLE';
    }
  }

  function updateUi(d) {
    const state = d.state || 'IDLE';
    const badge = document.getElementById('statusBadge');
    const badgeText = document.getElementById('badgeText');
    badgeText.innerText = state;
    badge.className = 'status-badge ' + (state === 'STREAMING' ? 'streaming' : (state === 'STARTING' ? 'starting' : ''));

    document.getElementById('hudStateText').innerText = state;
    document.getElementById('hudStatusDot').className = 'hud-status-dot' + (state === 'STREAMING' ? ' live' : (state === 'STARTING' ? ' starting' : ''));

    const battPct = d.battery_percent;
    document.getElementById('hudBattery').innerText = (battPct !== undefined && battPct !== null) ? battPct + '%' : '--';
    document.getElementById('hudChargeIcon').classList.toggle('hidden', !d.charging);

    const temp = d.temperature_c;
    document.getElementById('hudTemp').innerText = (temp !== undefined && temp !== null) ? Number(temp).toFixed(1) + 'C' : '--';

    const remSec = d.remaining_seconds || 0;
    document.getElementById('hudRemaining').innerText = remSec > 0 ? (remSec + 's') : (state === 'STREAMING' ? 'Continuous' : '--');

    lastTorchActive = !!d.torch_active;
    const torchBtn = document.getElementById('torchBtn');
    const torchBtnText = document.getElementById('torchBtnText');
    if (lastTorchActive) { torchBtn.classList.add('btn-active'); torchBtnText.innerText = 'Torch OFF'; }
    else { torchBtn.classList.remove('btn-active'); torchBtnText.innerText = 'Torch ON'; }

    const ip = (d.ips && (d.ips.wifi || d.ips.best)) || d.ip || '--';
    document.getElementById('infoIp').innerText = ip;
    const ntfyTopic = d.ntfy_topic || '--';
    document.getElementById('infoNtfy').innerText = ntfyTopic;
    updateControllerLink(ip, ntfyTopic);

    lastIsRecording = !!d.is_recording;
    document.getElementById('hudRec').classList.toggle('hidden', !lastIsRecording);
    const recordBtn = document.getElementById('recordBtn');
    const recordBtnText = document.getElementById('recordBtnText');
    recordBtn.classList.toggle('btn-active', lastIsRecording);
    recordBtnText.innerText = lastIsRecording ? 'Recording...' : 'Record';

    if (typeof d.zoom_level === 'number' && !zoomSliderDragging) {
      document.getElementById('rngZoom').value = d.zoom_level;
      document.getElementById('zoomVal').innerText = d.zoom_level.toFixed(1) + 'x';
    }

    const banner = document.getElementById('streamBanner');
    const link = document.getElementById('streamLink');
    if (state === 'STREAMING' && d.stream_url) {
      banner.classList.remove('hidden');
      link.href = d.stream_url;
      link.innerText = d.stream_url;
    } else {
      banner.classList.add('hidden');
    }

    ensureViewportMatchesState(d);
  }

  function updateControllerLink(ip, ntfyTopic) {
    if (!ip || ip === '--') return;
    const suffix = '?ip=' + encodeURIComponent(ip);
    document.getElementById('infoControllerLink').innerText = suffix;
  }

  function copyControllerLink() {
    const suffix = document.getElementById('infoControllerLink').innerText;
    if (!suffix || suffix === '--') return;
    navigator.clipboard.writeText(suffix).then(() => {
      const text = document.getElementById('copyLinkBtnText');
      text.innerText = 'Copied';
      setTimeout(() => { text.innerText = 'Copy'; }, 1500);
    });
  }

  // ---- Video viewport: continuous MJPEG with snapshot-polling fallback ----
  function ensureViewportMatchesState(d) {
    const state = d.state || 'IDLE';
    const streaming = state === 'STREAMING';
    const audioOnly = d.mode === 'AUDIO_ONLY';
    const img = document.getElementById('liveSnapshot');
    const placeholder = document.getElementById('viewportPlaceholder');

    if (streaming && !audioOnly) {
      placeholder.classList.add('hidden');
      img.classList.remove('hidden');
      if (!mjpegActive) startMjpeg();
    } else {
      stopMjpeg();
      img.classList.add('hidden');
      placeholder.classList.remove('hidden');
      placeholder.innerText = streaming && audioOnly ? 'Audio-Only Mode' : 'Waiting for video stream...';
    }

    const audio = document.getElementById('liveAudio');
    if (streaming && !liveActive) {
      liveActive = true;
      audio.src = '/api/audio.wav?t=' + Date.now();
      audio.muted = audioMuted;
      audio.play().catch(() => {});
    } else if (!streaming && liveActive) {
      liveActive = false;
      audio.pause();
      audio.removeAttribute('src');
      audio.load();
    }
  }

  function startMjpeg() {
    mjpegActive = true;
    const img = document.getElementById('liveSnapshot');
    img.onerror = () => { if (mjpegActive) { mjpegActive = false; startSnapshotFallback(); } };
    img.src = '/api/mjpeg?t=' + Date.now();
  }

  function startSnapshotFallback() {
    const img = document.getElementById('liveSnapshot');
    function loop() {
      const probe = new Image();
      probe.onload = () => { img.src = probe.src; img.classList.remove('hidden'); document.getElementById('viewportPlaceholder').classList.add('hidden'); snapshotTimer = setTimeout(loop, 500); };
      probe.onerror = () => { snapshotTimer = setTimeout(loop, 500); };
      probe.src = '/api/snapshot.jpg?t=' + Date.now();
    }
    loop();
  }

  function stopMjpeg() {
    mjpegActive = false;
    if (snapshotTimer) { clearTimeout(snapshotTimer); snapshotTimer = null; }
    const img = document.getElementById('liveSnapshot');
    img.onerror = null;
    img.removeAttribute('src');
  }

  function saveSnapshot() {
    const img = document.getElementById('liveSnapshot');
    if (!img.src || img.classList.contains('hidden')) { logMsg('No frame to save yet', 'err'); return; }
    try {
      const canvas = document.createElement('canvas');
      canvas.width = img.naturalWidth || 1280;
      canvas.height = img.naturalHeight || 720;
      canvas.getContext('2d').drawImage(img, 0, 0, canvas.width, canvas.height);
      const a = document.createElement('a');
      a.href = canvas.toDataURL('image/jpeg', 0.92);
      a.download = 'snapshot_' + Date.now() + '.jpg';
      document.body.appendChild(a);
      a.click();
      a.remove();
      logMsg('Snapshot saved', 'ok');
    } catch (e) { logMsg('Snapshot save failed: ' + e.message, 'err'); }
  }

  function toggleFullscreen() {
    const box = document.getElementById('videoViewport');
    if (!document.fullscreenElement) box.requestFullscreen().catch(() => {});
    else document.exitFullscreen();
  }

  function toggleMute() {
    const audio = document.getElementById('liveAudio');
    audioMuted = !audio.muted;
    audio.muted = audioMuted;
  }

  function toggleTorch() {
    sendAction(lastTorchActive ? 'TORCH_OFF' : 'TORCH_ON');
  }

  async function sendAction(action) {
    const payload = {
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

    logMsg('Dispatching ' + action + '...', 'info');
    try {
      const res = await fetch('/api/control', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });
      const data = await res.json();
      if (res.ok && data.ok) {
        logMsg('Action ' + action + ' acknowledged (state: ' + data.state + ')', 'ok');
        updateUi(data);
      } else {
        logMsg('Action ' + action + ' declined: ' + (data.error || 'Server error'), 'err');
      }
    } catch (e) {
      logMsg('Network failure: ' + e.message, 'err');
    }
  }

  // ---- Zoom ----
  document.getElementById('rngZoom').addEventListener('pointerdown', () => { zoomSliderDragging = true; });
  document.getElementById('rngZoom').addEventListener('pointerup', () => { zoomSliderDragging = false; });

  function onZoomInput(val) {
    document.getElementById('zoomVal').innerText = parseFloat(val).toFixed(1) + 'x';
    if (zoomDebounceTimer) clearTimeout(zoomDebounceTimer);
    zoomDebounceTimer = setTimeout(() => sendZoom(parseFloat(val)), 150);
  }

  async function sendZoom(level) {
    try {
      await fetch('/api/control', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ action: 'SET_ZOOM', zoom_level: level })
      });
    } catch (e) {
      logMsg('Zoom command failed: ' + e.message, 'err');
    }
  }

  // ---- Recording ----
  function toggleRecording() {
    sendAction(lastIsRecording ? 'RECORD_STOP' : 'RECORD_START');
  }

  async function loadRecordings() {
    const box = document.getElementById('recordingsList');
    try {
      const res = await fetch('/api/recordings');
      if (!res.ok) { box.innerText = 'Failed to load (HTTP ' + res.status + ')'; return; }
      const data = await res.json();
      const files = data.files || [];
      if (files.length === 0) { box.innerText = 'No recordings yet.'; return; }
      box.innerHTML = '';
      files.slice().reverse().forEach((f) => {
        const row = document.createElement('div');
        row.className = 'rec-row';
        const sizeMb = (f.size_bytes / (1024 * 1024)).toFixed(1);
        const when = new Date(f.modified_ms).toLocaleString();
        const label = document.createElement('span');
        label.className = 'rec-name';
        label.innerText = f.name + ' (' + sizeMb + ' MB, ' + when + ')';
        const actions = document.createElement('span');
        actions.className = 'rec-actions';
        const dl = document.createElement('a');
        dl.innerText = 'Download';
        dl.href = '/api/recordings/' + encodeURIComponent(f.name);
        const del = document.createElement('a');
        del.innerText = 'Delete';
        del.className = 'danger';
        del.href = '#';
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
    try {
      await fetch('/api/recordings/' + encodeURIComponent(name), { method: 'DELETE' });
      logMsg('Deleted recording ' + name, 'ok');
      loadRecordings();
    } catch (e) {
      logMsg('Delete failed: ' + e.message, 'err');
    }
  }

  // ---- Push-to-Talk — fixed pointer-state handling (no stuck "Recording..."
  // on mouse-leave or an interrupted getUserMedia prompt) ----
  let talkCtx = null, talkStream = null, talkProcessor = null, talkSource = null;
  let talkChunks = [];
  let talkSampleRate = 16000;
  let talkActive = false;
  let talkStarting = false;
  let talkCancelRequested = false;

  function resetTalkUi() {
    document.getElementById('talkBtn').classList.remove('btn-active');
    document.getElementById('talkStatus').innerText = 'Idle';
  }

  async function startTalk() {
    if (talkActive || talkStarting) return;
    talkStarting = true;
    talkCancelRequested = false;
    document.getElementById('talkStatus').innerText = 'Requesting mic...';
    try {
      talkStream = await navigator.mediaDevices.getUserMedia({ audio: true });
      if (talkCancelRequested) {
        talkStream.getTracks().forEach((t) => t.stop());
        talkStream = null;
        talkStarting = false;
        resetTalkUi();
        return;
      }
      talkCtx = new (window.AudioContext || window.webkitAudioContext)();
      talkSampleRate = talkCtx.sampleRate;
      talkSource = talkCtx.createMediaStreamSource(talkStream);
      talkProcessor = talkCtx.createScriptProcessor(4096, 1, 1);
      talkChunks = [];
      talkProcessor.onaudioprocess = (e) => {
        if (talkActive) talkChunks.push(new Float32Array(e.inputBuffer.getChannelData(0)));
      };
      talkSource.connect(talkProcessor);
      talkProcessor.connect(talkCtx.destination);
      talkStarting = false;
      talkActive = true;
      document.getElementById('talkBtn').classList.add('btn-active');
      document.getElementById('talkStatus').innerText = 'Recording...';
      if (talkCancelRequested) await stopTalk();
    } catch (e) {
      talkStarting = false;
      talkActive = false;
      resetTalkUi();
      logMsg('Mic access failed: ' + e.message, 'err');
    }
  }

  async function stopTalk() {
    if (talkStarting) { talkCancelRequested = true; return; }
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
      resetTalkUi();
      document.getElementById('talkStatus').innerText = 'Sending...';
      const pcm16 = downsampleTo16kMono(merged, srcRate);
      const wavBlob = encodeWav(pcm16, 16000);
      const res = await fetch('/api/talk', {
        method: 'POST',
        headers: { 'Content-Type': 'audio/wav' },
        body: wavBlob
      });
      document.getElementById('talkStatus').innerText = 'Idle';
      if (res.ok) logMsg('Talk clip sent', 'ok'); else logMsg('Talk send failed: HTTP ' + res.status, 'err');
    } catch (e) {
      resetTalkUi();
      logMsg('Talk send error: ' + e.message, 'err');
    }
  }
  window.addEventListener('pointerup', stopTalk);
  window.addEventListener('pointercancel', stopTalk);

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

  // ---- URL query params for zero-touch launch (e.g. ?mode=BOTH&camera=BACK&res=720p&fps=30&bitrate=1500&autostart=1) ----
  function applyQueryParamsToForm() {
    const qp = new URLSearchParams(window.location.search);
    const fieldMap = { mode: 'selMode', camera: 'selCamera', res: 'selRes', fps: 'numFps', bitrate: 'numBitrate', vox: 'numVox', motion: 'numMotion' };
    Object.keys(fieldMap).forEach((key) => {
      if (qp.has(key)) {
        const el = document.getElementById(fieldMap[key]);
        if (el) el.value = qp.get(key);
      }
    });
  }

  async function autoStartIfIdle() {
    const qp = new URLSearchParams(window.location.search);
    if (qp.get('autostart') === '0') {
      logMsg('Autostart disabled via ?autostart=0', 'info');
      return;
    }
    try {
      const res = await fetch('/api/status');
      if (!res.ok) return;
      const data = await res.json();
      updateUi(data);
      if (data.state === 'IDLE') {
        logMsg('Auto-starting stream (zero-touch launch)...', 'info');
        sendAction('START');
      }
    } catch (e) {
      logMsg('Auto-start status check failed: ' + e.message, 'err');
    }
  }

  applyQueryParamsToForm();
  fetchStatus();
  setInterval(fetchStatus, 2000);
  loadRecordings();
  autoStartIfIdle();
</script>
</body>
</html>"""
}
