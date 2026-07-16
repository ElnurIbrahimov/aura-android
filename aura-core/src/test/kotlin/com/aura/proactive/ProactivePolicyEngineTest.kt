package com.aura.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactivePolicyEngineTest {
    private val engine = ProactivePolicyEngine()
    private val defaults = listOf(ProactivePolicyEngine.Policy("MorningBrief", 1.0f, 0L))

    @Test
    fun `negative feedback lowers weight`() {
        val summary = listOf(ActionCount("dismissed", 7), ActionCount("tapped", 3))
        val policies = engine.adaptFromSummary(summary, defaults)
        assertTrue(policies.first().weight < 1.0f)
    }

    @Test
    fun `positive feedback raises weight`() {
        val summary = listOf(ActionCount("tapped", 5), ActionCount("acted", 5))
        val policies = engine.adaptFromSummary(summary, defaults)
        assertEquals(1.3f, policies.first().weight, 0.01f)
    }
}
