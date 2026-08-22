package com.aura.agent

import com.aura.providers.ModelInfo
import com.aura.providers.Provider
import com.aura.providers.ProviderRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ContextBudgetResolverTest {

    /**
     * Both of the next two used to assert 158_400 with the comment "no cap".
     * That number is 80% of Anthropic's 200K *input* window, and no Claude model
     * will return anywhere near it — Anthropic rejects an oversized `max_tokens`
     * outright, so every main-chat call to an Anthropic model was being sent a
     * value the API refuses. The context window was standing in for an output
     * cap that did not exist in this codebase until [ProviderOutputLimits].
     */
    @Test
    fun `a large context window is still capped by the model's output limit`() = runTest {
        val provider = mockk<Provider>(relaxed = true)
        every { provider.prefix } returns "anthropic"
        coEvery { provider.listModelsWithContext() } returns listOf(
            ModelInfo(name = "claude-sonnet-4", contextWindow = 200_000),
        )
        val registry = ProviderRegistry(mapOf("anthropic" to provider), mockk(relaxed = true), mockk(relaxed = true))
        val resolver = ContextBudgetResolver(registry)

        val budgets = resolver.budgetsFor("anthropic:claude-sonnet-4")
        // Context-derived would be (200_000 - 2_000) * 0.8 = 158_400; the
        // platform output ceiling is what actually governs.
        assertEquals(32_000, budgets.maxTokens)
        assertEquals(32_000, budgets.outputCeiling)
    }

    /** A live per-model value beats the static table, which holds platform minimums. */
    @Test
    fun `a live maxOutputTokens overrides the static table`() = runTest {
        val provider = mockk<Provider>(relaxed = true)
        every { provider.prefix } returns "anthropic"
        coEvery { provider.listModelsWithContext() } returns listOf(
            ModelInfo(name = "claude-sonnet-4", contextWindow = 200_000, maxOutputTokens = 64_000),
        )
        val registry = ProviderRegistry(mapOf("anthropic" to provider), mockk(relaxed = true), mockk(relaxed = true))
        val resolver = ContextBudgetResolver(registry)

        val budgets = resolver.budgetsFor("anthropic:claude-sonnet-4")
        assertEquals(64_000, budgets.outputCeiling)
        assertEquals(64_000, budgets.maxTokens)
    }

    /**
     * A provider with no known ceiling must behave exactly as it did before the
     * ceiling existed — null means "do not clamp", never "guess a default".
     */
    @Test
    fun `an unknown output ceiling leaves the context-derived budget alone`() = runTest {
        val provider = mockk<Provider>(relaxed = true)
        every { provider.prefix } returns "openai"
        coEvery { provider.listModelsWithContext() } returns listOf(
            ModelInfo(name = "gpt-4o", contextWindow = 128_000),
        )
        val registry = ProviderRegistry(mapOf("openai" to provider), mockk(relaxed = true), mockk(relaxed = true))
        val resolver = ContextBudgetResolver(registry)

        val budgets = resolver.budgetsFor("openai:gpt-4o")
        assertNull(budgets.outputCeiling)
        assertEquals(100_800, budgets.maxTokens)
    }

}