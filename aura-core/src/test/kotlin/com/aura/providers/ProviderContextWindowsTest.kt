package com.aura.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the per-provider context window fallback table.
 *
 * SNAPSHOT — last verified 2026-08-01. Values are platform-wide
 * minimums. When a provider ships a model with a larger context,
 * listModelsWithContext should return it live and override this
 * table.
 */
class ProviderContextWindowsTest {

    @Test
    fun `anthropic returns platform-wide 200K context`() {
        assertEquals(200_000, ProviderContextWindows.lookup("anthropic", "claude-opus-4-20250514"))
        assertEquals(200_000, ProviderContextWindows.lookup("anthropic", "claude-sonnet-4-20250514"))
        assertEquals(200_000, ProviderContextWindows.lookup("anthropic", "claude-2.1"))
    }

    @Test
    fun `openai returns 128K platform default`() {
        assertEquals(128_000, ProviderContextWindows.lookup("openai", "gpt-4o"))
        assertEquals(128_000, ProviderContextWindows.lookup("openai", "gpt-4-0613"))
        assertEquals(128_000, ProviderContextWindows.lookup("openai", "o1"))
    }

    @Test
    fun `groq returns 128K platform default`() {
        assertEquals(128_000, ProviderContextWindows.lookup("groq", "llama-3.1-70b-versatile"))
        assertEquals(128_000, ProviderContextWindows.lookup("groq", "mixtral-8x7b-instruct"))
    }

    @Test
    fun `chatgpt returns 128K platform default`() {
        assertEquals(128_000, ProviderContextWindows.lookup("chatgpt", "gpt-4o"))
    }

    @Test
    fun `deepseek returns 128K platform default`() {
        assertEquals(128_000, ProviderContextWindows.lookup("deepseek", "deepseek-chat"))
        assertEquals(128_000, ProviderContextWindows.lookup("deepseek", "deepseek-coder"))
    }

    @Test
    fun `mistral returns 128K platform default`() {
        assertEquals(128_000, ProviderContextWindows.lookup("mistral", "mistral-large-2"))
    }

    @Test
    fun `xai returns 128K platform default`() {
        assertEquals(128_000, ProviderContextWindows.lookup("xai", "grok-2"))
    }

    @Test
    fun `together returns 128K platform default`() {
        assertEquals(128_000, ProviderContextWindows.lookup("together", "meta-llama-3.1-405b"))
    }

    @Test
    fun `cerebras returns 128K platform default`() {
        assertEquals(128_000, ProviderContextWindows.lookup("cerebras", "llama-3.1-70b"))
    }

    @Test
    fun `nvidia returns 128K platform default`() {
        assertEquals(128_000, ProviderContextWindows.lookup("nvidia", "llama-3.1-nemotron-70b"))
    }

    @Test
    fun `custom and moa return null by design`() {
        assertNull(ProviderContextWindows.lookup("custom", "anything"))
        assertNull(ProviderContextWindows.lookup("moa", "anything"))
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