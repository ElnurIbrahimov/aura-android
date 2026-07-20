package com.aura.emotion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import io.mockk.mockk

class EmotionEngineTest {

    private lateinit var engine: EmotionEngine

    @Before
    fun setUp() {
        engine = EmotionEngine(mockk(relaxed = true))
    }

    @Test
    fun `snapshot returns defaults before any update`() {
        val s = engine.snapshot()
        assertEquals(0.3f, s.tension, 0.01f)
        assertEquals(0.5f, s.connection, 0.01f)
        assertEquals(0.4f, s.energy, 0.01f)
        assertEquals(0.3f, s.focus, 0.01f)
    }

    @Test
    fun `blank message does not change state`() {
        val before = engine.snapshot()
        engine.update("   ")
        val after = engine.snapshot()
        assertEquals(before.tension, after.tension, 0.001f)
        assertEquals(before.connection, after.connection, 0.001f)
    }

    @Test
    fun `frustration patterns raise tension`() {
        engine.update("This doesn't work at all, it's broken!")
        val s = engine.snapshot()
        assertTrue("tension should be above baseline", s.tension > 0.3f)
    }

    @Test
    fun `wtf raises tension significantly`() {
        engine.update("wtf this is ridiculous")
        val s = engine.snapshot()
        assertTrue("tension should spike", s.tension > 0.4f)
    }

    @Test
    fun `satisfaction patterns lower tension and raise connection`() {
        engine.update("That's great, thank you so much! Perfect!")
        val s = engine.snapshot()
        assertTrue("tension should drop", s.tension < 0.3f)
        assertTrue("connection should rise", s.connection > 0.5f)
    }

    @Test
    fun `code request raises focus`() {
        engine.update("Can you write a function to debug this API error?")
        val s = engine.snapshot()
        assertTrue("focus should rise", s.focus > 0.3f)
    }

    @Test
    fun `question mark raises focus slightly`() {
        engine.update("What time is it?")
        val s = engine.snapshot()
        assertTrue("focus should rise", s.focus > 0.3f)
    }

    @Test
    fun `long message raises energy and connection`() {
        val longMsg = "I was thinking about the project we discussed earlier and I wanted to share some thoughts about how we might approach the next phase. The key challenge is balancing speed with quality, and I think we should prioritize the user-facing features first before diving into the backend optimization work."
        engine.update(longMsg)
        val s = engine.snapshot()
        assertTrue("energy should rise for long messages", s.energy > 0.4f)
        assertTrue("connection should rise for long messages", s.connection > 0.5f)
    }

    @Test
    fun `short message raises tension slightly`() {
        engine.update("no")
        val s = engine.snapshot()
        assertTrue("short messages raise tension", s.tension > 0.3f)
    }

    @Test
    fun `decay moves values toward baseline`() {
        // Push tension high with multiple frustrated messages
        repeat(5) { engine.update("wtf broken doesn't work angry frustrated pissed") }
        val peaked = engine.snapshot()
        assertTrue("need high tension before decay (got ${peaked.tension})", peaked.tension > 0.4f)

        engine.decay()
        val decayed = engine.snapshot()
        assertTrue("tension should decrease after decay", decayed.tension < peaked.tension)
    }

    @Test
    fun `decay moves connection toward baseline`() {
        // Push connection high with multiple satisfied messages
        repeat(5) { engine.update("great awesome perfect love it amazing fantastic excellent") }
        val peaked = engine.snapshot()
        assertTrue("need high connection before decay (got ${peaked.connection})", peaked.connection > 0.6f)

        engine.decay()
        val decayed = engine.snapshot()
        assertTrue("connection should decrease after decay", decayed.connection < peaked.connection)
    }

    @Test
    fun `multiple decay calls converge toward baseline`() {
        engine.update("wtf broken angry")
        repeat(20) { engine.decay() }
        val s = engine.snapshot()
        assertEquals("tension should converge to baseline", 0.3f, s.tension, 0.1f)
    }

    @Test
    fun `moodString contains all four dimensions`() {
        engine.update("Can you debug this function?")
        val mood = engine.moodString()
        assertTrue(mood.contains("tension="))
        assertTrue(mood.contains("connection="))
        assertTrue(mood.contains("energy="))
        assertTrue(mood.contains("focus="))
    }

    @Test
    fun `profile returns NEUTRAL by default`() {
        val profile = engine.profile()
        assertEquals(ResponseProfile.NEUTRAL, profile)
    }

    @Test
    fun `profile returns FOCUSED after code request`() {
        engine.update("code function debug error stack trace class method api")
        val profile = engine.profile()
        assertTrue("expected FOCUSED or similar high-focus profile, got $profile",
            profile == ResponseProfile.FOCUSED || profile == ResponseProfile.NEUTRAL)
    }

    // ResponseProfile tests
    @Test
    fun `ResponseProfile DIRECT when high tension and low connection`() {
        val snapshot = EmotionEngine.EmotionSnapshot(tension = 0.8f, connection = 0.2f)
        assertEquals(ResponseProfile.DIRECT, ResponseProfile.from(snapshot))
    }

    @Test
    fun `ResponseProfile WARM when high connection and low tension`() {
        val snapshot = EmotionEngine.EmotionSnapshot(tension = 0.2f, connection = 0.8f)
        assertEquals(ResponseProfile.WARM, ResponseProfile.from(snapshot))
    }

    @Test
    fun `ResponseProfile CALM when low energy and low tension`() {
        val snapshot = EmotionEngine.EmotionSnapshot(tension = 0.2f, energy = 0.2f)
        assertEquals(ResponseProfile.CALM, ResponseProfile.from(snapshot))
    }

    @Test
    fun `ResponseProfile FOCUSED when high focus and low energy`() {
        val snapshot = EmotionEngine.EmotionSnapshot(focus = 0.8f, energy = 0.3f)
        assertEquals(ResponseProfile.FOCUSED, ResponseProfile.from(snapshot))
    }

    @Test
    fun `ResponseProfile ENERGETIC when high tension and high energy`() {
        val snapshot = EmotionEngine.EmotionSnapshot(tension = 0.7f, energy = 0.7f)
        assertEquals(ResponseProfile.ENERGETIC, ResponseProfile.from(snapshot))
    }

    @Test
    fun `ResponseProfile NEUTRAL for midrange values`() {
        val snapshot = EmotionEngine.EmotionSnapshot()
        assertEquals(ResponseProfile.NEUTRAL, ResponseProfile.from(snapshot))
    }

    @Test
    fun `ResponseProfile promptSuffix is non-empty for non-neutral profiles`() {
        for (profile in ResponseProfile.entries) {
            if (profile != ResponseProfile.NEUTRAL) {
                assertTrue("${profile.name} should have non-empty suffix", profile.promptSuffix.isNotBlank())
            }
        }
    }
}