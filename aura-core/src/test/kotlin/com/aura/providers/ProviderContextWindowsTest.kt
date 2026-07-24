package com.aura.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the per-provider context window table. The
 * table is a snapshot — when a provider ships a new
 * model, the lookup returns null and the compactor uses
 * the 32K default. Don't add entries for alpha/beta
 * models that might change before GA.
 */
class ProviderContextWindowsTest {

    // --- Anthropic ---

    @Test
    fun `anthropic modern models map to 200K`() {
        // Latest Claude family uses 200K context.
        assertEquals(200_000, ProviderContextWindows.lookup("anthropic", "claude-opus-4-20250514"))
        assertEquals(200_000, ProviderContextWindows.lookup("anthropic", "claude-sonnet-4-20250514"))
        assertEquals(200_000, ProviderContextWindows.lookup("anthropic", "claude-haiku-4-20250514"))
    }

    @Test
    fun `anthropic claude-3 family maps to 200K`() {
        assertEquals(200_000, ProviderContextWindows.lookup("anthropic", "claude-3-opus-20240229"))
        assertEquals(200_000, ProviderContextWindows.lookup("anthropic", "claude-3-sonnet-20240229"))
        assertEquals(200_000, ProviderContextWindows.lookup("anthropic", "claude-3-haiku-20240307"))
    }

    @Test
    fun `anthropic legacy models map to 100K`() {
        assertEquals(100_000, ProviderContextWindows.lookup("anthropic", "claude-2.1"))
        assertEquals(100_000, ProviderContextWindows.lookup("anthropic", "claude-instant-1.2"))
    }

    // --- OpenAI ---

    @Test
    fun `openai gpt-4o and gpt-4-turbo map to 128K`() {
        assertEquals(128_000, ProviderContextWindows.lookup("openai", "gpt-4o-2024-08-06"))
        assertEquals(128_000, ProviderContextWindows.lookup("openai", "gpt-4-turbo-2024-04-09"))
    }

    @Test
    fun `openai gpt-4 original maps to 8K`() {
        // Original gpt-4 (not turbo) was 8K context.
        assertEquals(8_192, ProviderContextWindows.lookup("openai", "gpt-4-0613"))
    }

    @Test
    fun `openai gpt-4-32k maps to 32K`() {
        assertEquals(32_768, ProviderContextWindows.lookup("openai", "gpt-4-32k-0613"))
    }

    @Test
    fun `openai reasoning models map to 200K`() {
        assertEquals(200_000, ProviderContextWindows.lookup("openai", "o1"))
        assertEquals(200_000, ProviderContextWindows.lookup("openai", "o1-2024-12-17"))
        assertEquals(200_000, ProviderContextWindows.lookup("openai", "o3"))
        assertEquals(200_000, ProviderContextWindows.lookup("openai", "o3-mini"))
        assertEquals(200_000, ProviderContextWindows.lookup("openai", "o4-mini"))
    }

    @Test
    fun `openai gpt-3 point 5 maps to 16K`() {
        assertEquals(16_385, ProviderContextWindows.lookup("openai", "gpt-3.5-turbo-16k-0613"))
        assertEquals(16_385, ProviderContextWindows.lookup("openai", "gpt-3.5-turbo-0125"))
    }

    // --- ChatGPT subscription ---

    @Test
    fun `chatgpt uses the openai table`() {
        // ChatGPT is gated to OpenAI model IDs, so the
        // same context windows apply.
        assertEquals(128_000, ProviderContextWindows.lookup("chatgpt", "gpt-4o"))
        assertEquals(200_000, ProviderContextWindows.lookup("chatgpt", "o1"))
    }

    // --- Groq ---

    @Test
    fun `groq llama 3 point 1 and 3 point 3 use 131K context`() {
        assertEquals(131_072, ProviderContextWindows.lookup("groq", "llama-3.1-70b-versatile"))
        assertEquals(131_072, ProviderContextWindows.lookup("groq", "llama-3.1-8b-instant"))
        assertEquals(131_072, ProviderContextWindows.lookup("groq", "llama-3.3-70b-versatile"))
    }

    @Test
    fun `groq llama 3 original uses 8K context`() {
        // Llama 3 (not 3.1) was 8K context.
        assertEquals(8_192, ProviderContextWindows.lookup("groq", "llama-3-70b"))
    }

    @Test
    fun `groq mixtral uses 32K context`() {
        assertEquals(32_768, ProviderContextWindows.lookup("groq", "mixtral-8x7b-instruct"))
    }

    // --- Unknown models fall through to null ---

    @Test
    fun `unknown model returns null so compactor uses 32K default`() {
        // We don't have a Claude 5 in the table — and we
        // shouldn't until it's GA. Returns null → compactor
        // uses 32K default. Better than guessing wrong.
        assertNull(ProviderContextWindows.lookup("anthropic", "claude-5-future"))
    }

    @Test
    fun `unknown prefix returns null`() {
        // Custom / unknown provider prefix.
        assertNull(ProviderContextWindows.lookup("mystery-provider", "any-model"))
    }

    @Test
    fun `no entry guesses 4K or 8K for safety`() {
        // Sanity check: the table never returns a value
        // BELOW 4_000 (would be wrong) or ABOVE 1_000_000
        // (would be wrong). All entries are either
        // reasonable modern context windows or null.
        val testCases = listOf(
            "anthropic" to "claude-opus-4",
            "openai" to "gpt-4o",
            "openai" to "o1",
            "groq" to "llama-3.1-70b",
        )
        for ((prefix, model) in testCases) {
            val ctx = ProviderContextWindows.lookup(prefix, model) ?: continue
            assertTrue("$prefix:$model context $ctx should be >= 4K", ctx >= 4_000)
            assertTrue("$prefix:$model context $ctx should be <= 1M", ctx <= 1_000_000)
        }
    }
}
