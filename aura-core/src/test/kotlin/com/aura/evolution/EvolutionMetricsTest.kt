package com.aura.evolution

import org.junit.Assert.assertEquals
import org.junit.Test

class EvolutionMetricsTest {
    @Test
    fun `score is zero when empty`() {
        assertEquals(0f, EvolutionMetrics().score())
    }

    @Test
    fun `score rewards applied and penalizes rolled back`() {
        val m = EvolutionMetrics()
        m.record("proposal.approved", 2)
        m.record("proposal.applied", 1)
        m.record("proposal.rolled_back", 1)
        assertEquals(0f, m.score()) // (1 - 1) / 4 = 0
    }

    @Test
    fun `snapshot returns sorted counters`() {
        val m = EvolutionMetrics()
        m.record("a")
        m.record("b", 3)
        assertEquals(mapOf("a" to 1L, "b" to 3L), m.snapshot())
    }
}
