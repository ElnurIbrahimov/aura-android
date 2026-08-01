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

    @Test
    fun `returns 80 percent of advertised context window minus reserve`() = runTest {
        val provider = mockk<Provider>(relaxed = true)
        every { provider.prefix } returns "ollama"
        coEvery { provider.listModelsWithContext() } returns listOf(
            ModelInfo(name = "gemma3:4b", contextWindow = 128_000),
        )
        val registry = ProviderRegistry(mapOf("ollama" to provider), mockk(relaxed = true))
        val resolver = ContextBudgetResolver(registry)

        val budget = resolver.maxTokensFor("ollama:gemma3:4b")
        // (128_000 - 2_000) * 0.8 = 100_800 — no longer capped at 32K
        assertEquals(100_800, budget)
    }

    @Test
    fun `large context model gets full generation budget without 32K cap`() = runTest {
        val provider = mockk<Provider>(relaxed = true)
        every { provider.prefix } returns "anthropic"
        coEvery { provider.listModelsWithContext() } returns listOf(
            ModelInfo(name = "claude-sonnet-4", contextWindow = 200_000),
        )
        val registry = ProviderRegistry(mapOf("anthropic" to provider), mockk(relaxed = true))
        val resolver = ContextBudgetResolver(registry)

        val budget = resolver.maxTokensFor("anthropic:claude-sonnet-4")
        // (200_000 - 2_000) * 0.8 = 158_400 — no cap
        assertEquals(158_400, budget)
    }

    @Test
    fun `falls back to ProviderContextWindows when provider returns null context`() = runTest {
        val provider = mockk<Provider>(relaxed = true)
        every { provider.prefix } returns "anthropic"
        coEvery { provider.listModelsWithContext() } returns listOf(
            ModelInfo(name = "claude-sonnet-4-20250514", contextWindow = null),
        )
        val registry = ProviderRegistry(mapOf("anthropic" to provider), mockk(relaxed = true))
        val resolver = ContextBudgetResolver(registry)

        val budget = resolver.maxTokensFor("anthropic:claude-sonnet-4-20250514")
        // (200_000 - 2_000) * 0.8 = 158_400 — no cap
        assertEquals(158_400, budget)
    }

    @Test
    fun `openai provider uses 128K from context windows table`() = runTest {
        val provider = mockk<Provider>(relaxed = true)
        every { provider.prefix } returns "openai"
        coEvery { provider.listModelsWithContext() } returns listOf(
            ModelInfo(name = "gpt-4o", contextWindow = null),
        )
        val registry = ProviderRegistry(mapOf("openai" to provider), mockk(relaxed = true))
        val resolver = ContextBudgetResolver(registry)

        val budget = resolver.maxTokensFor("openai:gpt-4o")
        // (128_000 - 2_000) * 0.8 = 100_800
        assertEquals(100_800, budget)
    }

    @Test
    fun `unknown provider with no context info falls back to 32K default`() = runTest {
        val provider = mockk<Provider>(relaxed = true)
        every { provider.prefix } returns "mystery"
        coEvery { provider.listModelsWithContext() } returns emptyList()
        coEvery { provider.listModels() } returns listOf("some-model")
        val registry = ProviderRegistry(mapOf("mystery" to provider), mockk(relaxed = true))
        val resolver = ContextBudgetResolver(registry)

        val budget = resolver.maxTokensFor("mystery:some-model")
        // (32_768 - 2_000) * 0.8 = 24_614
        assertEquals(24_614, budget)
    }

    @Test
    fun `minimum budget is 1024 tokens`() = runTest {
        val provider = mockk<Provider>(relaxed = true)
        every { provider.prefix } returns "tiny"
        coEvery { provider.listModelsWithContext() } returns listOf(
            ModelInfo(name = "mini", contextWindow = 1_000),
        )
        val registry = ProviderRegistry(mapOf("tiny" to provider), mockk(relaxed = true))
        val resolver = ContextBudgetResolver(registry)

        val budget = resolver.maxTokensFor("tiny:mini")
        // (1_000 - 2_000) would be negative -> coerced to 1_024
        assertEquals(1_024, budget)
    }

    @Test
    fun `returns null for unresolvable model id`() = runTest {
        val registry = ProviderRegistry(emptyMap(), mockk(relaxed = true))
        val resolver = ContextBudgetResolver(registry)

        assertNull(resolver.maxTokensFor("not_a_model"))
    }
}