package com.aura.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Builtin agents are stored under their identifier — `phone_native`, not
 * "Phone Native" — because `delegate_to_agent` looks them up by that exact
 * string and lists them back in its own error message. The picker showed the
 * identifier verbatim, so a list of seven agents read like a config file.
 *
 * Custom agents are named by whoever created them, and their capitalisation is
 * a choice rather than a convention to be corrected.
 */
class AgentLabelsTest {

    @Test
    fun `a snake case builtin becomes words`() {
        assertEquals("Phone Native", agentDisplayName("phone_native"))
    }

    @Test
    fun `a single lowercase word is capitalised`() {
        assertEquals("Coder", agentDisplayName("coder"))
        assertEquals("Researcher", agentDisplayName("researcher"))
    }

    @Test
    fun `a name the user capitalised is left alone`() {
        // Not "Deep Thought Mk II" re-cased, and not "MyAgent" split apart.
        assertEquals("Deep Thought MkII", agentDisplayName("Deep Thought MkII"))
        assertEquals("MyAgent", agentDisplayName("MyAgent"))
    }

    @Test
    fun `a multi word lowercase custom name is left alone`() {
        // It already has spaces, so the user typed it as a phrase.
        assertEquals("night owl", agentDisplayName("night owl"))
    }

    @Test
    fun `hyphens separate words too`() {
        assertEquals("Phone Native", agentDisplayName("phone-native"))
    }

    @Test
    fun `repeated separators do not produce empty words`() {
        assertEquals("A B", agentDisplayName("a__b"))
    }

    @Test
    fun `a blank name is returned unchanged`() {
        assertEquals("", agentDisplayName(""))
        assertEquals("   ", agentDisplayName("   "))
    }
}
