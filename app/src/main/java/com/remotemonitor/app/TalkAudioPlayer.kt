package com.remotemonitor.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlin.concurrent.thread

/** Plays a short PCM16 clip (raw or WAV-wrapped) through the speaker for two-way audio / push-to-talk. */
class TalkAudioPlayer {
    companion object { private const val TAG = "RM-Talk" }

    @Volatile private var playThread: Thread? = null

    fun play(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val (pcm, sampleRate) = extractPcm(bytes)
        if (pcm.isEmpty()) return
        playThread?.interrupt()
        playThread = thread(name = "RM-TalkPlayback") {
            var track: AudioTrack? = null
            try {
                val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val t = AudioTrack(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                    maxOf(minBuf, pcm.size),
                    AudioTrack.MODE_STATIC,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )
                track = t
                t.write(pcm, 0, pcm.size)
                t.play()
                val durationMs = (pcm.size.toLong() * 1000L) / (sampleRate.toLong() * 2L)
                Thread.sleep(durationMs + 200)
            } catch (e: Exception) {
                Log.w(TAG, "playback error: ${e.message}")
            } finally {
                try { track?.stop() } catch (_: Exception) {}
                try { track?.release() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Reads a RIFF/WAVE "fmt "+"data" header if present to recover sample rate, bit depth, and
     * the raw PCM region (otherwise assumes raw 16kHz mono PCM16). 8-bit unsigned PCM — used by
     * the compact ntfy-relay push-to-talk path — is upconverted to signed PCM16 here so the
     * AudioTrack below only ever has one encoding to deal with.
     */
    private fun extractPcm(bytes: ByteArray): Pair<ByteArray, Int> {
        if (bytes.size < 44 ||
            bytes[0] != 'R'.code.toByte() || bytes[1] != 'I'.code.toByte() ||
            bytes[2] != 'F'.code.toByte() || bytes[3] != 'F'.code.toByte()
        ) {
            return bytes to 16000
        }
        return try {
            var pos = 12
            var sampleRate = 16000
            var bitsPerSample = 16
            var dataOffset = -1
            var dataSize = 0
            while (pos + 8 <= bytes.size) {
                val chunkId = String(bytes, pos, 4, Charsets.US_ASCII)
                val chunkSize = (bytes[pos + 4].toInt() and 0xFF) or
                    ((bytes[pos + 5].toInt() and 0xFF) shl 8) or
                    ((bytes[pos + 6].toInt() and 0xFF) shl 16) or
                    ((bytes[pos + 7].toInt() and 0xFF) shl 24)
                val bodyStart = pos + 8
                when (chunkId) {
                    "fmt " -> {
                        if (bodyStart + 16 <= bytes.size) {
                            sampleRate = (bytes[bodyStart + 4].toInt() and 0xFF) or
                                ((bytes[bodyStart + 5].toInt() and 0xFF) shl 8) or
                                ((bytes[bodyStart + 6].toInt() and 0xFF) shl 16) or
                                ((bytes[bodyStart + 7].toInt() and 0xFF) shl 24)
                            bitsPerSample = (bytes[bodyStart + 14].toInt() and 0xFF) or
                                ((bytes[bodyStart + 15].toInt() and 0xFF) shl 8)
                        }
                    }
                    "data" -> {
                        dataOffset = bodyStart
                        dataSize = chunkSize
                    }
                }
                if (dataOffset >= 0) break
                pos = bodyStart + chunkSize + (chunkSize % 2)
            }
            if (dataOffset < 0 || dataOffset > bytes.size) return bytes to 16000
            val end = minOf(bytes.size, dataOffset + dataSize)
            val data = bytes.copyOfRange(dataOffset, end)
            if (bitsPerSample == 8) upconvert8to16(data) to sampleRate else data to sampleRate
        } catch (e: Exception) {
            bytes to 16000
        }
    }

    private fun upconvert8to16(pcm8: ByteArray): ByteArray {
        val out = ByteArray(pcm8.size * 2)
        for (i in pcm8.indices) {
            val signed16 = ((pcm8[i].toInt() and 0xFF) - 128) shl 8
            out[i * 2] = (signed16 and 0xFF).toByte()
            out[i * 2 + 1] = ((signed16 shr 8) and 0xFF).toByte()
        }
        return out
    }
}
