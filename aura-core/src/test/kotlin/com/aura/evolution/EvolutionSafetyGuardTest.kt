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
        assertTrue(guard.containsCredentialLeak("prefix sk-abc...qrst prefix"))
    }

    @Test
    fun `detects anthropic key leak`() {
        assertTrue(guard.containsCredentialLeak("sk-ant-api03-1234567890abcdefGHIJ"))
    }

    @Test
    fun `detects gemini key leak`() {
        assertTrue(guard.containsCredentialLeak("AIzaSyA1234567890abcdefghijklmnopqrstuvwx"))
    }

    @Test
    fun `detects groq key leak`() {
        assertTrue(guard.containsCredentialLeak("gsk_1234567890abcdefghijklmnopqrstuv"))
    }

    @Test
    fun `detects openrouter key leak`() {
        assertTrue(guard.containsCredentialLeak("sk-or-v1-1234567890abcdefGHIJKLMN"))
    }

    @Test
    fun `detects tavily key leak`() {
        assertTrue(guard.containsCredentialLeak("tvly-1234567890abcdefghij"))
    }

    @Test
    fun `detects bearer token leak`() {
        assertTrue(guard.containsCredentialLeak("Bearer eyJhbGciOiJSUzI1NiIsInR5cCI"))
    }

    @Test
    fun `detects brave key leak`() {
        assertTrue(guard.containsCredentialLeak("BSA1234567890abcdefghijklmnopqrstuvwx"))
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