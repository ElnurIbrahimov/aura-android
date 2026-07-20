package com.aura.memory

import com.aura.agent.Brain
import com.aura.agent.BrainChunk
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryRewriterTest {

    private fun mockBrain(response: String): Brain {
        val brain = mockk<Brain>(relaxed = true)
        coEvery { brain.stream(any(), any(), any(), any()) } returns flowOf(BrainChunk.Text(response))
        return brain
    }

    @Test
    fun `blank query returns unchanged`() = runTest {
        val rewriter = QueryRewriter(mockBrain("rewritten"))
        assertEquals("", rewriter.rewrite("", "context", "model"))
    }

    @Test
    fun `self-contained query skips rewrite`() = runTest {
        val brain = mockk<Brain>(relaxed = true)
        coEvery { brain.stream(any(), any(), any(), any()) } returns flowOf(BrainChunk.Text("should not be called"))
        val rewriter = QueryRewriter(brain)
        // "What is Kotlin coroutines" has no deictic markers
        val result = rewriter.rewrite("What is Kotlin coroutines", "context", "model")
        assertEquals("What is Kotlin coroutines", result)
    }

    @Test
    fun `deictic phrase triggers rewrite`() = runTest {
        val brain = mockBrain("the database migration strategy from Tuesday")
        val rewriter = QueryRewriter(brain)
        val result = rewriter.rewrite("what about that thing we discussed", "user: let's talk about database migration\nassistant: sure", "model")
        assertEquals("the database migration strategy from Tuesday", result)
    }

    @Test
    fun `remind me triggers rewrite`() = runTest {
        val brain = mockBrain("what Helene said about the AI lab")
        val rewriter = QueryRewriter(brain)
        val result = rewriter.rewrite("remind me what she said", "user: who is Helene?\nassistant: my sister, she runs an AI lab", "model")
        assertEquals("what Helene said about the AI lab", result)
    }

    @Test
    fun `that thing triggers rewrite`() = runTest {
        val brain = mockBrain("the quantum computing breakthrough")
        val rewriter = QueryRewriter(brain)
        val result = rewriter.rewrite("tell me more about that thing", "user: quantum computing breakthrough\nassistant: amazing stuff", "model")
        assertEquals("the quantum computing breakthrough", result)
    }

    @Test
    fun `deictic starter triggers rewrite`() = runTest {
        val brain = mockBrain("the Kotlin coroutines discussion")
        val rewriter = QueryRewriter(brain)
        val result = rewriter.rewrite("it was interesting", "user: Kotlin coroutines\nassistant: great topic", "model")
        assertEquals("the Kotlin coroutines discussion", result)
    }

    @Test
    fun `model returns same query — returns original`() = runTest {
        val brain = mockBrain("what about that thing we discussed")
        val rewriter = QueryRewriter(brain)
        val result = rewriter.rewrite("what about that thing we discussed", "context", "model")
        assertEquals("what about that thing we discussed", result)
    }

    @Test
    fun `model returns blank — returns original`() = runTest {
        val brain = mockBrain("")
        val rewriter = QueryRewriter(brain)
        val result = rewriter.rewrite("what about that thing we discussed", "context", "model")
        assertEquals("what about that thing we discussed", result)
    }

    @Test
    fun `LLM error falls back to original`() = runTest {
        val brain = mockk<Brain>(relaxed = true)
        coEvery { brain.stream(any(), any(), any(), any()) } throws RuntimeException("API down")
        val rewriter = QueryRewriter(brain)
        val result = rewriter.rewrite("what about that thing we discussed", "context", "model")
        assertEquals("what about that thing we discussed", result)
    }

    @Test
    fun `timeout falls back to original`() = runTest {
        val brain = mockk<Brain>(relaxed = true)
        coEvery { brain.stream(any(), any(), any(), any()) } returns kotlinx.coroutines.flow.flow {
            kotlinx.coroutines.delay(30_000)
            emit(BrainChunk.Text("rewritten"))
        }
        val rewriter = QueryRewriter(brain)
        val result = rewriter.rewrite("what about that thing we discussed", "context", "model")
        assertEquals("what about that thing we discussed", result)
    }

    @Test
    fun `needsRewrite detects deictic phrases`() {
        // Can't test private method directly, but we can verify behavior
        val brain = mockk<Brain>(relaxed = true)
        val rewriter = QueryRewriter(brain)
        // These should NOT trigger rewrite (no deictic markers)
        runTest {
            coEvery { brain.stream(any(), any(), any(), any()) } returns flowOf(BrainChunk.Text("x"))
            // Self-contained queries return unchanged
            assertEquals("What is Kotlin", rewriter.rewrite("What is Kotlin", "ctx", "m"))
            assertEquals("How does X work", rewriter.rewrite("How does X work", "ctx", "m"))
            assertEquals("Write a poem about cats", rewriter.rewrite("Write a poem about cats", "ctx", "m"))
        }
    }
}