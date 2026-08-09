package com.aura.agent

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [TokenBudgetPolicy]. This logic used to live inline in `Brain.stream()`
 * with no coverage whatsoever, and it shipped two defects that only a live
 * provider could reveal:
 *
 *  - a caller-set `maxTokens` had the thinking budget clamped to `maxTokens - 1`,
 *    so a creative draft could spend its whole response allowance thinking;
 *  - a caller-set `thinkingBudget` skipped the logic entirely, so four Creative
 *    Studio call sites reached Anthropic with `budget_tokens >= max_tokens` and
 *    took a non-retryable 400.
 *
 * Every case below is a regression guard for one of those, or for the auxiliary
 * protection that must survive the fix.
 */
class TokenBudgetPolicyTest {

    private fun resolve(
        callerMaxTokens: Int? = null,
        callerThinkingBudget: Int? = null,
        resolverMaxTokens: Int? = null,
        reasoningEnabled: Boolean = true,
        reasoningBudget: Int = 32_000,
        outputCeiling: Int? = null,
    ) = TokenBudgetPolicy.resolve(
        callerMaxTokens = callerMaxTokens,
        callerThinkingBudget = callerThinkingBudget,
        resolverMaxTokens = resolverMaxTokens,
        reasoningEnabled = reasoningEnabled,
        reasoningBudget = reasoningBudget,
        outputCeiling = outputCeiling,
    )

    // ---------------------------------------------------------------- auxiliary

    /**
     * The non-regression guard that matters most. These are the real
     * auxiliary call sites — reflection (150), the linear plan prefix (150),
     * ReasoningTree.expand (300) and .score (20), MemoryReranker (50),
     * QueryRewriter (100), ParallelResearch decompose (200) and subagent (800),
     * DebateRound (400). Injecting a 32K thinking budget into any of them was a
     * 375x cost inflation, which is what the old `< 1000` gate existed to stop.
     */
    @Test
    fun `small auxiliary calls get no thinking budget and keep their own maxTokens`() {
        for (small in listOf(20, 50, 100, 150, 200, 300, 400, 800)) {
            val budget = resolve(callerMaxTokens = small)
            assertNull(budget.thinkingBudget, "maxTokens=$small should not get a thinking budget")
            assertEquals(small, budget.maxTokens, "maxTokens=$small should be untouched")
        }
    }

    /**
     * The old gate was a bare `1000`, which left 1000..1023 in a dead zone: a
     * budget was injected and then clamped to a value Anthropic rejects, since
     * its documented floor for `budget_tokens` is 1024.
     */
    @Test
    fun `the auxiliary threshold is Anthropic's 1024 floor, not 1000`() {
        assertNull(resolve(callerMaxTokens = 1_023).thinkingBudget)
        assertEquals(1_023, resolve(callerMaxTokens = 1_023).maxTokens)

        val atFloor = resolve(callerMaxTokens = 1_024)
        assertEquals(1_024, atFloor.thinkingBudget)
        assertEquals(2_048, atFloor.maxTokens)
    }

    // ------------------------------------------------------------- caller path

    /**
     * The core of the fix: thinking is additive to what the caller asked for,
     * so the requested output survives intact. Before, `total` WAS the caller's
     * number and thinking was carved out of it.
     */
    @Test
    fun `a caller's requested output survives in full`() {
        for (requested in listOf(1_200, 2_048, 8_192, 16_384, 28_672)) {
            val budget = resolve(callerMaxTokens = requested)
            val total = assertNotNull(budget.maxTokens)
            val thinking = assertNotNull(budget.thinkingBudget)
            assertEquals(
                requested,
                total - thinking,
                "output left after thinking should equal the requested $requested",
            )
        }
    }

    /** The additive rule's ceiling: never worse than 2x what the call site asked for. */
    @Test
    fun `the global preference cannot more than double a caller's request`() {
        val budget = resolve(callerMaxTokens = 8_192, reasoningBudget = 32_000)
        assertEquals(8_192, budget.thinkingBudget, "the 32K preference is bounded by the caller's own size")
        assertEquals(16_384, budget.maxTokens)
    }

