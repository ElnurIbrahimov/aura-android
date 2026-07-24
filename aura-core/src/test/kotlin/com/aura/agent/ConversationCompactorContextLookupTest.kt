package com.aura.agent

import com.aura.core.error.CrashLogger
import com.aura.providers.FinishReason
import com.aura.providers.ModelInfo
import com.aura.providers.Provider
import com.aura.providers.ProviderChunk
import com.aura.providers.ProviderRegistry
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for ConversationCompactor's context-window
 * lookup. Pre-fix, the compactor picked a 12K threshold based
 * on a hardcoded constant. Post-fix, it queries the provider's
 * model catalog for the actual context window — a 1M-context
 * model gets a 800K threshold (80%), not 12K.
 */
class ConversationCompactorContextLookupTest {

    @Test
    fun `compactor with 1M context model does not compact below 800K tokens`() = runTest {
        // Provider knows the model has 1M context. 80% = 800K.
        // A 200K-token conversation should NOT trigger compaction.
        val provider = mockProvider(
            listOf(ModelInfo("big-model", contextWindow = 1_000_000)),
        )
        val registry = mockRegistry(provider, "big-model")
        coEvery { registry.chat(any(), any(), any(), any()) } returns kotlinx.coroutines.flow.flowOf(
            ProviderChunk(text = "should not happen", finishReason = FinishReason.stop),
        )
        val compactor = ConversationCompactor(registry, mockk<CrashLogger>(relaxed = true))

        val conv = conversationWithTurns(2000, padding = "x".repeat(200)) // ~800K chars / 4 = 200K tokens

        val result = compactor.compactIfNeeded(conv, "test:big-model")
        // Compactor should NOT have run because 200K < 800K threshold.
        assertEquals(
            "1M-context model: compactor should not fire on 200K tokens",
            0, // summaryThroughTurn = 0 means no compaction happened
            result.summaryThroughTurn,
        )
    }

    @Test
    fun `compactor with unknown context window uses 32K default`() = runTest {
        // Provider returns null context window (doesn't know it).
        // Compactor falls back to 32K default.
        val provider = mockProvider(
            listOf(ModelInfo("unknown-ctx-model", contextWindow = null)),
        )
        val registry = mockRegistry(provider, "unknown-ctx-model")
        coEvery { registry.chat(any(), any(), any(), any()) } returns kotlinx.coroutines.flow.flowOf(
            ProviderChunk(text = "should not happen", finishReason = FinishReason.stop),
        )
        val compactor = ConversationCompactor(registry, mockk<CrashLogger>(relaxed = true))

        val conv = conversationWithTurns(50, padding = "x".repeat(200)) // ~20K chars / 4 = 5K tokens
        // 5K tokens is below 32K default — no compaction.

        val result = compactor.compactIfNeeded(conv, "test:unknown-ctx-model")
        assertEquals(0, result.summaryThroughTurn)
    }

    @Test
    fun `compactor with 8K context model compacts at 80 percent = 6400 tokens`() = runTest {
        // Small-context model: 80% of 8K = 6,400. Conversation
        // over 6,400 tokens should compact.
        val provider = mockProvider(
            listOf(ModelInfo("small-ctx-model", contextWindow = 8_000)),
        )
        val registry = mockRegistry(provider, "small-ctx-model")
        coEvery { registry.chat(any(), any(), any(), any()) } returns kotlinx.coroutines.flow.flowOf(
            ProviderChunk(text = "compacted", finishReason = FinishReason.stop),
        )
        val compactor = ConversationCompactor(registry, mockk<CrashLogger>(relaxed = true))

        val conv = conversationWithTurns(50, padding = "x".repeat(600)) // ~60K chars / 4 = 15K tokens

        val result = compactor.compactIfNeeded(conv, "test:small-ctx-model")
        // 15K tokens > 6.4K threshold — compaction should fire.
        assertEquals(26, result.summaryThroughTurn) // 50 - 24
    }

    // --- helpers ---

    private fun mockProvider(models: List<ModelInfo>): Provider = mockk<Provider>(relaxed = true).also {
        coEvery { it.listModels() } returns models.map { m -> m.name }
        coEvery { it.listModelsWithContext() } returns models
    }

    private fun mockRegistry(provider: Provider, modelName: String): ProviderRegistry =
        mockk<ProviderRegistry>(relaxed = true).also {
            coEvery { it.parse("test:$modelName") } returns Pair(provider, modelName)
        }

    private fun conversationWithTurns(count: Int, padding: String = "x".repeat(500)): Conversation =
        Conversation(
            turns = List(count) { index ->
                Turn(user = "user-$index: $padding", assistant = "assistant-$index: $padding")
            },
        )
}
