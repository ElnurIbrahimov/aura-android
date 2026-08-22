package com.aura.realtime

import kotlinx.coroutines.flow.Flow

/**
 * Microphone input as a stream of PCM16 frames.
 *
 * An interface so the session logic — framing, barge-in, budget — is testable
 * with a fake, on CI, with no device. `AudioRecord` cannot be, and the parts
 * that actually contain decisions should not have to be.
 */
interface AudioCapture {
    /**
     * Start capturing and emit 20ms frames.
     *
     * **20ms, not larger.** Frame size adds directly to the server's
     * turn-detection latency: at 100ms frames the model waits an extra tenth of
     * a second to notice the user stopped talking, on every single turn, and
     * that is the thing live voice exists to remove.
     */
    fun start(): Flow<ByteArray>

    fun stop()

    /** Whether the platform echo canceller is active. See [AudioSink]. */
    val echoCancellationActive: Boolean
}

/**
 * Speaker output, and — critically — how much of it was actually heard.
 */
interface AudioSink {
    fun start()

    /** Queue PCM16 for playback. */
    fun write(pcm16: ByteArray)

    /**
     * Milliseconds of audio the SPEAKER has actually played.
     *
     * Not how much was written. The two diverge by the whole buffer depth, and
     * that difference is exactly what `RealtimeSession.interrupt` needs: telling
     * the server "you got to 4000ms" when the user only heard 2500ms leaves the
     * model believing it said a sentence and a half that never reached anyone.
     */
    fun playedMs(): Long

    /**
     * Stop immediately and discard what is queued.
     *
     * Discard, not drain. `AudioTrack.stop()` plays out the buffer, which on
     * barge-in means the assistant keeps talking over the user for as long as
     * the buffer is deep — the opposite of interrupting.
     */
    fun flush()

    fun stop()
}

/**
 * Audio constants, in one place because several of them are load-bearing and
 * would otherwise be scattered as literals.
 */
object AudioFormatSpec {
    /**
     * OpenAI Realtime speaks PCM16 mono at 24kHz. Not negotiable per-session,
     * so resampling belongs in the capture implementation rather than here.
     */
    const val SAMPLE_RATE_HZ = 24_000
    const val BYTES_PER_SAMPLE = 2
    const val CHANNELS = 1

    /** See [AudioCapture.start] for why this is 20 and not larger. */
    const val FRAME_MS = 20

    val FRAME_BYTES = SAMPLE_RATE_HZ * BYTES_PER_SAMPLE * CHANNELS * FRAME_MS / 1000

    /** Milliseconds represented by [bytes] of PCM16 mono at [SAMPLE_RATE_HZ]. */
    fun bytesToMs(bytes: Long): Long = bytes * 1000 / (SAMPLE_RATE_HZ.toLong() * BYTES_PER_SAMPLE * CHANNELS)
}

/**
 * Splits a byte stream into fixed-size frames, carrying the remainder.
 *
 * `AudioRecord` returns whatever happens to be in its buffer, which is not the
 * frame size and varies run to run. Sending those straight to the server makes
 * turn detection erratic in a way that looks like the model being slow to
 * respond, so the framing has to be explicit — and being explicit means it can
 * be tested without a microphone.
 */
class AudioFramer(private val frameBytes: Int = AudioFormatSpec.FRAME_BYTES) {
    private var carry = ByteArray(0)

    /** Complete frames available after appending [chunk]. */
    fun accept(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty() && carry.isEmpty()) return emptyList()
        val combined = carry + chunk
        val whole = combined.size / frameBytes
        if (whole == 0) {
            carry = combined
            return emptyList()
        }
        val out = ArrayList<ByteArray>(whole)
        for (i in 0 until whole) {
            out += combined.copyOfRange(i * frameBytes, (i + 1) * frameBytes)
        }
        carry = combined.copyOfRange(whole * frameBytes, combined.size)
        return out
    }

    /**
     * The trailing partial frame, zero-padded, or null.
     *
     * Padded rather than dropped: the tail of a sentence is usually a partial
     * frame, and dropping it clips the last word of every utterance.
     */
    fun flush(): ByteArray? {
        if (carry.isEmpty()) return null
        val padded = carry.copyOf(frameBytes)
        carry = ByteArray(0)
        return padded
    }

    fun reset() {
        carry = ByteArray(0)
    }
}
