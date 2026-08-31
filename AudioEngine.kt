package com.fawads.ai.ai

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Native PCM audio engine.
 *  - Microphone:  16000 Hz, mono, 16-bit PCM (voice recognition source).
 *  - Speaker:     24000 Hz, mono, 16-bit PCM (streamed model audio).
 *  - Exposes real-time RMS amplitude and queue-based playback with speak start/stop
 *    callbacks (used to drive the orb + waveform states).
 */
@SuppressLint("MissingPermission")
class AudioEngine {

    companion object {
        const val MIC_RATE = 16000
        const val SPEAKER_RATE = 24000
        const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_BYTES = 1024
        private const val TAG = "AudioEngine"
    }

    // ---- Callbacks ----
    var onAudioChunk: ((ByteArray) -> Unit)? = null
    var onSpeakingStarted: (() -> Unit)? = null
    var onSpeakingStopped: (() -> Unit)? = null
    var onAmplitudeChanged: ((Float) -> Unit)? = null

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    @Volatile private var recording = false
    @Volatile private var speaking = false
    @Volatile private var muted = false
    @Volatile private var runPlayback = true

    private val playbackQueue = ConcurrentLinkedQueue<ByteArray>()

    // -------------------------------- MIC --------------------------------
    fun startRecording() {
        if (recording) return
        try {
            val minBuf = AudioRecord.getMinBufferSize(MIC_RATE, CHANNEL_IN, ENCODING)
            val bufSize = maxOf(minBuf, CHUNK_BYTES * 4)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MIC_RATE, CHANNEL_IN, ENCODING, bufSize
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed")
                audioRecord?.release()
                audioRecord = null
                return
            }
            recording = true
            audioRecord?.startRecording()
            Thread({ recordLoop() }, "FawadsMic").start()
        } catch (e: SecurityException) {
            Log.e(TAG, "RECORD_AUDIO permission not granted", e)
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            audioRecord?.release()
            audioRecord = null
        }
    }

    private fun recordLoop() {
        val buffer = ByteArray(CHUNK_BYTES)
        while (recording) {
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
            if (read <= 0) continue
            if (!muted) onAudioChunk?.invoke(buffer.copyOf(read))

            // RMS amplitude -> normalized 0..1
            var sum = 0.0
            val frames = read / 2
            for (i in 0 until frames) {
                val lo = buffer[i * 2].toInt() and 0xFF
                val hi = buffer[i * 2 + 1].toInt() shl 8
                val sample = lo or hi
                sum += (sample * sample).toDouble()
            }
            val rms = if (frames > 0) (sqrt(sum / frames) / 32768.0).toFloat() else 0.0f
            onAmplitudeChanged?.invoke(rms.coerceIn(0f, 1f))
        }
    }

    fun stopRecording() {
        recording = false
        try { audioRecord?.stop() } catch (_: Exception) {}
        audioRecord?.release()
        audioRecord = null
    }

    // -------------------------------- SPEAKER --------------------------------
    fun startPlayback() {
        if (audioTrack == null) {
            val minBuf = AudioTrack.getMinBufferSize(SPEAKER_RATE, CHANNEL_OUT, ENCODING)
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(ENCODING)
                        .setSampleRate(SPEAKER_RATE)
                        .setChannelMask(CHANNEL_OUT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, 8192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audioTrack?.play()
        }
        if (!runPlayback) {
            runPlayback = true
            Thread({ playbackLoop() }, "FawadsPlayback").also { it.start() }
        }
    }

    fun queueAudio(base64Pcm: String) {
        try {
            val bytes = Base64.decode(base64Pcm, Base64.NO_WRAP)
            if (bytes.isNotEmpty()) playbackQueue.offer(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "audio decode failed", e)
        }
    }

    fun clearPlayback() {
        playbackQueue.clear()
        if (speaking) stopSpeaking()
    }

    /** Hard interrupt: drop everything currently playing (used on long-press mic). */
    fun interruptPlayback() {
        playbackQueue.clear()
        if (speaking) stopSpeaking()
        audioTrack?.pause()
        audioTrack?.flush()
        audioTrack?.play()
    }

    private fun stopSpeaking() {
        speaking = false
        onSpeakingStopped?.invoke()
    }

    private fun playbackLoop() {
        val track = audioTrack ?: return
        while (runPlayback) {
            val first = try {
                playbackQueue.poll(120, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                break
            }
            if (first == null) {
                if (speaking) {
                    speaking = false
                    track.pause()
                    track.flush()
                    track.play()
                    onSpeakingStopped?.invoke()
                }
                continue
            }
            if (!speaking) {
                speaking = true
                onSpeakingStarted?.invoke()
            }
            writeAudio(track, first)
            // Drain everything currently buffered
            while (true) {
                val b = playbackQueue.poll() ?: break
                writeAudio(track, b)
            }
        }
    }

    private fun writeAudio(track: AudioTrack, data: ByteArray) {
        var offset = 0
        while (offset < data.size && runPlayback) {
            val written = track.write(data, offset, data.size - offset)
            if (written <= 0) break
            offset += written
        }
    }

    // -------------------------------- CONTROL --------------------------------
    fun setMuted(m: Boolean) {
        muted = m
    }

    val isSpeaking: Boolean get() = speaking

    fun release() {
        recording = false
        runPlayback = false
        playbackQueue.clear()
        try { audioRecord?.stop() } catch (_: Exception) {}
        audioRecord?.release()
        audioRecord = null
        try { audioTrack?.stop() } catch (_: Exception) {}
        audioTrack?.release()
        audioTrack = null
    }
}
