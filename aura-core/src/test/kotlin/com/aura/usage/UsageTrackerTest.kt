package com.aura.usage

import com.aura.providers.Usage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsageTrackerTest {

    @Test
    fun `reported provider tokens override character estimate`() {
        val tracker = UsageTracker()

        tracker.recordLlmCall(
            modelId = "openai:test-model",
            inputChars = 4_000,
            outputChars = 2_000,
            reportedUsage = Usage(promptTokens = 100, completionTokens = 50, totalTokens = 150),
        )

        val snapshot = tracker.snapshot.value
        assertEquals(100, snapshot.promptTokens)
        assertEquals(50, snapshot.completionTokens)
        assertEquals(1, snapshot.calls)
        assertFalse(snapshot.models.single().estimated)
    }

    // ---- the Gate A measurement -------------------------------------------

    @Test
    fun `the reported cache rate ignores models that never report caching`() {
        // The trap this exists to avoid. Anthropic caches perfectly while a
        // heavy Ollama user reports no cache fields at all; the plain aggregate
        // divides by BOTH models' prompt tokens and reads 9%, which would be
        // taken as "caching does not work" and get it turned off. The correct
        // reading is 90% on the model that supports it.
        val tracker = UsageTracker()
        tracker.recordLlmCall(
            modelId = "anthropic:claude-sonnet-4.6",
            inputChars = 0,
            outputChars = 0,
            reportedUsage = Usage(
                promptTokens = 10_000, completionTokens = 100, totalTokens = 10_100,
                cachedPromptTokens = 9_000,
            ),
        )
        tracker.recordLlmCall(
            modelId = "ollama:llama3",
            inputChars = 0,
            outputChars = 0,
            reportedUsage = Usage(promptTokens = 90_000, completionTokens = 100, totalTokens = 90_100),
        )

        val snapshot = tracker.snapshot.value
        assertEquals(0.9, snapshot.measuredCacheHitRate, 0.001)
        assertEquals(1, snapshot.cacheReportingModels.size)
        assertTrue(snapshot.cacheHitRate < 0.1, "the naive aggregate should be the misleading one")
    }

    @Test
    fun `a cache write alone counts as reporting`() {
        // The first call of any cached run is a write, not a read. Without this
        // the very run that proves caching is wired shows up as "not measured".
        val tracker = UsageTracker()
        tracker.recordLlmCall(
            modelId = "anthropic:claude-sonnet-4.6",
            inputChars = 0,
            outputChars = 0,
            reportedUsage = Usage(
                promptTokens = 8_000, completionTokens = 50, totalTokens = 8_050,
                cacheWritePromptTokens = 8_000,
            ),
        )

        val model = tracker.snapshot.value.models.single()
        assertTrue(model.reportsCaching, "a write-only first call read as 'never reported'")
        assertEquals(0.0, model.cacheHitRate, 0.001)
    }

    @Test
    fun `a model that reports nothing is not counted as a zero hit rate`() {
        val tracker = UsageTracker()
        tracker.recordLlmCall(
            modelId = "ollama:llama3",
            inputChars = 0,
            outputChars = 0,
            reportedUsage = Usage(promptTokens = 5_000, completionTokens = 10, totalTokens = 5_010),
        )

        val snapshot = tracker.snapshot.value
        assertFalse(snapshot.models.single().reportsCaching)
        assertTrue(snapshot.cacheReportingModels.isEmpty())
        // Zero because there is nothing to measure, and the UI hides the line
        // entirely in this state rather than showing a confident 0%.
        assertEquals(0.0, snapshot.measuredCacheHitRate, 0.001)
    }

    @Test
    fun `missing provider usage is estimated and accumulated by model`() {
        val tracker = UsageTracker()

        tracker.recordLlmCall("ollama:model-a", inputChars = 400, outputChars = 200)
        tracker.recordLlmCall("ollama:model-a", inputChars = 200, outputChars = 200)
        tracker.recordToolResult(800)

        val snapshot = tracker.snapshot.value
        assertEquals(150, snapshot.promptTokens)
        assertEquals(100, snapshot.completionTokens)
        assertEquals(2, snapshot.calls)
        assertEquals(800, snapshot.toolResultChars)
        assertTrue(snapshot.models.single().estimated)
    }

    @Test
    fun `reset clears persistent and session counters`() {
        val tracker = UsageTracker()
        tracker.recordLlmCall("gemini:model", 100, 100)

        tracker.reset()

        assertEquals(UsageSnapshot(), tracker.snapshot.value)
    }
}