    @Test
    fun `thinking never exceeds the configured reasoning budget`() {
        val budget = resolve(callerMaxTokens = 100_000, reasoningBudget = 32_000)
        assertEquals(32_000, budget.thinkingBudget)
        assertEquals(132_000, budget.maxTokens)
    }

    // ------------------------------------------- caller-set thinking (the D4 four)

    /**
     * The four Creative Studio call sites that reached the wire with
     * `budget_tokens >= max_tokens` and took a non-retryable 400 on Anthropic.
     * `Brain` skipped its entire budget block whenever the caller supplied a
     * thinking budget, so nothing normalised these.
     */
    @Test
    fun `the four broken creative callers now produce a valid pair`() {
        val cases = listOf(
            Triple("ProseCraftTools EXPAND", 8_192, 8_192),
            Triple("ProseCraftTools other", 4_096, 8_192),
            Triple("TensionAnalyzer", 6_000, 16_384),
            Triple("VoiceCalibration.calibrate", 1_500, 8_192),
            Triple("CharacterProgressionTracker", 2_000, 4_096),
        )
        for ((name, max, thinking) in cases) {
            val budget = resolve(callerMaxTokens = max, callerThinkingBudget = thinking)
            val total = assertNotNull(budget.maxTokens, name)
            val resolvedThinking = assertNotNull(budget.thinkingBudget, name)
            assertTrue(total > resolvedThinking, "$name: max_tokens ($total) must exceed thinking ($resolvedThinking)")
            assertEquals(thinking, resolvedThinking, "$name: an explicit thinking budget is honoured verbatim")
            assertEquals(max, total - resolvedThinking, "$name: the requested output survives")
        }
    }

    /**
     * An explicit thinking budget is deliberately NOT bounded by the caller's
     * own output size. TensionAnalyzer asking for 16,384 thinking on a
     * 6,000-token answer means "analyse hard, report briefly", and coercing it
     * down to 6,000 would quietly undo that.
     */
    @Test
    fun `an explicit thinking budget may exceed the requested output`() {
        val budget = resolve(callerMaxTokens = 6_000, callerThinkingBudget = 16_384)
        assertEquals(16_384, budget.thinkingBudget)
        assertEquals(22_384, budget.maxTokens)
    }

    /** Creative Studio passes 0 when its own thinking toggle is off. */
    @Test
    fun `a zero thinking budget means none`() {
        val budget = resolve(callerMaxTokens = 8_192, callerThinkingBudget = 0)
        assertNull(budget.thinkingBudget)
        assertEquals(8_192, budget.maxTokens)
    }

    // ----------------------------------------------------------- resolver path

    /** A model with a large window behaves exactly as it did before this change. */
    @Test
    fun `a large context window is unchanged from the previous behaviour`() {
        val budget = resolve(resolverMaxTokens = 100_800)
        assertEquals(100_800, budget.maxTokens)
        assertEquals(32_000, budget.thinkingBudget)
    }

    /**
     * The old code added `budget + 24_576` on this path, which for a 32K-context
     * model produced max_tokens=56,576 — larger than the model's entire window.
     * The floor is now subtracted from the ceiling instead of added to it.
     */
    @Test
    fun `the resolver ceiling is never inflated past itself`() {
        val budget = resolve(resolverMaxTokens = 24_614)
        assertEquals(24_614, budget.maxTokens, "must not exceed the resolver's own ceiling")
        val thinking = assertNotNull(budget.thinkingBudget)
        assertTrue(thinking < 24_614, "thinking must fit inside the ceiling")
        assertEquals(12_307, thinking)
    }

    @Test
    fun `the resolver path always leaves room to answer`() {
        for (ceiling in listOf(4_096, 12_000, 24_614, 40_000, 100_800, 158_400)) {
            val budget = resolve(resolverMaxTokens = ceiling)
            val total = assertNotNull(budget.maxTokens)
            assertEquals(ceiling, total)
            val thinking = budget.thinkingBudget ?: 0
            assertTrue(total - thinking >= minOf(TokenBudgetPolicy.OUTPUT_FLOOR_TOKENS, ceiling / 2),
                "ceiling=$ceiling left only ${total - thinking} for output")
        }
    }

