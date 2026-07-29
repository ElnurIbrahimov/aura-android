package com.aura.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the per-provider context window fallback.
 * Only Anthropic has a documented platform-wide context window
 * (200K). All other providers return null so the compactor can
 * fall back to its safe 32K default.
 */
class ProviderContextWindowsTest {

    @Test
    fun `anthropic returns platform-wide 200K context`() {
        assertEquals(200_000, ProviderContextWindows.lookup("anthropic", "claude-opus-4-20250514"))
        assertEquals(200_000, ProviderContextWindows.lookup("anthropic", "claude-sonnet-4-20250514"))
        assertEquals(200_000, ProviderContextWindows.lookup("anthropic", "claude-2.1"))
    }

    @Test
    fun `openai returns null and uses compactor default`() {
        assertNull(ProviderContextWindows.lookup("openai", "gpt-4o"))
        assertNull(ProviderContextWindows.lookup("openai", "gpt-4-0613"))
        assertNull(ProviderContextWindows.lookup("openai", "o1"))
    }

    @Test
    fun `groq returns null and uses compactor default`() {
        assertNull(ProviderContextWindows.lookup("groq", "llama-3.1-70b-versatile"))
        assertNull(ProviderContextWindows.lookup("groq", "mixtral-8x7b-instruct"))
    }

    @Test
    fun `chatgpt returns null and uses compactor default`() {
        assertNull(ProviderContextWindows.lookup("chatgpt", "gpt-4o"))
    }

    @Test
    fun `unknown prefix returns null`() {
        assertNull(ProviderContextWindows.lookup("mystery-provider", "any-model"))
    }

    @Test
    fun `model name is ignored by design`() {
        // ProviderContextWindows intentionally does NOT inspect model
        // names. Any Anthropic model should return the same platform
        // default regardless of name substring.
        assertEquals(
            ProviderContextWindows.lookup("anthropic", "claude-sonnet-4"),
            ProviderContextWindows.lookup("anthropic", "future-model-xyz"),
        )
    }
}
