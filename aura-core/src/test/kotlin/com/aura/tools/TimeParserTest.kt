package com.aura.tools

import com.aura.tools.TimeParser
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the shared time parser used by SetReminderTool + TaskManagerTool.
 */
class TimeParserTest {

    @Test
    fun `parses HH mm for later today`() {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_YEAR, 1) // guaranteed future
        val hh = (cal.get(java.util.Calendar.HOUR_OF_DAY) + 1) % 24
        val result = TimeParser.parse(String.format("%02d:00", hh))
        assertNotNull(result)
        assertTrue(result > System.currentTimeMillis(), "result should be in the future")
    }

    @Test
    fun `parses ISO 8601 datetime`() {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.YEAR, 1)
        val iso = String.format(
            "%04d-%02d-%02dT15:30:00",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
        )
        val result = TimeParser.parse(iso)
        assertNotNull(result)
        // The year should match what we put in.
        val resultCal = java.util.Calendar.getInstance().apply { timeInMillis = result }
        assertEquals(cal.get(java.util.Calendar.YEAR), resultCal.get(java.util.Calendar.YEAR))
    }

    @Test
    fun `rejects garbage input`() {
        assertNull(TimeParser.parse(""))
        assertNull(TimeParser.parse("not a time"))
        assertNull(TimeParser.parse("25:99"))  // out-of-range hours/minutes
        assertNull(TimeParser.parse("12:60"))  // minutes must be 0..59
        assertNull(TimeParser.parse("12"))     // missing minutes
    }

    @Test
    fun `format returns non-empty string`() {
        val out = TimeParser.format(System.currentTimeMillis() + 60_000L)
        assertTrue(out.isNotBlank(), "format should produce a non-blank string")
    }

    /**
     * Three tools call [TimeParser.parse] — `set_reminder`, `manage_tasks` and
     * `calendar_write` — and `ToolExecutor` runs up to eight tool bodies at once
     * on a bounded dispatcher. "Remind me at 9, 10 and 11" is one model turn
     * emitting three parallel calls into a process-wide `object`.
     *
     * `SimpleDateFormat` is documented as not thread-safe: it keeps a mutable
     * `Calendar` across `parse`, so concurrent callers overwrite each other's
     * fields. The corruption is silent in the worst way. A torn parse either
     * returns the *wrong instant* — a reminder that fires on another day — or
     * throws, and the throw lands in `tryIso`'s `catch (_: Exception) { null }`,
     * falls through to `tryHhMm`, which cannot read ISO, and yields null. Null
     * reads to the caller as "the user typed a bad time", so the reminder is
     * dropped with a plausible error and nothing is logged.
     *
     * Note [TimeParser.format] one line below already builds its formatter per
     * call. The shared field is the inconsistency, not the fix.
     */
    @Test
    fun `parse is safe to call from several tools at once`() {
        val iso = "2027-03-09T15:30:00"
        val expected = java.util.Calendar.getInstance().apply {
            clear()
            set(2027, java.util.Calendar.MARCH, 9, 15, 30, 0)
        }.timeInMillis

        val threads = 8
        val perThread = 400
        val barrier = java.util.concurrent.CyclicBarrier(threads)
        val wrong = java.util.concurrent.atomic.AtomicInteger()
        val nulls = java.util.concurrent.atomic.AtomicInteger()

        val workers = (1..threads).map {
            Thread {
                barrier.await()
                repeat(perThread) {
                    when (TimeParser.parse(iso)) {
                        null -> nulls.incrementAndGet()
                        expected -> Unit
                        else -> wrong.incrementAndGet()
                    }
                }
            }.apply { start() }
        }
        workers.forEach { it.join() }

        assertEquals(0, nulls.get(), "a valid ISO time parsed to null under concurrency")
        assertEquals(0, wrong.get(), "a valid ISO time parsed to the wrong instant under concurrency")
    }
}