    // --------------------------------------------------------------- ceiling

    @Test
    fun `a model output ceiling clamps the total and re-fits thinking`() {
        val budget = resolve(resolverMaxTokens = 158_400, outputCeiling = 64_000)
        assertEquals(64_000, budget.maxTokens)
        val thinking = assertNotNull(budget.thinkingBudget)
        assertTrue(thinking < 64_000)
    }

    @Test
    fun `a ceiling also clamps the additive caller path`() {
        val budget = resolve(callerMaxTokens = 28_672, outputCeiling = 32_000)
        assertEquals(32_000, budget.maxTokens)
        val thinking = assertNotNull(budget.thinkingBudget)
        assertTrue(thinking < 32_000, "thinking must still fit under the clamped total")
    }

    /** A ceiling so small that thinking cannot fit drops thinking rather than sending a rejected value. */
    @Test
    fun `a tiny ceiling drops thinking instead of sending a sub-floor budget`() {
        val budget = resolve(callerMaxTokens = 8_192, outputCeiling = 2_000)
        assertEquals(2_000, budget.maxTokens)
        val thinking = budget.thinkingBudget
        assertTrue(thinking == null || thinking >= TokenBudgetPolicy.MIN_THINKING_TOKENS)
        assertNull(thinking)
    }

    /** Null means "unknown", and an unknown model must behave exactly as it did before. */
    @Test
    fun `a null ceiling never clamps`() {
        val withCeiling = resolve(callerMaxTokens = 28_672, outputCeiling = null)
        assertEquals(57_344, withCeiling.maxTokens)
        assertEquals(28_672, withCeiling.thinkingBudget)
    }

    // ----------------------------------------------------------------- toggles

    /**
     * The global toggle is checked above the caller's own budget on purpose:
     * the four creative call sites hardcode their thinking budgets, and a user
     * who turns extended thinking off to stop paying for it would otherwise
     * still be billed by every one of them.
     */
    @Test
    fun `disabling reasoning removes thinking on every path`() {
        assertNull(resolve(callerMaxTokens = 8_192, reasoningEnabled = false).thinkingBudget)
        assertNull(resolve(resolverMaxTokens = 100_800, reasoningEnabled = false).thinkingBudget)
        assertNull(
            resolve(callerMaxTokens = 6_000, callerThinkingBudget = 16_384, reasoningEnabled = false).thinkingBudget,
            "an explicitly requested budget is still subject to the user's global toggle",
        )
        assertEquals(8_192, resolve(callerMaxTokens = 8_192, reasoningEnabled = false).maxTokens)
    }

    // ------------------------------------------------------------- degenerate

    /**
     * An unresolvable model id yields no ceiling from either source. A null
     * max_tokens is fine alone, but not beside a 32K thinking budget — Anthropic
     * would default max_tokens to 4096 and reject the pair.
     */
    @Test
    fun `with no numbers at all, a surviving thinking budget still gets a total`() {
        val budget = resolve()
        assertEquals(32_000, budget.thinkingBudget)
        val total = assertNotNull(budget.maxTokens)
        assertTrue(total > 32_000, "a thinking budget must never be sent without room to answer")
        assertEquals(32_000 + TokenBudgetPolicy.OUTPUT_FLOOR_TOKENS, total)
    }

    @Test
    fun `with no numbers and no reasoning, both fields are omitted`() {
        val budget = resolve(reasoningEnabled = false)
        assertNull(budget.maxTokens)
        assertNull(budget.thinkingBudget)
    }

    @Test
    fun `non-positive inputs are treated as absent`() {
        val zero = resolve(callerMaxTokens = 0, resolverMaxTokens = -1)
        assertEquals(32_000 + TokenBudgetPolicy.OUTPUT_FLOOR_TOKENS, zero.maxTokens)
        assertEquals(32_000, zero.thinkingBudget)
    }
}
