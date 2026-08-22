package com.aura.realtime

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Framing is explicit because `AudioRecord` will not do it.
 *
 * It returns whatever happens to be in its buffer — not the frame size, and
 * varying run to run. Sending those straight to the server makes turn detection
 * erratic in a way that presents as the model being slow to respond rather than
 * as an audio bug, so the reframing has to be deliberate. Being deliberate is
 * also what makes it testable without a microphone.
 */
class AudioFramerTest {

    private val frame = 64

    @Test
    fun `exact multiples emit whole frames with nothing left over`() {
        val framer = AudioFramer(frame)
        val out = framer.accept(ByteArray(frame * 3))
        assertEquals(3, out.size)
        assertTrue(out.all { it.size == frame })
        assertNull(framer.flush(), "a clean multiple should leave no remainder")
    }

    @Test
    fun `a partial chunk is carried, not emitted`() {
        val framer = AudioFramer(frame)
        assertEquals(emptyList(), framer.accept(ByteArray(frame - 1)))
        // The carried byte plus the next chunk completes a frame.
        assertEquals(1, framer.accept(ByteArray(1)).size)
    }

    @Test
    fun `the carry spans multiple ragged chunks`() {
        // The realistic case: AudioRecord hands back 37, then 91, then 12.
        val framer = AudioFramer(frame)
        val emitted = mutableListOf<ByteArray>()
        listOf(37, 91, 12, 55, 3).forEach { emitted += framer.accept(ByteArray(it)) }

        val totalIn = 37 + 91 + 12 + 55 + 3
        assertEquals(totalIn / frame, emitted.size)
        assertTrue(emitted.all { it.size == frame })
    }

    @Test
    fun `no audio is lost across a ragged stream`() {
        // The invariant that matters: every byte the microphone produced is
        // either in a frame or in the flushed tail. Dropped audio is speech the
        // server never hears, and it presents as the model mishearing.
        val framer = AudioFramer(frame)
        var seen = 0
        var value: Byte = 0
        val sizes = listOf(10, 200, 3, 77, 128, 1)
        sizes.forEach { n ->
            val chunk = ByteArray(n) { (value++).toInt().toByte() }
            seen += framer.accept(chunk).sumOf { it.size }
        }
        val tail = framer.flush()
        val total = sizes.sum()
        // Frames plus the tail cover everything; the tail is padded, so it can
        // only be larger than the true remainder, never smaller.
        assertTrue(seen + (tail?.size ?: 0) >= total, "audio was lost: $seen + ${tail?.size} < $total")
        assertTrue(seen % frame == 0)
    }

    @Test
    fun `flush pads rather than dropping the tail`() {
        // The tail of a sentence is nearly always a partial frame. Dropping it
        // clips the last word of every utterance.
        val framer = AudioFramer(frame)
        framer.accept(ByteArray(10) { 7 })
        val tail = framer.flush()
        assertEquals(frame, tail!!.size, "the tail was not padded to a full frame")
        assertEquals(7, tail[0])
        assertEquals(0, tail[frame - 1], "padding should be silence")
    }

    @Test
    fun `flush twice yields nothing the second time`() {
        val framer = AudioFramer(frame)
        framer.accept(ByteArray(5))
        assertTrue(framer.flush() != null)
        assertNull(framer.flush(), "the tail was emitted twice")
    }

    @Test
    fun `reset discards the carry`() {
        val framer = AudioFramer(frame)
        framer.accept(ByteArray(frame - 1))
        framer.reset()
        assertNull(framer.flush())
    }

    @Test
    fun `empty input is a no-op`() {
        val framer = AudioFramer(frame)
        assertEquals(emptyList(), framer.accept(ByteArray(0)))
        assertNull(framer.flush())
    }

    // ---- format arithmetic ----------------------------------------------

    @Test
    fun `a frame is 20ms of PCM16 mono`() {
        // 20ms and not larger: frame size adds DIRECTLY to the server's
        // turn-detection latency, on every turn, and that latency is the thing
        // live voice exists to remove.
        assertEquals(20, AudioFormatSpec.FRAME_MS)
        assertEquals(24_000 * 2 * 20 / 1000, AudioFormatSpec.FRAME_BYTES)
        assertEquals(20, AudioFormatSpec.bytesToMs(AudioFormatSpec.FRAME_BYTES.toLong()))
    }
}
