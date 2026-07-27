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
        // (128_000 - 2_000) * 0.8 = 100_800; capped at 32_768
        assertEquals(32_768, budget)
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
        assertEquals(32_768, budget)
    }

    @Test
    fun `returns null for unresolvable model id`() = runTest {
        val registry = ProviderRegistry(emptyMap(), mockk(relaxed = true))
        val resolver = ContextBudgetResolver(registry)

        assertNull(resolver.maxTokensFor("not_a_model"))
    }
}
