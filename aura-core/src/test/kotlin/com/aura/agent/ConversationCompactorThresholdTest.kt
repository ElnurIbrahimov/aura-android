package com.aura.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for ConversationCompactor's threshold logic.
 *
 * Pre-fix, the compactor used string-matching on the model
 * name ("4k" / "8k" / "4096" / "8192") to decide when to
 * compact. Modern models rarely embed context size in the
 * name (Claude Sonnet 4 doesn't say "200k" anywhere), so
 * the compactor would either trigger too early on a small
 * model that happened to have "8k" in its name, or skip
 * compaction on a huge-context model that didn't.
 *
 * Post-fix, the compactor asks for the model's actual
 * context window. When unknown, it uses a generous 32K
 * default that covers Claude Sonnet 4, Gemini 2.5,
 * GPT-4o, Llama 3.1 70B, and most modern models.
 */
class ConversationCompactorThresholdTest {

    @Test
    fun `resolveThreshold with explicit context window returns 80 percent`() {
        // Claude Sonnet 4 has 200K context. 80% = 160,000.
        assertEquals(160_000, ConversationCompactor.resolveThreshold("claude-sonnet-4", 200_000))
        // Gemini 2.5 has 1M context. 80% = 800,000.
        assertEquals(800_000, ConversationCompactor.resolveThreshold("gemini-2.5-flash", 1_000_000))
        // GPT-4o has 128K context. 80% = 102,400.
        assertEquals(102_400, ConversationCompactor.resolveThreshold("gpt-4o", 128_000))
    }

    @Test
    fun `resolveThreshold with tiny context window floors at 4K`() {
        // Even a 2K-context model gets a 4K floor so the
        // compactor doesn't fire on every single turn.
        // 2K * 0.8 = 1,600 → floor at 4,000.
        assertEquals(4_000, ConversationCompactor.resolveThreshold("tiny", 2_000))
    }

    @Test
    fun `resolveThreshold with null context window uses default`() {
        // No catalog data: use the generous 32K default
        // that covers all modern models without false
        // positives. Previously "claude-sonnet-4" would
        // fall to MAX_UNCOMPACTED_TOKENS=12K and compact
        // way too early (every ~3K chars).
        assertEquals(
            ConversationCompactor.DEFAULT_UNCOMPACTED_TOKENS,
            ConversationCompactor.resolveThreshold("claude-sonnet-4"),
        )
        assertEquals(
            ConversationCompactor.DEFAULT_UNCOMPACTED_TOKENS,
            ConversationCompactor.resolveThreshold("gemini-2.5-flash"),
        )
    }

    @Test
    fun `resolveThreshold no longer matches model name strings`() {
        // The old API (thresholdForModel) had branches for
        // "4k", "8k", "4096", "8192" in the name. Modern
        // models don't use that convention, so the new
        // function ignores the model name entirely when
        // contextWindow is null. Any model name returns
        // the same default.
        val allNames = listOf(
            "claude-sonnet-4",
            "gpt-4o",
            "gemini-2.5-flash",
            "llama-3.1-70b",
            "mixtral-8x7b",
            "qwen2.5-coder-32b",
        )
        val allSame = allNames.map { ConversationCompactor.resolveThreshold(it) }.toSet()
        assertEquals(
            "All modern models should map to the same default when context is unknown",
            1,
            allSame.size,
        )
    }

    @Test
    fun `default threshold is at least 32K for modern models`() {
        // Sanity check: DEFAULT_UNCOMPACTED_TOKENS is at
        // least 32,000 so it covers Claude Sonnet 4's
        // useful working context without premature
        // compaction.
        assertTrue(
            "DEFAULT_UNCOMPACTED_TOKENS must be >= 32,000 for modern models",
            ConversationCompactor.DEFAULT_UNCOMPACTED_TOKENS >= 32_000,
        )
    }
}
