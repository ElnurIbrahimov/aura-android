package com.aura.creative.livingworld

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The keyed map exists because `LongformProgressBus` holds a single global slot
 * and disambiguates at the consumer. There that limitation is theoretical —
 * one manuscript is drafted at a time. Here it is routine: the periodic ticker
 * walks every running world in one slice, so with one slot each world would
 * overwrite the last and an open screen would watch a world that isn't its own.
 */
class WorldTickBusTest {

    private val bus = WorldTickBus()

    @Test
    fun `two worlds ticking at once do not overwrite each other`() = runTest {
        bus.begin("w1", currentTick = 0, targetTick = 10)
        bus.begin("w2", currentTick = 100, targetTick = 140)
        bus.progress("w1", 4)

        assertEquals(4L, bus.live("w1").value())
        assertEquals(100L, bus.live("w2").value(), "the second world's progress was clobbered by the first")
    }

    @Test
    fun `clearing one world leaves the other running`() = runTest {
        bus.begin("w1", 0, 10)
        bus.begin("w2", 0, 10)
        bus.clear("w1")

        bus.live("w1").test {
            assertNull(awaitItem())
            cancelAndConsumeRemainingEvents()
        }
        assertEquals(0L, bus.live("w2").value())
    }

    @Test
    fun `remaining counts down toward the target`() = runTest {
        bus.begin("w1", currentTick = 10, targetTick = 40)
        bus.live("w1").test {
            assertEquals(30L, awaitItem()?.remaining)
            cancelAndConsumeRemainingEvents()
        }
        bus.progress("w1", 35)
        bus.live("w1").test {
            assertEquals(5L, awaitItem()?.remaining)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `progress for a world that never began is ignored rather than inventing one`() = runTest {
        bus.progress("ghost", 5)
        bus.live("ghost").test {
            assertNull(awaitItem())
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    fun `the map cannot grow without bound when a slice dies before clearing`() = runTest {
        // A process killed mid-slice never calls clear. Without a cap, every
        // such death would leak an entry for the life of the process.
        for (index in 1..40) bus.begin("w$index", 0, 10)
        var tracked = 0
        for (index in 1..40) if (bus.live("w$index").value() != null) tracked++
        assertEquals(8, tracked, "the live map is not bounded")
    }

    private suspend fun kotlinx.coroutines.flow.Flow<LiveTick?>.value(): Long? {
        var result: Long? = null
        test {
            result = awaitItem()?.currentTick
            cancelAndConsumeRemainingEvents()
        }
        return result
    }
}
