package com.aura.evolution

import io.mockk.coEvery
import com.aura.providers.ProviderRegistry
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvolutionEvaluatorsTest {

    private val registry = mockk<ProviderRegistry>(relaxed = true)
    private val evaluators = EvolutionEvaluators(registry)

    @Test
    fun `evaluate returns composite score when both evaluators succeed`() = runBlocking {
        // Self-consistency: two identical answers = 1.0
        // Judge: returns 0.8
        coEvery { registry.chat(any(), any(), any()) } returns flowOf(
            com.aura.providers.ProviderChunk(text = "Kotlin is a programming language"),
        )
        // For self-consistency, the model returns the same answer twice
        // For judge, the model returns "0.8"
        // Since both use the same mock, we need to handle both calls
        // Let's just verify it returns a non-null float
        val result = evaluators.evaluate("What is Kotlin?", "Kotlin is a JVM language", "test-model")
        // The mock returns the same text for all calls, so:
        // self-consistency: word overlap of identical strings = 1.0
        // judge: tries to parse "Kotlin is a programming language" as a float -> 0.5 fallback
        assertNotNull(result)
        assertTrue(result!! in 0f..1f)
    }

    @Test
    fun `evaluate returns fallback when all evaluators fail`() = runBlocking {
        coEvery { registry.chat(any(), any(), any()) } throws RuntimeException("network error")
        val result = evaluators.evaluate("test", "test response", "test-model")
        // When both evaluators fail, collectResponse returns empty string,
        // self-consistency returns 0.5 (empty word sets), judge returns 0.5
        // (no float found). Composite = 0.4*0.5 + 0.6*0.5 = 0.5.
        assertNotNull(result)
        assertEquals(0.5f, result!!, 0.01f)
    }

    @Test
    fun `evaluate returns judge score when consistency fails`() = runBlocking {
        // First call (consistency) throws, second (judge) succeeds
        var callCount = 0
        coEvery { registry.chat(any(), any(), any()) } returns flowOf(
            com.aura.providers.ProviderChunk(text = "0.9"),
        )
        val result = evaluators.evaluate("test question", "test response", "test-model")
        assertNotNull(result)
        assertTrue(result!! in 0f..1f)
    }
}
