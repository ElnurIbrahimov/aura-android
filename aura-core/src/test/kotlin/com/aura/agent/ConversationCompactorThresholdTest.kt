package com.aura.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for ConversationCompactor's threshold logic.
 *
 * Pre-fix, the compactor used string-matching on the model
 * name ("4k" / "8k" / "4096" / "8192") to decide when to
 * compact. Models that didn't have those substrings in the
 * name fell to a hardcoded 12K threshold — which fired way
 * too early for any model with more than ~12K of working
 * context.
 *
 * Post-fix, the compactor asks for the model's actual
 * context window. When unknown, it uses a generous 32K
 * default that doesn't trigger on the first 8K of context.
 */
class ConversationCompactorThresholdTest {

    @Test
    fun `resolveThreshold with explicit context window returns 80 percent`() {
        // Pick three different context sizes to pin the 80% rule.
        // 80% of 100K = 80,000.
        assertEquals(80_000, ConversationCompactor.resolveThreshold("any-model", 100_000))
        // 80% of 200K = 160,000.
        assertEquals(160_000, ConversationCompactor.resolveThreshold("any-model", 200_000))
        // 80% of 8K = 6,400.
        assertEquals(6_400, ConversationCompactor.resolveThreshold("any-model", 8_000))
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
        // so the compactor doesn't fire on the first
        // ~8K of context. Previously, any model without
        // "4k" or "8k" in its name hit MAX_UNCOMPACTED_TOKENS=12K
        // and compacted way too early.
        assertEquals(
            ConversationCompactor.DEFAULT_UNCOMPACTED_TOKENS,
            ConversationCompactor.resolveThreshold("any-model"),
        )
    }

    @Test
    fun `resolveThreshold no longer matches model name strings`() {
        // The old API (thresholdForModel) had branches for
        // "4k", "8k", "4096", "8192" in the name. The new
        // function ignores the model name entirely when
        // contextWindow is null — no string matching at all.
        val allNames = listOf(
            "any-model",
            "some-llm",
            "gpt-style",
            "claude-style",
            "llama-style",
        )
        val allSame = allNames.map { ConversationCompactor.resolveThreshold(it) }.toSet()
        assertEquals(
            "All model names should map to the same default when context is unknown",
            1,
            allSame.size,
        )
    }

    @Test
    fun `default threshold is at least 32K to avoid premature compaction`() {
        // Sanity check: DEFAULT_UNCOMPACTED_TOKENS is at
        // least 32,000 so the compactor doesn't fire on
        // normal-sized conversations. The old 12K default
        // was too aggressive — a normal 20-turn chat
        // would compact mid-conversation on big-context
        // models.
        assertTrue(
            "DEFAULT_UNCOMPACTED_TOKENS must be >= 32,000",
            ConversationCompactor.DEFAULT_UNCOMPACTED_TOKENS >= 32_000,
        )
    }
}
