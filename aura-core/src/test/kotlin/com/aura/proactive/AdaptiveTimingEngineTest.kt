package com.aura.proactive

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.Calendar
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * This engine could not say yes.
 *
 * Scores were normalised by dividing by their own maximum, coerced to at least
 * 1, then clamped to `[0, 1]`. Since `"dismissed"` was the only interaction
 * anything ever wrote, every raw score was negative, every normalised score
 * clamped to zero, and the 0.4 gate was false for the life of the install.
 * These tests pin the two properties that fixes it: **a positive is
 * representable**, and **no evidence is neutral rather than hostile**.
 */
class AdaptiveTimingEngineTest {

    private val dao = mockk<ProactiveInteractionDao>(relaxed = true)
    private val engine = AdaptiveTimingEngine(dao)

    private fun at(hour: Int, action: String, daysAgo: Int = 0): ProactiveInteractionEntity {
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -daysAgo)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 30)
        }
        return ProactiveInteractionEntity(
            eventId = 1L, action = action, feedback = "", timestamp = cal.timeInMillis,
        )
    }

    private fun nowHour() = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    @Test
    fun `an empty corpus is neutral, not hostile`() = runTest {
        coEvery { dao.recent(any()) } returns emptyList()
        val scores = engine.hourlyEngagement()
        assertTrue(scores.all { it == AdaptiveTimingEngine.NEUTRAL }, "a blank install should be 0.5 everywhere")
        assertTrue(engine.isGoodTime(), "a fresh install refused to ever speak")
    }

    @Test
    fun `a positive is representable at all`() = runTest {
        // The property the old normalisation could not express.
        coEvery { dao.recent(any()) } returns listOf(at(nowHour(), "acted"), at(nowHour(), "tapped"))
        assertTrue(engine.receptivityNow() > AdaptiveTimingEngine.NEUTRAL)
        assertTrue(engine.isGoodTime())
    }

    @Test
    fun `an hour the user consistently dismisses becomes a bad time`() = runTest {
        val hour = nowHour()
        coEvery { dao.recent(any()) } returns List(8) { at(hour, "dismissed", daysAgo = it) }
        assertTrue(engine.receptivityNow() < AdaptiveTimingEngine.NEUTRAL)
        assertTrue(!engine.isGoodTime(), "eight dismissals in this hour should close it")
    }

    @Test
    fun `one dismissal does not close the door`() = runTest {
        // The old behaviour: any negative at all clamped the hour to zero.
        coEvery { dao.recent(any()) } returns listOf(at(nowHour(), "dismissed"))
        assertTrue(
            engine.isGoodTime(),
            "a single dismissal made the hour hostile at ${engine.receptivityNow()}",
        )
    }

    @Test
    fun `hours are bucketed in local time, not UTC`() = runTest {
        // The old code bucketed by hours-since-epoch mod 24 — the UTC hour —
        // while the lookup read Calendar.HOUR_OF_DAY. Four hours out here and
        // silently wrong anywhere off Greenwich.
        val target = (nowHour() + 5) % 24
        coEvery { dao.recent(any()) } returns List(4) { at(target, "acted", daysAgo = it) }

        val scores = engine.hourlyEngagement()
        assertTrue(scores[target] > AdaptiveTimingEngine.NEUTRAL, "the engaged hour did not land in its own bucket")
        assertEquals(
            AdaptiveTimingEngine.NEUTRAL,
            scores[nowHour()],
            "activity leaked into the current hour — the buckets are not local",
        )
    }

    @Test
    fun `every score stays inside zero and one`() = runTest {
        coEvery { dao.recent(any()) } returns
            List(50) { at(nowHour(), "acted") } + List(50) { at((nowHour() + 1) % 24, "dismissed") }
        assertTrue(engine.hourlyEngagement().all { it in 0f..1f })
    }

    @Test
    fun `a read failure degrades to neutral rather than to silence`() = runTest {
        coEvery { dao.recent(any()) } throws IllegalStateException("db gone")
        assertTrue(engine.isGoodTime(), "a database error should not permanently mute the assistant")
    }
}
