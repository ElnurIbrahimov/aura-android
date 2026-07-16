package com.aura.evolution

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvolutionSafetyGuardTest {
    private val guard = EvolutionSafetyGuard()

    @Test
    fun `skill changes require approval and cannot auto-apply`() {
        assertFalse(guard.canAutoApply(EvolutionDomain.SKILL.name))
    }

    @Test
    fun `memory and proactive can auto-apply`() {
        assertTrue(guard.canAutoApply(EvolutionDomain.MEMORY.name))
        assertTrue(guard.canAutoApply(EvolutionDomain.PROACTIVE.name))
    }

    @Test
    fun `security domain is blocked`() {
        assertTrue(guard.isBlockedDomain("security"))
    }

    @Test
    fun `detects api key leak`() {
        assertTrue(guard.containsCredentialLeak("prefix sk-abcdefghijklmnopqrst prefix"))
    }

    @Test
    fun `clean text passes validation`() {
        val candidate = EvolutionCandidateEntity(
            id = "c1",
            domain = EvolutionDomain.MEMORY.name,
            action = "tag",
            targetId = "m1",
            argsJson = """{"tags":"work"}""",
            rationale = "add work tag",
            score = 0.9f,
        )
        assertTrue(guard.validateProposal(candidate).isSuccess)
    }
}