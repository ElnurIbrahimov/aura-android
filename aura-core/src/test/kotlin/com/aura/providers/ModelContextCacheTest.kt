package com.aura.providers

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The point of this cache is call count, so that is what these assert.
 *
 * `ContextBudgetResolver` probes the catalog on every step of every agentic
 * turn; for OllamaCloud that is an `/api/show` request per model, eight at a
 * time. A ten-step turn meant ten fan-outs.
 *
 * The TTLs themselves are not exercised here — doing so would mean either
 * waiting five minutes or injecting a clock, and the clock is not worth adding
 * for two constants. Hits, misses, negative caching and invalidation are.
 */
class ModelContextCacheTest {

    private fun provider(prefix: String, models: List<ModelInfo>): Provider =
        mockk<Provider>(relaxed = true).also {
            every { it.prefix } returns prefix
            coEvery { it.listModelsWithContext() } returns models
        }

    @Test
    fun `a repeated lookup for one provider probes the catalog once`() = runTest {
        val p = provider("ollama", listOf(ModelInfo("m", contextWindow = 8_000)))
        val cache = ModelContextCache()

        repeat(10) { cache.modelsFor(p) }

        coVerify(exactly = 1) { p.listModelsWithContext() }
    }

    @Test
    fun `the cached value is the real one, not an empty placeholder`() = runTest {
        val models = listOf(ModelInfo("m", contextWindow = 8_000, maxOutputTokens = 4_000))
        val p = provider("ollama", models)
        val cache = ModelContextCache()

        assertEquals(models, cache.modelsFor(p))
        assertEquals(models, cache.modelsFor(p), "the second read must return the same data, not a stub")
    }

    @Test
    fun `providers are cached independently`() = runTest {
        val a = provider("ollama", listOf(ModelInfo("a")))
        val b = provider("gemini", listOf(ModelInfo("b")))
        val cache = ModelContextCache()

        cache.modelsFor(a)
        cache.modelsFor(b)
        cache.modelsFor(a)
        cache.modelsFor(b)

        coVerify(exactly = 1) { a.listModelsWithContext() }
        coVerify(exactly = 1) { b.listModelsWithContext() }
        assertEquals(listOf(ModelInfo("a")), cache.modelsFor(a), "one provider's entry must not overwrite another's")
    }

    /**
     * The case a naive "only cache successes" policy misses, and the one where
     * the cache matters most: a provider that is down, rate-limited or
     * misconfigured would otherwise be re-probed on every step of the very turn
     * that is failing because of it.
     */
    @Test
    fun `a failing probe is cached rather than retried on every call`() = runTest {
        val p = mockk<Provider>(relaxed = true)
        every { p.prefix } returns "broken"
        coEvery { p.listModelsWithContext() } throws java.io.IOException("connection refused")
        val cache = ModelContextCache()

        repeat(5) { cache.modelsFor(p) }

        coVerify(exactly = 1) { p.listModelsWithContext() }
    }

    @Test
    fun `a failing probe returns empty rather than throwing`() = runTest {
        val p = mockk<Provider>(relaxed = true)
        every { p.prefix } returns "broken"
        coEvery { p.listModelsWithContext() } throws java.io.IOException("connection refused")

        val result = ModelContextCache().modelsFor(p)

        assertTrue(result.isEmpty(), "callers fall back to listModels(); a throw would break the whole turn")
    }

    @Test
    fun `invalidate forces the next lookup to re-probe`() = runTest {
        val p = provider("ollama", listOf(ModelInfo("m")))
        val cache = ModelContextCache()

        cache.modelsFor(p)
        cache.invalidate()
        cache.modelsFor(p)

        coVerify(exactly = 2) { p.listModelsWithContext() }
    }
}
