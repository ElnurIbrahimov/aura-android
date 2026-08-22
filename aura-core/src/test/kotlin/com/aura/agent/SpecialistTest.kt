package com.aura.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the [Specialist] presets.
 *
 * These are live: `AgentStore` seeds the seven builtin agents from
 * [Specialist.ALL], and `ProblemCategory.classify` takes a `Specialist`. Only
 * `SpecialistRouter` — the keyword matcher that chose one automatically — was
 * dead, and its tests went with it. That distinction matters: a review pass
 * read the presets as dead too, because their only *other* reader was the
 * router.
 */
class SpecialistTest {

    // ------------------------------------------------------------------
    // Specialist data class basics
    // ------------------------------------------------------------------

    @Test
    fun `specialist has expected fields`() {
        val s = Specialist(
            name = "test",
            icon = "\uD83D\uDC40",
            blurb = "A test agent",
            systemPrompt = "Test prompt",
            toolsAllowed = setOf("tool_a"),
            suggestedModel = "model-x",
        )
        assertEquals("test", s.name)
        assertEquals("\uD83D\uDC40", s.icon)
        assertEquals("Test prompt", s.systemPrompt)
        assertEquals(setOf("tool_a"), s.toolsAllowed)
        assertEquals("model-x", s.suggestedModel)
    }

    @Test
    fun `specialist - suggested model is nullable`() {
        val s = Specialist(name = "a", icon = "\uD83D\uDC40", blurb = "b", systemPrompt = "p")
        assertNull(s.suggestedModel)
        assertTrue(s.toolsAllowed.isEmpty())
    }

    @Test
    fun `all predefined specialists are available`() {
        assertEquals(7, Specialist.ALL.size)
        assertTrue(Specialist.ALL.any { it.name == "general" })
        assertTrue(Specialist.ALL.any { it.name == "coder" })
        assertTrue(Specialist.ALL.any { it.name == "researcher" })
        assertTrue(Specialist.ALL.any { it.name == "writer" })
        assertTrue(Specialist.ALL.any { it.name == "creative" })
        assertTrue(Specialist.ALL.any { it.name == "executive" })
        assertTrue(Specialist.ALL.any { it.name == "phone_native" })
    }

    @Test
    fun `byName lookup works`() {
        assertEquals("coder", Specialist.byName("coder")?.name)
        assertEquals("creative", Specialist.byName("creative")?.name)
        assertNull(Specialist.byName("nonexistent"))
    }

    @Test
    fun `general has no tool restriction`() {
        assertTrue(Specialist.General.toolsAllowed.isEmpty())
    }

    @Test
    fun `coder has expected tools`() {
        assertTrue(Specialist.Coder.toolsAllowed.contains("web_search"))
        assertTrue(Specialist.Coder.toolsAllowed.contains("fetch_url"))
    }

    @Test
    fun `researcher has expected tools`() {
        assertTrue(Specialist.Researcher.toolsAllowed.contains("deep_research"))
        assertTrue(Specialist.Researcher.toolsAllowed.contains("web_search"))
    }

    /**
     * The loop hides `brave_search` / `tavily_search` from the model entirely —
     * `web_search` dispatches to them internally. Listing them in an allowlist
     * grants nothing, so keeping them there only implies a capability the
     * specialist does not actually have.
     */
    @Test
    fun `no specialist allowlists a search backend the model never sees`() {
        val hidden = setOf("brave_search", "tavily_search")
        val offenders = Specialist.ALL
            .filter { it.toolsAllowed.any { tool -> tool in hidden } }
            .map { it.name }
        assertTrue("specialists allowlisting hidden search backends: $offenders", offenders.isEmpty())
    }

    @Test
    fun `creative has expected tools`() {
        assertTrue(Specialist.Creative.toolsAllowed.contains("image_gen"))
        assertEquals(setOf("image_gen"), Specialist.Creative.toolsAllowed)
    }

    @Test
    fun `executive has expected tools`() {
        assertTrue(Specialist.Executive.toolsAllowed.contains("calendar_read"))
        assertTrue(Specialist.Executive.toolsAllowed.contains("calendar_write"))
        assertTrue(Specialist.Executive.toolsAllowed.contains("contacts_search"))
        assertTrue(Specialist.Executive.toolsAllowed.contains("remember"))
        assertTrue(Specialist.Executive.toolsAllowed.contains("recall"))
    }

    @Test
    fun `phone_native has expected tools`() {
        assertTrue(Specialist.PhoneNative.toolsAllowed.contains("photo_library"))
        assertTrue(Specialist.PhoneNative.toolsAllowed.contains("location_now"))
        assertTrue(Specialist.PhoneNative.toolsAllowed.contains("set_reminder"))
    }

    // ------------------------------------------------------------------
    // withOverrides — the Settings customisation rules
    // ------------------------------------------------------------------
    //
    // These rules shipped twice: as `applyOverrides`/`applyToolOverrides` here,
    // which nothing called, and inline in `ChatSendController`, which is the
    // copy that actually ran. Tested here now, where the one surviving
    // implementation lives.

    @Test
    fun `an override replaces the builtin prompt and tools`() {
        val custom = Specialist.Coder.withOverrides(
            promptOverrides = mapOf("coder" to "You write only Kotlin."),
            toolOverrides = mapOf("coder" to setOf("code_interpreter")),
        )

        assertEquals("You write only Kotlin.", custom.systemPrompt)
        assertEquals(setOf("code_interpreter"), custom.toolsAllowed)
        assertEquals("coder", custom.name)
    }

    @Test
    fun `an override for a different specialist is ignored`() {
        val untouched = Specialist.Coder.withOverrides(
            promptOverrides = mapOf("writer" to "Prose only."),
            toolOverrides = mapOf("writer" to setOf("web_search")),
        )

        assertEquals(Specialist.Coder.systemPrompt, untouched.systemPrompt)
        assertEquals(Specialist.Coder.toolsAllowed, untouched.toolsAllowed)
    }

    @Test
    fun `blank and empty overrides reset to the builtin rather than erasing it`() {
        // A cleared text field decodes as "", and an emptied tool list decodes
        // as []. Neither is distinguishable from "the user never set one" in
        // the JSON these come from, so both read as reset. Taking them
        // literally would ship a specialist with no system prompt, or one
        // silently stripped of every tool — a specialist that quietly stops
        // being able to do its job is worse than one that ignores an edit.
        val cleared = Specialist.Researcher.withOverrides(
            promptOverrides = mapOf("researcher" to "   "),
            toolOverrides = mapOf("researcher" to emptySet()),
        )

        assertEquals(Specialist.Researcher.systemPrompt, cleared.systemPrompt)
        assertEquals(Specialist.Researcher.toolsAllowed, cleared.toolsAllowed)
    }

    @Test
    fun `no overrides at all is the identity`() {
        assertEquals(
            Specialist.General,
            Specialist.General.withOverrides(emptyMap(), emptyMap()),
        )
    }
}
