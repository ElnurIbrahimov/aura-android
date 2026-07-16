package com.aura.evolution

import com.aura.skills.Skill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EvolutionSkillDetectorTest {
    private val detector = EvolutionSkillDetector()

    @Test
    fun `detects missing skill from repeated requests`() {
        val requests = List(4) { "Please summarize this article for me." }
        val skills = listOf(Skill(name = "Translate", description = "", body = "Translate text."))
        val candidate = detector.detectMissingSkillFromRequests(requests, skills)
        assertNotNull(candidate)
        assertEquals(EvolutionAction.CREATE_SKILL.name, candidate!!.action)
        assertEquals(EvolutionDomain.SKILL.name, candidate.domain)
    }

    @Test
    fun `returns null when skill already covers phrase`() {
        val requests = List(4) { "Please summarize this article for me." }
        val skills = listOf(Skill(name = "Summarize", description = "", body = "Summarize any text."))
        val candidate = detector.detectMissingSkillFromRequests(requests, skills)
        assertNull(candidate)
    }

    @Test
    fun `returns null when threshold not met`() {
        val requests = List(2) { "Please summarize this article for me." }
        val candidate = detector.detectMissingSkillFromRequests(requests, emptyList())
        assertNull(candidate)
    }
}
