package com.aura.realtime

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AudioCapture] over `AudioRecord`.
 *
 * The source is `VOICE_COMMUNICATION`, and that single choice is what makes
 * barge-in survivable on speakerphone: it engages the platform's echo canceller
 * and noise suppressor. Without them the assistant's own voice comes back
 * through the microphone, trips the server's VAD, and it interrupts itself in a
 * loop — the number one failure mode of naive realtime implementations, and one
 * that looks like a model bug rather than an audio-routing one.
 */
@Singleton
class AndroidAudioCapture @Inject constructor() : AudioCapture {

    @Volatile
    private var record: AudioRecord? = null

    @Volatile
    private var running = false

    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    override val echoCancellationActive: Boolean
        get() = aec?.enabled == true

    @SuppressLint("MissingPermission")
    override fun start(): Flow<ByteArray> = flow {
        val minBuffer = AudioRecord.getMinBufferSize(
            AudioFormatSpec.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "AudioRecord reports no usable buffer size for ${AudioFormatSpec.SAMPLE_RATE_HZ}Hz")
            return@flow
        }
        // 4x the minimum: the minimum is the point at which underrun begins,
        // not a working size, and an underrun mid-sentence drops audio the
        // server then never hears.
        val bufferBytes = minBuffer * 4

        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            AudioFormatSpec.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord failed to initialise")
            r.release()
            return@flow
        }
        record = r
        enableEffects(r.audioSessionId)
        r.startRecording()
        running = true

        val framer = AudioFramer()
        val buffer = ByteArray(AudioFormatSpec.FRAME_BYTES)
        try {
            while (running && currentCoroutineContextIsActive()) {
                val read = r.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                // Explicit reframing: AudioRecord returns whatever is in its
                // buffer, which is not the frame size and varies run to run.
                framer.accept(buffer.copyOf(read)).forEach { emit(it) }
            }
            framer.flush()?.let { emit(it) }
        } finally {
            stop()
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun currentCoroutineContextIsActive(): Boolean =
        kotlinx.coroutines.currentCoroutineContext().isActive

    private fun enableEffects(sessionId: Int) {
        // Both are best-effort: availability varies by OEM, and a device
        // without them still works — it just echoes on speakerphone, which the
        // UI can warn about because `echoCancellationActive` says so.
        runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(sessionId)?.also { it.enabled = true }
            }
        }.onFailure { Log.w(TAG, "echo canceller unavailable: ${it.message}", it) }
        runCatching {
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(sessionId)?.also { it.enabled = true }
            }
        }.onFailure { Log.w(TAG, "noise suppressor unavailable: ${it.message}", it) }
    }

    override fun stop() {
        running = false
        runCatching { aec?.release() }.onFailure { Log.w(TAG, "aec release failed: ${it.message}", it) }
        runCatching { ns?.release() }.onFailure { Log.w(TAG, "ns release failed: ${it.message}", it) }
        aec = null
        ns = null
        val r = record
        record = null
        runCatching {
            if (r?.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop()
            r?.release()
        }.onFailure { Log.w(TAG, "AudioRecord release failed: ${it.message}", it) }
    }

    private companion object {
        const val TAG = "AuraAudioCapture"
    }
}

/**
 * [AudioSink] over `AudioTrack`.
 *
 * The playback-position accounting is the part that matters. `getPlaybackHeadPosition`
 * reports what the speaker has actually rendered, which lags what has been
 * written by the whole buffer depth — and that lag is precisely what
 * `RealtimeSession.interrupt` must report, or the model's idea of the
 * conversation diverges from the user's.
 */
@Singleton
class AndroidAudioSink @Inject constructor() : AudioSink {

    @Volatile
    private var track: AudioTrack? = null

    /** Frames rendered before the most recent flush, since the counter resets. */
    @Volatile
    private var framesBeforeFlush: Long = 0

    override fun start() {
        if (track != null) return
        val minBuffer = AudioTrack.getMinBufferSize(
            AudioFormatSpec.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(AudioFormatSpec.FRAME_BYTES * 4)

        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // VOICE_COMMUNICATION rather than MEDIA: it routes to the
                    // earpiece by default, engages the echo canceller's
                    // reference path, and ducks correctly against a call.
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AudioFormatSpec.SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
            .also { it.play() }
        framesBeforeFlush = 0
    }

    override fun write(pcm16: ByteArray) {
        val t = track ?: return
        runCatching { t.write(pcm16, 0, pcm16.size) }
            .onFailure { Log.w(TAG, "AudioTrack write failed: ${it.message}", it) }
    }

    override fun playedMs(): Long {
        val t = track ?: return AudioFormatSpec.bytesToMs(framesBeforeFlush * AudioFormatSpec.BYTES_PER_SAMPLE)
        // getPlaybackHeadPosition is FRAMES, not bytes, and it is a 32-bit
        // value that wraps — treated as unsigned so a long session does not
        // report a negative position.
        val frames = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        return AudioFormatSpec.bytesToMs((framesBeforeFlush + frames) * AudioFormatSpec.BYTES_PER_SAMPLE)
    }

    override fun flush() {
        val t = track ?: return
        // pause() then flush(), NOT stop(). stop() drains the buffer, so on
        // barge-in the assistant keeps talking over the user for the whole
        // buffer depth — the exact opposite of interrupting.
        runCatching {
            val frames = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            framesBeforeFlush += frames
            t.pause()
            t.flush()
            t.play()
        }.onFailure { Log.w(TAG, "AudioTrack flush failed: ${it.message}", it) }
    }

    override fun stop() {
        val t = track
        track = null
        runCatching {
            t?.pause()
            t?.flush()
            t?.stop()
            t?.release()
        }.onFailure { Log.w(TAG, "AudioTrack release failed: ${it.message}", it) }
    }

    private companion object {
        const val TAG = "AuraAudioSink"
    }
}

/**
 * Puts the device into communication mode for the duration of a call.
 *
 * Without it the platform treats realtime audio as media: it routes to the
 * loudspeaker, does not engage the echo-canceller reference path, and does not
 * duck for an incoming call.
 */
@Singleton
class VoiceAudioMode @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) {
    private var previousMode: Int = AudioManager.MODE_NORMAL
    private var focusRequest: Any? = null

    fun enter() {
        val am = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager ?: return
        previousMode = am.mode
        runCatching {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            focusRequest = requestFocus(am)
        }.onFailure { Log.w(TAG, "entering communication mode failed: ${it.message}", it) }
    }

    fun exit() {
        val am = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            (focusRequest as? android.media.AudioFocusRequest)?.let { am.abandonAudioFocusRequest(it) }
            am.mode = previousMode
        }.onFailure { Log.w(TAG, "leaving communication mode failed: ${it.message}", it) }
        focusRequest = null
    }

    private fun requestFocus(am: AudioManager): Any {
        val request = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        am.requestAudioFocus(request)
        return request
    }

    private companion object {
        const val TAG = "AuraVoiceAudioMode"
    }
}
