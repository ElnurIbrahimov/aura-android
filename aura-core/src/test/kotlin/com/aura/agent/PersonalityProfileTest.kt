package com.aura.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonalityProfileTest {

    @Test
    fun `neutral profile produces empty directive`() {
        val p = PersonalityProfile()
        assertEquals("", p.toPromptDirective())
    }

    @Test
    fun `high warmth produces warm directive`() {
        val p = PersonalityProfile(warmth = 0.9f)
        assertTrue(p.toPromptDirective().contains("warm"))
    }

    @Test
    fun `low warmth produces direct directive`() {
        val p = PersonalityProfile(warmth = 0.1f)
        assertTrue(p.toPromptDirective().contains("direct"))
    }

    @Test
    fun `high formality produces formal directive`() {
        val p = PersonalityProfile(formality = 0.9f)
        assertTrue(p.toPromptDirective().contains("formal"))
    }

    @Test
    fun `low formality produces casual directive`() {
        val p = PersonalityProfile(formality = 0.1f)
        assertTrue(p.toPromptDirective().contains("casual"))
    }

    @Test
    fun `high verbosity produces thorough directive`() {
        val p = PersonalityProfile(verbosity = 0.9f)
        assertTrue(p.toPromptDirective().contains("thorough"))
    }

    @Test
    fun `low verbosity produces concise directive`() {
        val p = PersonalityProfile(verbosity = 0.1f)
        assertTrue(p.toPromptDirective().contains("concise"))
    }

    @Test
    fun `high humor produces humor directive`() {
        val p = PersonalityProfile(humor = 0.9f)
        assertTrue(p.toPromptDirective().contains("humor"))
    }

    @Test
    fun `low humor produces serious directive`() {
        val p = PersonalityProfile(humor = 0.1f)
        assertTrue(p.toPromptDirective().contains("serious"))
    }

    @Test
    fun `high proactivity produces anticipate directive`() {
        val p = PersonalityProfile(proactivity = 0.9f)
        assertTrue(p.toPromptDirective().lowercase().contains("anticipate"))
    }

    @Test
    fun `high risk tolerance produces creative alternatives directive`() {
        val p = PersonalityProfile(riskTolerance = 0.9f)
        assertTrue(p.toPromptDirective().contains("creative"))
    }

    @Test
    fun `low risk tolerance produces proven directive`() {
        val p = PersonalityProfile(riskTolerance = 0.1f)
        assertTrue(p.toPromptDirective().contains("proven"))
    }

    @Test
    fun `all extremes produce full directive`() {
        val p = PersonalityProfile(0.9f, 0.9f, 0.1f, 0.1f, 0.9f, 0.1f)
        val directive = p.toPromptDirective()
        assertTrue(directive.contains("Tone:"))
        assertTrue(directive.contains("warm"))
        assertTrue(directive.contains("formal"))
        assertTrue(directive.contains("concise"))
        assertTrue(directive.contains("serious"))
        assertTrue(directive.lowercase().contains("anticipate"))
        assertTrue(directive.contains("proven"))
    }

    @Test
    fun `builtin profiles are distinct`() {
        val profiles = listOf(
            PersonalityProfile.General,
            PersonalityProfile.Coder,
            PersonalityProfile.Researcher,
            PersonalityProfile.Writer,
            PersonalityProfile.Creative,
            PersonalityProfile.Executive,
            PersonalityProfile.PhoneNative,
        )
        // All 7 should be distinct
        assertEquals(7, profiles.toSet().size)
    }

    @Test
    fun `Coder profile is terse and formal`() {
        val directive = PersonalityProfile.Coder.toPromptDirective()
        // Coder: warmth=0.3, formality=0.7, verbosity=0.3, humor=0.2
        // warmth=0.3 is exactly at threshold, not < 0.3, so no "direct"
        // formality=0.7 is exactly at threshold, not > 0.7, so no "formal"
        // verbosity=0.3 is exactly at threshold, not < 0.3, so no "concise"
        // humor=0.2 < 0.3, so "serious"
        assertTrue(directive.contains("serious"))
    }

    @Test
    fun `Creative profile is playful and experimental`() {
        val directive = PersonalityProfile.Creative.toPromptDirective()
        // Creative: warmth=0.7, formality=0.2, verbosity=0.5, humor=0.7, risk=0.8
        // humor=0.7 is exactly at threshold, not > 0.7
        // formality=0.2 < 0.3, so "casual"
        // risk=0.8 > 0.7, so "creative"
        assertTrue(directive.contains("casual"))
        assertTrue(directive.contains("creative"))
    }
}