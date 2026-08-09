package com.aura.providers

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [ProviderOutputLimits]' contract is unusual and worth pinning: a **null is the
 * safe answer**, not a gap. Null preserves the behaviour this table was added to
 * change, so a missing entry can only ever be conservative, while a wrong entry
 * would silently truncate answers. Every assertion below is really about that.
 */
class ProviderOutputLimitsTest {

    /**
     * The one entry that exists. Anthropic is the only provider here that both
     * hard-rejects an oversized `max_tokens` (rather than clamping it) and
     * documents a platform-wide floor.
     */
    @Test
    fun `anthropic has a platform output ceiling`() {
        assertEquals(32_000, ProviderOutputLimits.lookup("anthropic", "claude-sonnet-4"))
        assertEquals(32_000, ProviderOutputLimits.lookup("anthropic", "anything-else"))
    }

    /** No model-name substring heuristics — those rot as vendors rename models. */
    @Test
    fun `the anthropic ceiling does not vary by model name`() {
        val names = listOf("claude-opus-4", "claude-haiku-4-5", "", "claude-3-5-sonnet-20241022")
        val values = names.map { ProviderOutputLimits.lookup("anthropic", it) }.distinct()
        assertEquals(1, values.size, "the table must not branch on model name: got $values")
    }

    /**
     * These report a real per-model value through `listModelsWithContext`, so a
     * table entry could only ever be the worse answer.
     */
    @Test
    fun `providers that report a live value have no table entry`() {
        for (prefix in listOf("gemini", "openrouter", "chatgpt")) {
            assertNull(ProviderOutputLimits.lookup(prefix, "any-model"), "$prefix should defer to its live catalog")
        }
    }

    /**
     * OpenAI-compatible endpoints span 4K to 100K+ output across models and
     * generally clamp rather than reject, so a single number would cost more
     * than it saves.
     */
    @Test
    fun `openai-compatible providers are left unclamped`() {
        for (prefix in listOf("openai", "groq", "deepseek", "mistral", "xai", "together", "cerebras", "nvidia")) {
            assertNull(ProviderOutputLimits.lookup(prefix, "any-model"), "$prefix should not be clamped on a guess")
        }
    }

    @Test
    fun `a user's own endpoint and unknown providers are unknowable`() {
        assertNull(ProviderOutputLimits.lookup("custom", "whatever"))
        assertNull(ProviderOutputLimits.lookup("moa", "whatever"))
        assertNull(ProviderOutputLimits.lookup("a-provider-that-does-not-exist", "whatever"))
        assertNull(ProviderOutputLimits.lookup("", ""))
    }
}
