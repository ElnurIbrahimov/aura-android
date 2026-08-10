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
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertFailsWith

class MemoryRerankerTest {

    private fun mem(id: String, content: String) = MemoryEntity(
        id = id,
        content = content,
        source = "user",
        category = "fact",
    )

    private fun mockBrain(vararg responses: String): Brain {
        val brain = mockk<Brain>(relaxed = true)
        val chunks = responses.map { listOf(BrainChunk.Text(it)) }
        coEvery { brain.stream(any(), any(), any(), any()) } returnsMany chunks.map { flowOf(*it.toTypedArray()) }
        return brain
    }

    @Test
    fun `empty candidates returns empty`() = runTest {
        val reranker = MemoryReranker(mockBrain("0.5"))
        val result = reranker.rerank("test", emptyList(), "model", 5)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `single candidate returns as-is`() = runTest {
        val reranker = MemoryReranker(mockBrain("0.9"))
        val result = reranker.rerank("test", listOf(mem("m1", "hello")), "model", 5)
        assertEquals(1, result.size)
        assertEquals("m1", result[0].id)
    }

    @Test
    fun `reranks by LLM score descending`() = runTest {
        // 4 candidates, scores: 0.2, 0.9, 0.5, 0.1
        // Expected order: m2 (0.9), m3 (0.5), m1 (0.2), m4 (0.1)
        val brain = mockBrain("0.2\n0.9\n0.5\n0.1")
        val reranker = MemoryReranker(brain)
        val candidates = listOf(
            mem("m1", "first memory"),
            mem("m2", "second memory"),
            mem("m3", "third memory"),
            mem("m4", "fourth memory"),
        )
        val result = reranker.rerank("query", candidates, "model", topK = 4)
        assertEquals(4, result.size)
        assertEquals("m2", result[0].id) // highest score 0.9
        assertEquals("m3", result[1].id) // 0.5
        assertEquals("m1", result[2].id) // 0.2
        assertEquals("m4", result[3].id) // 0.1
    }

    @Test
    fun `topK limits results`() = runTest {
        val brain = mockBrain("0.9\n0.8\n0.7\n0.6")
        val reranker = MemoryReranker(brain)
        val candidates = (1..4).map { mem("m$it", "memory $it") }
        val result = reranker.rerank("query", candidates, "model", topK = 2)
        assertEquals(2, result.size)
    }

    @Test
    fun `falls back to original order on error`() = runTest {
        val brain = mockk<Brain>(relaxed = true)
        coEvery { brain.stream(any(), any(), any(), any()) } throws RuntimeException("API down")
        val reranker = MemoryReranker(brain)
        val candidates = listOf(
            mem("m1", "first"),
            mem("m2", "second"),
            mem("m3", "third"),
        )
        val result = reranker.rerank("query", candidates, "model", topK = 3)
        // Should return in original order
        assertEquals(3, result.size)
        assertEquals("m1", result[0].id)
        assertEquals("m2", result[1].id)
        assertEquals("m3", result[2].id)
    }

    @Test
    fun `caller cancellation is not swallowed by the fallback`() = runTest {
        // The fallback exists for provider failures, not for the caller giving
        // up. `catch (e: Exception)` caught CancellationException too — because
        // it IS an Exception — so a cancelled recall was reported as a
        // successful rerank in RRF order, and the model call kept running for a
        // result nobody was waiting for. This repo has fixed the same defect
        // twice before in other subsystems.
        val brain = mockk<Brain>(relaxed = true)
        coEvery { brain.stream(any(), any(), any(), any()) } throws CancellationException("caller went away")
        val reranker = MemoryReranker(brain)
        val candidates = listOf(mem("m1", "first"), mem("m2", "second"))

        assertFailsWith<CancellationException> {
            reranker.rerank("query", candidates, "model", topK = 2)
        }
    }

    @Test
    fun `batches more than 4 candidates into multiple calls`() = runTest {
        // 6 candidates = 2 batches of 4 + 2
        val brain = mockBrain(
            "0.5\n0.9\n0.3\n0.7", // batch 1 (4 candidates)
            "0.8\n0.2",           // batch 2 (2 candidates)
        )
        val reranker = MemoryReranker(brain)
        val candidates = (1..6).map { mem("m$it", "memory $it") }
        val result = reranker.rerank("query", candidates, "model", topK = 6)
        assertEquals(6, result.size)
        // m2 has 0.9 (highest), m5 has 0.8 (second)
        assertEquals("m2", result[0].id)
        assertEquals("m5", result[1].id)
    }

    @Test
    fun `out-of-order explicit indices are mapped to the right candidate`() = runTest {
        // P1 MEMORY B4 regression: pre-fix, the parser was
        // strictly positional. If the LLM returned
        // "Memory 4: 0.9\nMemory 1: 0.5\n..." out of
        // order, the score for candidate[3] would be
        // assigned to candidates[0]. Now the parser
        // extracts the explicit index and uses it to
        // map scores to the correct candidate.
        val brain = mockBrain("Memory 4: 0.9\nMemory 1: 0.1\nMemory 2: 0.5\nMemory 3: 0.3")
        val reranker = MemoryReranker(brain)
        val candidates = listOf(
            mem("m1", "first"),
            mem("m2", "second"),
            mem("m3", "third"),
            mem("m4", "fourth"),
        )
        val result = reranker.rerank("query", candidates, "model", topK = 4)
        // m4 has the highest score (0.9), should be first
        assertEquals("m4", result[0].id)
        assertEquals("m2", result[1].id) // 0.5
        assertEquals("m3", result[2].id) // 0.3
        assertEquals("m1", result[3].id) // 0.1
    }

    @Test
    fun `mismatched line count defaults missing candidates to neutral`() = runTest {
        // P1 MEMORY B4: when the LLM under-responds (3 lines
        // for 4 candidates), the missing candidate gets the
        // neutral 0.5f score instead of being dropped.
        val brain = mockBrain("0.9\n0.8\n0.7")
        val reranker = MemoryReranker(brain)
        val candidates = (1..4).map { mem("m$it", "memory $it") }
        val result = reranker.rerank("query", candidates, "model", topK = 4)
        // m1 (0.9) first, m2 (0.8) second, m3 (0.7) third,
        // m4 (default 0.5) last
        assertEquals("m1", result[0].id)
        assertEquals("m2", result[1].id)
        assertEquals("m3", result[2].id)
        assertEquals("m4", result[3].id)
    }

    @Test
    fun `handles malformed LLM response gracefully`() = runTest {
        val brain = mockBrain("I think the scores are:\n0.8\nnot a number\n0.3")
        val reranker = MemoryReranker(brain)
        val candidates = (1..4).map { mem("m$it", "memory $it") }
        val result = reranker.rerank("query", candidates, "model", topK = 4)
        assertEquals(4, result.size)
        // Should not crash, m1 (0.8) should be first
        assertEquals("m1", result[0].id)
    }

    @Test
    fun `timeout falls back to original order`() = runTest {
        val brain = mockk<Brain>(relaxed = true)
        // Simulate a hanging response by returning an infinite flow
        coEvery { brain.stream(any(), any(), any(), any()) } returns kotlinx.coroutines.flow.flow {
            kotlinx.coroutines.delay(30_000) // longer than timeout
            emit(BrainChunk.Text("0.5"))
        }
        val reranker = MemoryReranker(brain)
        val candidates = listOf(
            mem("m1", "first"),
            mem("m2", "second"),
        )
        val result = reranker.rerank("query", candidates, "model", topK = 2)
        // Should return in original order due to timeout
        assertEquals(2, result.size)
        assertEquals("m1", result[0].id)
        assertEquals("m2", result[1].id)
    }
}