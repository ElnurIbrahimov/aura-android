package com.aura.agent

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflectionEngineTest {

    private val brain = mockk<Brain>(relaxed = true)
    private val engine = ReflectionEngine(brain)

    @Test
    fun `reflect returns reflection text on success`() = runBlocking {
        coEvery { brain.stream(any(), any(), any(), any()) } returns flowOf(
            BrainChunk.Text("Try rephrasing the search query next time."),
        )
        val result = engine.reflect(
            userMessage = "search for quantum computing papers",
            toolErrors = listOf("web_search" to "rate limited"),
            maxSteps = 10,
            model = "ollama:gemma:cloud",
        )
        assertEquals("Try rephrasing the search query next time.", result)
    }

    @Test
    fun `reflect returns null when brain returns empty`() = runBlocking {
        coEvery { brain.stream(any(), any(), any(), any()) } returns flowOf()
        val result = engine.reflect(
            userMessage = "test",
            toolErrors = emptyList(),
            maxSteps = 5,
            model = "test-model",
        )
        assertNull(result)
    }

    @Test
    fun `reflect returns null on exception`() = runBlocking {
        coEvery { brain.stream(any(), any(), any(), any()) } throws RuntimeException("network error")
        val result = engine.reflect(
            userMessage = "test",
            toolErrors = listOf("tool" to "error"),
            maxSteps = 10,
            model = "test-model",
        )
        assertNull(result)
    }

    @Test
    fun `reflect handles no tool errors gracefully`() = runBlocking {
        coEvery { brain.stream(any(), any(), any(), any()) } returns flowOf(
            BrainChunk.Text("The task needs more steps to complete."),
        )
        val result = engine.reflect(
            userMessage = "complex multi-step research task",
            toolErrors = emptyList(),
            maxSteps = 10,
            model = "test-model",
        )
        assertTrue(result!!.contains("steps"))
    }
}
