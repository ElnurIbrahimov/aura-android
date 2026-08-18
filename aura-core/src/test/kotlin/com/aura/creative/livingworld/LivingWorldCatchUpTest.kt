package com.aura.creative.livingworld

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** One tick behind is steady state; two is a slipped schedule worth a nudge. */
class LivingWorldCatchUpTest {

    private val epoch = 0L
    private fun nowAtTick(tick: Long): Long = tick * WorldClock.TICK_REAL_MS

    @Test
    fun `on time and one behind stay quiet`() {
        assertFalse(LivingWorldCatchUp.shouldEnqueue(currentTick = 10L, worldEpochMs = epoch, now = nowAtTick(10)))
        assertFalse(LivingWorldCatchUp.shouldEnqueue(currentTick = 9L, worldEpochMs = epoch, now = nowAtTick(10)))
    }

    @Test
    fun `two or more behind asks for the catch-up`() {
        assertTrue(LivingWorldCatchUp.shouldEnqueue(currentTick = 8L, worldEpochMs = epoch, now = nowAtTick(10)))
        assertTrue(LivingWorldCatchUp.shouldEnqueue(currentTick = 0L, worldEpochMs = epoch, now = nowAtTick(500)))
    }
}
