package com.remotemonitor.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.Image
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import com.pedro.encoder.input.video.Camera2ApiManager
import com.pedro.library.base.Camera2Base
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Serves continuous raw audio (as a streaming WAV) and periodic camera snapshots (as JPEG /
 * MJPEG) directly from the phone's own HTTP server, so a browser/script can watch and listen
 * live without needing an external RTSP player. Also supports push delivery of snapshots to a
 * caller-supplied callback URL and/or ntfy, for phones sitting behind NAT.
 *
 * Video frames are pulled from the SAME active camera capture session used by the RootEncoder
 * stream via Camera2Base.addImageListener(), so there is no second camera session and no
 * contention with the RTSP/RTMP stream. Audio uses a dedicated AudioRecord instance in parallel
 * with RootEncoder's own internal audio capture for the encoded stream.
 */
class DirectWebStreamer(
    private val ctx: Context,
    private val httpClient: OkHttpClient,
    private val ntfyWatcher: NtfyWatcher?,
    private val mqttWatcher: MqttWatcher? = null
) {
    companion object {
        private const val TAG = "RM-WebStreamer"
        private const val SAMPLE_RATE = 16000
        private const val JPEG_QUALITY = 70
        private val STREAM_END = ByteArray(0)
        // ~5s of 16kHz mono 16-bit PCM, backing the /api/live_pack NAT-traversal endpoint.
        private const val PACK_AUDIO_MAX_BYTES = SAMPLE_RATE * 2 * 5
        // Safety cap for the ntfy push buffer in case pushes stall for a while — drop the
        // oldest half rather than grow unbounded.
        private const val NTFY_AUDIO_MAX_BYTES = SAMPLE_RATE * 2 * 10
        // Sentinel mode: both alert kinds share this floor so a noisy/busy scene can't spam
        // the ntfy/MQTT relay.
        private const val ALERT_THROTTLE_MS = 30_000L
        // How long a sound level must stay above vox_threshold_db before it counts as an event,
        // instead of firing on a single loud transient.
        private const val VOX_SUSTAIN_MS = 500L
        private const val MOTION_GRID_COLS = 32
        private const val MOTION_GRID_ROWS = 24
    }

    @Volatile var latestJpeg: ByteArray? = null
        private set
    @Volatile var latestJpegTimestampMs: Long = 0L
        private set

    private var camera: Camera2Base? = null
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    @Volatile private var audioRunning = false

    private var pushThread: Thread? = null
    @Volatile private var pushRunning = false

    @Volatile private var lastCaptureMs = 0L
    @Volatile private var captureIntervalMs = 1000L

    // ---- sentinel mode (VOX + motion) ----
    @Volatile private var voxThresholdDb = 0
    @Volatile private var motionSensitivity = 0
    @Volatile private var soundAboveSinceMs = 0L
    @Volatile private var lastSoundAlertMs = 0L
    @Volatile private var lastMotionAlertMs = 0L
    @Volatile private var previousMotionGrid: FloatArray? = null

    private val audioClients = CopyOnWriteArrayList<LinkedBlockingQueue<ByteArray>>()
    private val mjpegClients = CopyOnWriteArrayList<LinkedBlockingQueue<ByteArray>>()

    private val packLock = Object()
    private var packAudioBuffer = ByteArray(0)

    // Dedicated consume-on-read buffer for the ntfy relay push, separate from packAudioBuffer
    // (a rolling "last 5s" window used by the pull-based /api/live_pack endpoint). Pushing the
    // same rolling window out every cycle would resend already-heard audio as an echo; this one
    // is drained to empty on every read so each push only ever carries audio captured since the
    // previous push.
    @Volatile private var ntfyPushActive = false
    private val ntfyAudioLock = Object()
    private var ntfyAudioBuffer = ByteArrayOutputStream()

    private val imageCallback = object : Camera2ApiManager.ImageCallback {
        override fun onImageAvailable(image: Image) {
            try {
                val now = System.currentTimeMillis()
                // Respect the caller's configured snapshot interval instead of a fixed 150ms
                // floor — at the default 1000ms interval the old fixed value ran the (fairly
                // heavy) YUV->JPEG conversion ~7x more often than anything ever consumed, on
                // the Camera2 callback thread itself. Still cap it at 400ms so a very slow
                // configured interval doesn't starve the MJPEG stream of frames.
                val minInterval = minOf(captureIntervalMs, 400L)
                if (now - lastCaptureMs < minInterval) {
                    image.close()
                    return
                }
                lastCaptureMs = now
                when (image.format) {
                    ImageFormat.JPEG -> {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        image.close()
                        publishJpeg(bytes, now)
                    }
                    ImageFormat.YUV_420_888 -> {
                        // Copying the raw plane bytes is cheap; the actual YUV->JPEG
                        // compression is not. Do the copy here so image.close() can run
                        // immediately and free the Camera2 pipeline's buffer slot, then
                        // compress on a background thread instead of blocking the capture
                        // callback thread.
                        val snapshot = copyYuvPlanes(image)
                        image.close()
                        thread(name = "RM-JpegEncode") {
                            val jpeg = yuv420888ToJpeg(snapshot, JPEG_QUALITY)
                            if (jpeg != null) publishJpeg(jpeg, now)
                            if (motionSensitivity > 0) checkMotion(snapshot, jpeg)
                        }
                    }
                    else -> {
                        Log.w(TAG, "Unsupported image format for snapshot: ${image.format}")
                        image.close()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "image callback error: ${e.message}")
                try { image.close() } catch (_: Exception) {}
            }
        }
    }

    private fun publishJpeg(jpeg: ByteArray, atMs: Long) {
        latestJpeg = jpeg
        latestJpegTimestampMs = atMs
        offerToClients(mjpegClients, jpeg)
    }

    /**
     * Begin serving snapshots/audio for the current streaming session. Call after the RootEncoder
     * stream has started. [isDirectTarget] is true when there is no external RTSP/RTMP push (the
     * built-in server or no server at all) — only then does DirectWebStreamer open its own
     * AudioRecord unconditionally, since in push mode RootEncoder's own prepareAudio() already
     * owns the mic and a second AudioRecord would contend with it. In push mode, local/relay
     * audio still runs if explicitly requested via ntfyPushSnapshots or webAudioEnabled.
     */
    fun start(cam: Camera2Base?, payload: ControlPayload, isDirectTarget: Boolean = true) {
        stop()
        val mode = payload.mode
        val isAudioOnly = mode == "AUDIO_ONLY"
        val isVideoOnly = mode == "VIDEO_ONLY"
        captureIntervalMs = payload.snapshotIntervalMs.coerceAtLeast(200L)
        ntfyPushActive = payload.ntfyPushSnapshots
        voxThresholdDb = payload.voxThresholdDb
        motionSensitivity = payload.motionSensitivity
        soundAboveSinceMs = 0L
        previousMotionGrid = null
        synchronized(ntfyAudioLock) { ntfyAudioBuffer.reset() }

        if (!isAudioOnly && cam != null) {
            camera = cam
            try {
                val (w, h) = resolveVideoResolution(payload.videoResolution)
                cam.addImageListener(w, h, ImageFormat.YUV_420_888, 2, imageCallback)
                Log.i(TAG, "Attached snapshot image listener (${w}x$h)")
            } catch (e: Exception) {
                Log.w(TAG, "addImageListener failed: ${e.message}")
            }
        }

        val wantAudio = !isVideoOnly && (isDirectTarget || payload.ntfyPushSnapshots || payload.webAudioEnabled)
        if (wantAudio) {
            startAudioCapture()
        } else if (!isVideoOnly) {
            Log.i(TAG, "Skipping DirectWebStreamer AudioRecord: external push target owns the mic (webAudioEnabled/ntfyPushSnapshots not requested)")
        }

        if (payload.callbackUrl.isNotBlank() || payload.ntfyPushSnapshots) {
            schedulePush(payload.callbackUrl.trim(), payload.ntfyPushSnapshots, captureIntervalMs)
        }
    }

    /** Stop all capture, release the mic/image listener, and unblock any connected HTTP clients. */
    fun stop() {
        try { camera?.removeImageListener() } catch (_: Exception) {}
        camera = null
        stopAudioCapture()
        pushRunning = false
        ntfyPushActive = false
        pushThread?.interrupt()
        pushThread = null
        latestJpeg = null
        latestJpegTimestampMs = 0L
        synchronized(packLock) { packAudioBuffer = ByteArray(0) }
        synchronized(ntfyAudioLock) { ntfyAudioBuffer.reset() }
        closeAllClients(audioClients)
        closeAllClients(mjpegClients)
    }

    /** Drains and returns everything captured since the last call — never resends the same audio twice. */
    private fun consumeNtfyAudioBytes(): ByteArray = synchronized(ntfyAudioLock) {
        val bytes = ntfyAudioBuffer.toByteArray()
        ntfyAudioBuffer.reset()
        bytes
    }

    fun snapshotBytes(): ByteArray? = latestJpeg

    /**
     * Single-request JSON snapshot for callers that can't hold a long-lived connection (e.g. a
     * phone behind NAT being polled over a relay): the latest JPEG plus whatever PCM audio has
     * accumulated since the ring buffer last wrapped, both base64-encoded.
     */
    fun livePackJson(): JSONObject {
        val jpeg = latestJpeg
        val audioBytes = synchronized(packLock) { packAudioBuffer.copyOf() }
        return JSONObject().apply {
            put("timestamp_ms", System.currentTimeMillis())
            put("jpeg_timestamp_ms", latestJpegTimestampMs)
            put("jpeg_base64", if (jpeg != null) Base64.encodeToString(jpeg, Base64.NO_WRAP) else "")
            put("audio_wav_base64", if (audioBytes.isNotEmpty()) Base64.encodeToString(buildStandaloneWav(audioBytes), Base64.NO_WRAP) else "")
        }
    }

    private fun buildStandaloneWav(pcm: ByteArray): ByteArray = buildWav(pcm, SAMPLE_RATE, 16)

    private fun buildWav(pcm: ByteArray, sampleRate: Int, bitsPerSample: Int): ByteArray {
        val channels = 1
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcm.size

        val out = ByteArrayOutputStream(44 + dataSize)
        fun writeStr(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun writeIntLE(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF)
            out.write((v shr 24) and 0xFF)
        }
        fun writeShortLE(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }
        writeStr("RIFF"); writeIntLE(36 + dataSize); writeStr("WAVE")
        writeStr("fmt "); writeIntLE(16)
        writeShortLE(1) // PCM
        writeShortLE(channels)
        writeIntLE(sampleRate)
        writeIntLE(byteRate)
        writeShortLE(blockAlign)
        writeShortLE(bitsPerSample)
        writeStr("data"); writeIntLE(dataSize)
        out.write(pcm)
        return out.toByteArray()
    }

    /**
     * 16kHz/16-bit mono PCM -> 8kHz/8-bit mono PCM: drops every other sample and the low byte
     * of what's left. ~8KB/s instead of ~32KB/s, small enough to embed as base64 directly in an
     * ntfy JSON text message instead of needing a second HTTP request as a binary attachment.
     */
    private fun downsampleTo8kHz8Bit(pcm16: ByteArray): ByteArray {
        val sampleCount = pcm16.size / 2
        val outCount = sampleCount / 2
        if (outCount <= 0) return ByteArray(0)
        val out = ByteArray(outCount)
        var i = 0
        var o = 0
        while (o < outCount) {
            val lo = pcm16[i].toInt() and 0xFF
            val hi = pcm16[i + 1].toInt()
            val sample16 = (hi shl 8) or lo
            out[o] = ((sample16 shr 8) + 128).coerceIn(0, 255).toByte()
            o++
            i += 4 // consume 2 samples (4 bytes), keep 1 -> 16kHz to 8kHz
        }
        return out
    }

    /** Registers a new subscriber and returns a blocking InputStream: a WAV header followed by continuous PCM. */
    fun newAudioStream(): InputStream {
        val queue = LinkedBlockingQueue<ByteArray>(50)
        audioClients.add(queue)
        return WavStreamingInputStream(queue) { audioClients.remove(queue) }
    }

    /** Registers a new subscriber and returns a blocking multipart/x-mixed-replace MJPEG InputStream. */
    fun newMjpegStream(): InputStream {
        val queue = LinkedBlockingQueue<ByteArray>(20)
        mjpegClients.add(queue)
        return MjpegStreamingInputStream(queue) { mjpegClients.remove(queue) }
    }

    // ---- audio capture ----

    private fun startAudioCapture() {
        if (audioRunning) return
        try {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) {
                Log.w(TAG, "Invalid AudioRecord buffer size, skipping direct audio capture")
                return
            }
            @Suppress("MissingPermission")
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord failed to initialize (mic busy?)")
                rec.release()
                return
            }
            audioRecord = rec
            audioRunning = true
            rec.startRecording()
            audioThread = thread(name = "RM-AudioCapture") {
                val chunk = ByteArray(4096)
                while (audioRunning) {
                    val n = try { rec.read(chunk, 0, chunk.size) } catch (e: Exception) { -1 }
                    if (n > 0) {
                        val piece = chunk.copyOf(n)
                        offerToClients(audioClients, piece)
                        if (voxThresholdDb != 0) checkVox(piece)
                        synchronized(packLock) {
                            val combined = packAudioBuffer + piece
                            packAudioBuffer = if (combined.size > PACK_AUDIO_MAX_BYTES) {
                                combined.copyOfRange(combined.size - PACK_AUDIO_MAX_BYTES, combined.size)
                            } else combined
                        }
                        if (ntfyPushActive) {
                            synchronized(ntfyAudioLock) {
                                ntfyAudioBuffer.write(piece)
                                if (ntfyAudioBuffer.size() > NTFY_AUDIO_MAX_BYTES) {
                                    val trimmed = ntfyAudioBuffer.toByteArray()
                                        .copyOfRange(ntfyAudioBuffer.size() - NTFY_AUDIO_MAX_BYTES / 2, ntfyAudioBuffer.size())
                                    ntfyAudioBuffer.reset()
                                    ntfyAudioBuffer.write(trimmed)
                                }
                            }
                        }
                    } else if (n < 0) {
                        break
                    }
                }
            }
            Log.i(TAG, "Direct audio capture started ($SAMPLE_RATE Hz mono PCM16)")
        } catch (e: Exception) {
            Log.w(TAG, "startAudioCapture error: ${e.message}")
        }
    }

    private fun stopAudioCapture() {
        audioRunning = false
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { audioThread?.join(500) } catch (_: Exception) {}
        audioThread = null
    }

    private fun offerToClients(clients: CopyOnWriteArrayList<LinkedBlockingQueue<ByteArray>>, data: ByteArray) {
        for (q in clients) {
            if (q.remainingCapacity() == 0) q.poll()
            q.offer(data)
        }
    }

    private fun closeAllClients(clients: CopyOnWriteArrayList<LinkedBlockingQueue<ByteArray>>) {
        for (q in clients) q.offer(STREAM_END)
        clients.clear()
    }

    // ---- sentinel: sound (VOX) + motion detection ----

    /** RMS decibel level of a PCM16 chunk relative to full scale (0 dB = max amplitude, more negative = quieter). */
    private fun rmsDb(pcm16: ByteArray): Double {
        val sampleCount = pcm16.size / 2
        if (sampleCount <= 0) return -96.0
        var sumSquares = 0.0
        var i = 0
        while (i + 1 < pcm16.size) {
            val lo = pcm16[i].toInt() and 0xFF
            val hi = pcm16[i + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toDouble()
            sumSquares += sample * sample
            i += 2
        }
        val rms = Math.sqrt(sumSquares / sampleCount)
        if (rms <= 0.0) return -96.0
        return 20.0 * Math.log10(rms / 32768.0)
    }

    private fun checkVox(piece: ByteArray) {
        val db = rmsDb(piece)
        val now = System.currentTimeMillis()
        if (db > voxThresholdDb) {
            if (soundAboveSinceMs == 0L) soundAboveSinceMs = now
            if (now - soundAboveSinceMs >= VOX_SUSTAIN_MS && now - lastSoundAlertMs >= ALERT_THROTTLE_MS) {
                lastSoundAlertMs = now
                publishAlert("sound", JSONObject().apply { put("db", db) })
            }
        } else {
            soundAboveSinceMs = 0L
        }
    }

    /** Downsamples the Y (luma) plane to a MOTION_GRID_COLS x MOTION_GRID_ROWS block-average grid. */
    private fun computeMotionGrid(snap: YuvSnapshot): FloatArray {
        val grid = FloatArray(MOTION_GRID_COLS * MOTION_GRID_ROWS)
        val blockW = (snap.width / MOTION_GRID_COLS).coerceAtLeast(1)
        val blockH = (snap.height / MOTION_GRID_ROWS).coerceAtLeast(1)
        for (gy in 0 until MOTION_GRID_ROWS) {
            for (gx in 0 until MOTION_GRID_COLS) {
                var sum = 0L
                var count = 0
                val yStart = gy * blockH
                val xStart = gx * blockW
                val yEnd = minOf(yStart + blockH, snap.height)
                val xEnd = minOf(xStart + blockW, snap.width)
                var y = yStart
                while (y < yEnd) {
                    var x = xStart
                    val rowBase = y * snap.width
                    while (x < xEnd) {
                        val idx = rowBase + x
                        if (idx < snap.yBytes.size) {
                            sum += (snap.yBytes[idx].toInt() and 0xFF)
                            count++
                        }
                        x++
                    }
                    y++
                }
                grid[gy * MOTION_GRID_COLS + gx] = if (count > 0) sum.toFloat() / count else 0f
            }
        }
        return grid
    }

    /** Called off the camera callback thread (already running in the background JPEG-encode thread) for each YUV frame. */
    private fun checkMotion(snap: YuvSnapshot, jpegForAlert: ByteArray?) {
        val grid = computeMotionGrid(snap)
        val previous = previousMotionGrid
        previousMotionGrid = grid
        if (previous == null || previous.size != grid.size) return
        var totalDelta = 0.0
        for (i in grid.indices) totalDelta += Math.abs(grid[i] - previous[i])
        val avgDelta = totalDelta / grid.size
        // Higher sensitivity (1-100) means a smaller average pixel delta is enough to trigger.
        val requiredDelta = (101 - motionSensitivity).coerceIn(1, 100) * 0.3f
        val now = System.currentTimeMillis()
        if (avgDelta > requiredDelta && now - lastMotionAlertMs >= ALERT_THROTTLE_MS) {
            lastMotionAlertMs = now
            publishAlert("motion", JSONObject().apply {
                put("delta", avgDelta)
                if (jpegForAlert != null) {
                    val small = downscaleJpeg(jpegForAlert, 320, 240, 45) ?: jpegForAlert
                    put("jpeg_b64", Base64.encodeToString(small, Base64.NO_WRAP))
                }
            })
        }
    }

    private fun publishAlert(reason: String, extra: JSONObject) {
        try {
            val json = JSONObject().apply {
                put("type", "alert")
                put("reason", reason)
                put("timestamp_ms", System.currentTimeMillis())
                val keyIt = extra.keys()
                while (keyIt.hasNext()) {
                    val k = keyIt.next()
                    put(k, extra.get(k))
                }
            }.toString()
            ntfyWatcher?.publishStatus(json)
            mqttWatcher?.publishStatus(json)
            Log.i(TAG, "Sentinel alert: $reason")
        } catch (e: Exception) {
            Log.w(TAG, "publishAlert error: ${e.message}")
        }
    }

    // ---- push mode (callback_url / ntfy snapshot push) ----

    private fun schedulePush(callbackUrl: String, pushToNtfy: Boolean, intervalMs: Long) {
        pushRunning = true
        // The ntfy relay push is intentionally throttled to its own floor, independent of the
        // (possibly much faster) local snapshot interval: it's talking to the public ntfy.sh
        // broker over the internet, not polling localhost. A previous 2s floor still hit
        // ntfy.sh's HTTP 429 rate limit after ~20s of a session, so this is now 4s.
        val ntfyIntervalMs = maxOf(intervalMs, 4000L)
        pushThread = thread(name = "RM-SnapshotPush") {
            var lastNtfyPushMs = 0L
            while (pushRunning) {
                try {
                    val jpeg = latestJpeg
                    if (jpeg != null && callbackUrl.isNotBlank()) postCallback(callbackUrl, jpeg)
                    val now = System.currentTimeMillis()
                    if (pushToNtfy && now - lastNtfyPushMs >= ntfyIntervalMs) {
                        lastNtfyPushMs = now
                        // Deliberately not gated on jpeg != null: AUDIO_ONLY mode never has a
                        // snapshot, but still needs its status/audio pushed every cycle.
                        pushLivePackToNtfy(jpeg)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "snapshot push error: ${e.message}")
                }
                try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { break }
            }
        }
    }

    /**
     * Sends a compact live update over the ntfy relay as a SINGLE HTTP POST: status fields,
     * an optional small downscaled JPEG, and an optional short 8kHz/8-bit WAV audio clip
     * (only the PCM captured since the previous push — never resends the same audio), all
     * embedded as base64 directly in the JSON message body. Two separate requests (a JSON
     * POST plus a binary PUT attachment) used to trip ntfy.sh's attachment rate limit
     * (HTTP 429) within ~20s of a session; one combined text message does not.
     */
    private fun pushLivePackToNtfy(fullJpeg: ByteArray?) {
        try {
            val svc = ctx as? MonitorService
            val batteryPercent = svc?.statusJson()?.optInt("battery_percent", -1) ?: -1
            val json = JSONObject().apply {
                put("type", "live_pack")
                put("state", MonitorBus.state.name)
                put("battery_percent", batteryPercent)
            }
            if (fullJpeg != null) {
                val smallJpeg = downscaleJpeg(fullJpeg, 320, 240, 45) ?: fullJpeg
                json.put("jpeg_b64", Base64.encodeToString(smallJpeg, Base64.NO_WRAP))
            }

            val newPcm = consumeNtfyAudioBytes()
            if (newPcm.isNotEmpty()) {
                val compactPcm = downsampleTo8kHz8Bit(newPcm)
                val wavB64 = Base64.encodeToString(buildWav(compactPcm, 8000, 8), Base64.NO_WRAP)
                json.put("audio_wav_b64", wavB64)
            }

            var body = json.toString()
            if (body.length > 28_000) {
                Log.w(TAG, "live_pack JSON too large (${body.length} chars), dropping audio for this push")
                json.remove("audio_wav_b64")
                body = json.toString()
            }
            ntfyWatcher?.publishStatus(body)
        } catch (e: Exception) {
            Log.w(TAG, "pushLivePackToNtfy error: ${e.message}")
        }
    }

    private fun postCallback(url: String, jpeg: ByteArray) {
        try {
            val target = if (url.endsWith("/snapshot")) url else url.trimEnd('/') + "/snapshot"
            val body = jpeg.toRequestBody("image/jpeg".toMediaType())
            val req = Request.Builder()
                .url(target)
                .post(body)
                .header("Authorization", "Bearer " + AppConfig.token(ctx))
                .build()
            httpClient.newCall(req).execute().close()
        } catch (e: Exception) {
            Log.w(TAG, "callback push to $url failed: ${e.message}")
        }
    }

    // ---- YUV_420_888 -> JPEG ----

    /** Plain-data copy of an Image's YUV planes, safe to hand to a background thread after the Image itself has been closed. */
    private class YuvSnapshot(
        val width: Int,
        val height: Int,
        val yBytes: ByteArray,
        val uBytes: ByteArray,
        val vBytes: ByteArray,
        val uRowStride: Int,
        val uPixelStride: Int,
        val vRowStride: Int,
        val vPixelStride: Int
    )

    private fun copyYuvPlanes(image: Image): YuvSnapshot {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val yBytes = ByteArray(yBuf.remaining())
        yBuf.get(yBytes)
        val uBuf = uPlane.buffer
        val uBytes = ByteArray(uBuf.remaining())
        uBuf.get(uBytes)
        val vBuf = vPlane.buffer
        val vBytes = ByteArray(vBuf.remaining())
        vBuf.get(vBytes)
        return YuvSnapshot(
            image.width, image.height, yBytes, uBytes, vBytes,
            uPlane.rowStride, uPlane.pixelStride, vPlane.rowStride, vPlane.pixelStride
        )
    }

    private fun yuv420888ToJpeg(snap: YuvSnapshot, quality: Int): ByteArray? = try {
        val width = snap.width
        val height = snap.height
        val ySize = snap.yBytes.size
        val nv21 = ByteArray(ySize + width * height / 2)
        System.arraycopy(snap.yBytes, 0, nv21, 0, ySize)

        val chromaRowStride = snap.vRowStride
        val chromaPixelStride = snap.vPixelStride
        var offset = ySize
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                val vIndex = row * chromaRowStride + col * chromaPixelStride
                val uIndex = row * snap.uRowStride + col * snap.uPixelStride
                nv21[offset++] = if (vIndex < snap.vBytes.size) snap.vBytes[vIndex] else 0
                nv21[offset++] = if (uIndex < snap.uBytes.size) snap.uBytes[uIndex] else 0
            }
        }

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), quality, out)
        out.toByteArray()
    } catch (e: Exception) {
        Log.w(TAG, "yuv420888ToJpeg error: ${e.message}")
        null
    }

    /** Downscales an existing JPEG to at most maxW x maxH at the given quality, for the compact ntfy relay live-pack push. */
    private fun downscaleJpeg(src: ByteArray, maxW: Int, maxH: Int, quality: Int): ByteArray? = try {
        val bmp = BitmapFactory.decodeByteArray(src, 0, src.size) ?: return null
        val scale = minOf(maxW.toFloat() / bmp.width, maxH.toFloat() / bmp.height, 1f)
        val outBmp = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * scale).toInt().coerceAtLeast(1),
                (bmp.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else bmp
        val out = ByteArrayOutputStream()
        outBmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (outBmp !== bmp) outBmp.recycle()
        bmp.recycle()
        out.toByteArray()
    } catch (e: Exception) {
        Log.w(TAG, "downscaleJpeg error: ${e.message}")
        null
    }

    // ---- streaming InputStreams ----

    private class WavStreamingInputStream(
        private val queue: LinkedBlockingQueue<ByteArray>,
        private val onClose: () -> Unit
    ) : InputStream() {
        private var headerBuf: ByteArray? = buildWavHeader()
        private var headerPos = 0
        private var current: ByteArray? = null
        private var pos = 0
        @Volatile private var closed = false

        override fun read(): Int {
            val b = ByteArray(1)
            val n = read(b, 0, 1)
            return if (n <= 0) -1 else (b[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) return -1
            headerBuf?.let { h ->
                val n = minOf(len, h.size - headerPos)
                System.arraycopy(h, headerPos, b, off, n)
                headerPos += n
                if (headerPos >= h.size) headerBuf = null
                return n
            }
            if (current == null || pos >= current!!.size) {
                var next: ByteArray? = null
                while (!closed && next == null) {
                    next = queue.poll(2, TimeUnit.SECONDS)
                }
                if (closed) return -1
                if (next === STREAM_END) {
                    closed = true
                    return -1
                }
                current = next
                pos = 0
            }
            val c = current!!
            val n = minOf(len, c.size - pos)
            System.arraycopy(c, pos, b, off, n)
            pos += n
            return n
        }

        override fun close() {
            closed = true
            onClose()
        }

        private fun buildWavHeader(): ByteArray {
            // Streaming WAV: declare an (effectively) unbounded data size since the real length
            // is unknown up front; browsers/ffplay/mpv happily play a WAV with an oversized
            // declared size as long as the bytes keep arriving.
            val sampleRate = SAMPLE_RATE
            val channels = 1
            val bitsPerSample = 16
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8
            val riffSize = 0x7FFFFFFF
            val dataSize = 0x7FFFFFFF - 44

            val h = ByteArrayOutputStream(44)
            fun writeStr(s: String) = h.write(s.toByteArray(Charsets.US_ASCII))
            fun writeIntLE(v: Int) {
                h.write(v and 0xFF)
                h.write((v shr 8) and 0xFF)
                h.write((v shr 16) and 0xFF)
                h.write((v shr 24) and 0xFF)
            }
            fun writeShortLE(v: Int) {
                h.write(v and 0xFF)
                h.write((v shr 8) and 0xFF)
            }
            writeStr("RIFF"); writeIntLE(riffSize); writeStr("WAVE")
            writeStr("fmt "); writeIntLE(16)
            writeShortLE(1) // PCM
            writeShortLE(channels)
            writeIntLE(sampleRate)
            writeIntLE(byteRate)
            writeShortLE(blockAlign)
            writeShortLE(bitsPerSample)
            writeStr("data"); writeIntLE(dataSize)
            return h.toByteArray()
        }
    }

    private class MjpegStreamingInputStream(
        private val queue: LinkedBlockingQueue<ByteArray>,
        private val onClose: () -> Unit
    ) : InputStream() {
        private var current: ByteArray? = null
        private var pos = 0
        @Volatile private var closed = false

        private fun buildFramePacket(jpeg: ByteArray): ByteArray {
            val header = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n"
                .toByteArray(Charsets.US_ASCII)
            val footer = "\r\n".toByteArray(Charsets.US_ASCII)
            val out = ByteArray(header.size + jpeg.size + footer.size)
            System.arraycopy(header, 0, out, 0, header.size)
            System.arraycopy(jpeg, 0, out, header.size, jpeg.size)
            System.arraycopy(footer, 0, out, header.size + jpeg.size, footer.size)
            return out
        }

        override fun read(): Int {
            val b = ByteArray(1)
            val n = read(b, 0, 1)
            return if (n <= 0) -1 else (b[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) return -1
            if (current == null || pos >= current!!.size) {
                var next: ByteArray? = null
                while (!closed && next == null) {
                    next = queue.poll(2, TimeUnit.SECONDS)
                }
                if (closed) return -1
                if (next === STREAM_END) {
                    closed = true
                    return -1
                }
                current = buildFramePacket(next!!)
                pos = 0
            }
            val c = current!!
            val n = minOf(len, c.size - pos)
            System.arraycopy(c, pos, b, off, n)
            pos += n
            return n
        }

        override fun close() {
            closed = true
            onClose()
        }
    }
}
