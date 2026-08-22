package com.aura.creative.livingworld

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The clock is what makes WorkManager's 15-minute periodic floor a non-issue,
 * so the properties that matter are about *independence from when a worker ran*
 * rather than about arithmetic.
 */
class WorldClockTest {

    private val epoch = 1_000_000_000_000L
    private val hour = WorldClock.TICK_REAL_MS

    @Test
    fun `a tick is due once its hour has passed, whenever anyone asks`() {
        assertEquals(0L, WorldClock.dueTick(epoch, epoch, sessionTicksBurned = 0L))
        assertEquals(0L, WorldClock.dueTick(epoch, epoch + hour - 1, sessionTicksBurned = 0L))
        assertEquals(1L, WorldClock.dueTick(epoch, epoch + hour, sessionTicksBurned = 0L))
        // The same wall-clock instant yields the same tick no matter how late a
        // worker gets round to asking — this is the whole point.
        assertEquals(5L, WorldClock.dueTick(epoch, epoch + 5 * hour, sessionTicksBurned = 0L))
        assertEquals(5L, WorldClock.dueTick(epoch, epoch + 5 * hour + 59 * 60_000L, sessionTicksBurned = 0L))
    }

    @Test
    fun `a clock that has not reached its epoch yet reports no ticks rather than negative ones`() {
        assertEquals(0L, WorldClock.dueTick(epoch, epoch - 10 * hour, sessionTicksBurned = 0L))
        assertEquals(0L, WorldClock.behind(0L, epoch, epoch - 10 * hour, sessionTicksBurned = 0L))
    }

    @Test
    fun `behind never goes negative when the stored tick runs ahead of the clock`() {
        // Can happen after a manual catch-up, or if the device clock moves back.
        assertEquals(0L, WorldClock.behind(currentTick = 50L, worldEpochMs = epoch, now = epoch + 10 * hour, sessionTicksBurned = 0L))
    }

    @Test
    fun `a three day absence is seventy two ticks`() {
        assertEquals(72L, WorldClock.behind(0L, epoch, epoch + 72 * hour, sessionTicksBurned = 0L))
    }

    @Test
    fun `time until the next tick is zero while one is already owed`() {
        assertEquals(0L, WorldClock.msUntilNextTick(currentTick = 0L, worldEpochMs = epoch, now = epoch + hour, sessionTicksBurned = 0L))
        val remaining = WorldClock.msUntilNextTick(currentTick = 1L, worldEpochMs = epoch, now = epoch + hour + 600_000L, sessionTicksBurned = 0L)
        assertEquals(hour - 600_000L, remaining)
        assertTrue(remaining in 1..hour)
    }
}
