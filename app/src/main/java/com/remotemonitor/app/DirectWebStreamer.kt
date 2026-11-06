package com.remotemonitor.app

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.Image
import android.media.MediaRecorder
import android.util.Log
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
    private val ntfyWatcher: NtfyWatcher?
) {
    companion object {
        private const val TAG = "RM-WebStreamer"
        private const val SAMPLE_RATE = 16000
        private const val JPEG_QUALITY = 70
        private const val MIN_CAPTURE_INTERVAL_MS = 150L
        private val STREAM_END = ByteArray(0)
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

    private val audioClients = CopyOnWriteArrayList<LinkedBlockingQueue<ByteArray>>()
    private val mjpegClients = CopyOnWriteArrayList<LinkedBlockingQueue<ByteArray>>()

    private val imageCallback = object : Camera2ApiManager.ImageCallback {
        override fun onImageAvailable(image: Image) {
            try {
                val now = System.currentTimeMillis()
                if (now - lastCaptureMs < MIN_CAPTURE_INTERVAL_MS) return
                lastCaptureMs = now
                val jpeg = when (image.format) {
                    ImageFormat.JPEG -> {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        bytes
                    }
                    ImageFormat.YUV_420_888 -> yuv420888ToJpeg(image, JPEG_QUALITY)
                    else -> {
                        Log.w(TAG, "Unsupported image format for snapshot: ${image.format}")
                        null
                    }
                }
                if (jpeg != null) {
                    latestJpeg = jpeg
                    latestJpegTimestampMs = now
                    offerToClients(mjpegClients, jpeg)
                }
            } catch (e: Exception) {
                Log.w(TAG, "image callback error: ${e.message}")
            } finally {
                try { image.close() } catch (_: Exception) {}
            }
        }
    }

    /** Begin serving snapshots/audio for the current streaming session. Call after the RootEncoder stream has started. */
    fun start(cam: Camera2Base?, payload: ControlPayload) {
        stop()
        val mode = payload.mode
        val isAudioOnly = mode == "AUDIO_ONLY"
        val isVideoOnly = mode == "VIDEO_ONLY"
        captureIntervalMs = payload.snapshotIntervalMs.coerceAtLeast(200L)

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

        if (!isVideoOnly) {
            startAudioCapture()
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
        pushThread?.interrupt()
        pushThread = null
        latestJpeg = null
        latestJpegTimestampMs = 0L
        closeAllClients(audioClients)
        closeAllClients(mjpegClients)
    }

    fun snapshotBytes(): ByteArray? = latestJpeg

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
                        offerToClients(audioClients, chunk.copyOf(n))
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

    // ---- push mode (callback_url / ntfy snapshot push) ----

    private fun schedulePush(callbackUrl: String, pushToNtfy: Boolean, intervalMs: Long) {
        pushRunning = true
        pushThread = thread(name = "RM-SnapshotPush") {
            while (pushRunning) {
                try {
                    val jpeg = latestJpeg
                    if (jpeg != null) {
                        if (callbackUrl.isNotBlank()) postCallback(callbackUrl, jpeg)
                        if (pushToNtfy) ntfyWatcher?.publishSnapshot(jpeg)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "snapshot push error: ${e.message}")
                }
                try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { break }
            }
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

    private fun yuv420888ToJpeg(image: Image, quality: Int): ByteArray? = try {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val ySize = yPlane.buffer.remaining()
        val nv21 = ByteArray(ySize + width * height / 2)
        yPlane.buffer.get(nv21, 0, ySize)

        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val uBytes = ByteArray(uBuf.remaining())
        val vBytes = ByteArray(vBuf.remaining())
        uBuf.get(uBytes)
        vBuf.get(vBytes)

        val chromaRowStride = vPlane.rowStride
        val chromaPixelStride = vPlane.pixelStride
        var offset = ySize
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                val vIndex = row * chromaRowStride + col * chromaPixelStride
                val uIndex = row * uPlane.rowStride + col * uPlane.pixelStride
                nv21[offset++] = if (vIndex < vBytes.size) vBytes[vIndex] else 0
                nv21[offset++] = if (uIndex < uBytes.size) uBytes[uIndex] else 0
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
