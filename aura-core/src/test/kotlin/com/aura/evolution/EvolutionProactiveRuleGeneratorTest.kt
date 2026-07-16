package com.aura.evolution

import com.aura.proactive.ActionCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvolutionProactiveRuleGeneratorTest {
    private val gen = EvolutionProactiveRuleGenerator()

    @Test
    fun `high dismiss rate generates suppress candidate`() {
        val summary = listOf(ActionCount("dismissed", 7), ActionCount("tapped", 3))
        val c = gen.generate(summary, emptyList())
        assertEquals(1, c.size)
        assertEquals(EvolutionAction.NEW_PROACTIVE_RULE.name, c.first().action)
    }

    @Test
    fun `high acted rate generates prioritize candidate`() {
        val summary = listOf(ActionCount("acted", 5), ActionCount("tapped", 5))
        val c = gen.generate(summary, emptyList())
        assertEquals(1, c.size)
        assertEquals(EvolutionAction.NEW_PROACTIVE_RULE.name, c.first().action)
    }
}
