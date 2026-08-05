package com.aura.agent

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MCTS-lite ReasoningTree contract tests.
 *
 * The tree expands a hard question into distinct approach branches,
 * scores each, and commits to the best. These tests pin the parsing,
 * the value-scoring, and the guards (short messages skip entirely;
 * failures fall back to null).
 */
class ReasoningTreeTest {

    private fun tree(brain: Brain): ReasoningTree = ReasoningTree(brain)

    @Test
    fun `short messages are skipped`() = runTest {
        val t = tree(mockk<Brain>(relaxed = true))
        assertNull(t.bestApproach("hi", "model"), "short messages must not trigger the tree")
        assertNull(t.bestApproach("what time is it", "model"))
    }

    @Test
    fun `expand parses A-prefixed branches`() = runTest {
        val brain = mockk<Brain>(relaxed = true)
        coEvery { brain.stream(any(), any(), any(), any()) } returns flowOf(
            BrainChunk.Text("A1: Search the web for current prices and compare three vendors.\n"),
            BrainChunk.Text("A2: Query the knowledge graph for past purchases and recommend the best.\n"),
            BrainChunk.Text("A3: Ask clarifying questions first to narrow the scope.\n"),
        )
        val t = tree(brain)
        val branches = t.expand("Which laptop should I buy for development work", "model")
        assertNotNull(branches)
        assertEquals(3, branches!!.size)
        assertTrue(branches[0].startsWith("Search the web"))
        assertTrue(branches[2].startsWith("Ask clarifying"))
    }

    @Test
    fun `expand returns null when fewer than 2 branches`() = runTest {
        val brain = mockk<Brain>(relaxed = true)
        coEvery { brain.stream(any(), any(), any(), any()) } returns flowOf(
            BrainChunk.Text("A1: Only one approach here."),
        )
        val t = tree(brain)
        assertNull(t.expand("Some long question that needs multiple approaches", "model"))
    }

    @Test
    fun `expand returns null on model failure`() = runTest {
        val brain = mockk<Brain>(relaxed = true)
        coEvery { brain.stream(any(), any(), any(), any()) } throws RuntimeException("provider down")
        val t = tree(brain)
        assertNull(t.expand("Some long question that needs multiple approaches", "model"))
    }

    @Test
    fun `score parses a numeric value`() = runTest {
        val brain = mockk<Brain>(relaxed = true)
        coEvery { brain.stream(any(), any(), any(), any()) } returns flowOf(BrainChunk.Text("0.8"))
        val t = tree(brain)
        val s = t.score("Approach A", "Question here", "model")
        assertEquals(0.8, s, 0.001)
    }

    @Test
    fun `score returns 0 on unparseable output`() = runTest {
        val brain = mockk<Brain>(relaxed = true)
        coEvery { brain.stream(any(), any(), any(), any()) } returns flowOf(BrainChunk.Text("high confidence"))
        val t = tree(brain)
        assertEquals(0.0, t.score("Approach A", "Question here", "model"))
    }

    @Test
    fun `bestApproach picks the highest-scoring branch`() = runTest {
        val brain = mockk<Brain>(relaxed = true)
        // First call (expand) returns 3 branches; then score is called 3 times.
        coEvery { brain.stream(any(), any(), any(), any()) } returnsMany listOf(
            flowOf(
                BrainChunk.Text("A1: Approach one with web search and comparison.\n"),
                BrainChunk.Text("A2: Approach two using the knowledge graph for history.\n"),
                BrainChunk.Text("A3: Approach three asking clarifying questions first.\n"),
            ),
            flowOf(BrainChunk.Text("0.4")),
            flowOf(BrainChunk.Text("0.9")),
            flowOf(BrainChunk.Text("0.6")),
        )
        val t = tree(brain)
        val best = t.bestApproach(
            "I need a comprehensive comparison of the top three frameworks for building a mobile app in 2026",
            "model",
        )
        assertNotNull(best)
        assertTrue(best!!.startsWith("Approach two"), "should pick the 0.9-scored branch, got: $best")
    }

    @Test
    fun `bestApproach returns null when all scores are low`() = runTest {
        val brain = mockk<Brain>(relaxed = true)
        coEvery { brain.stream(any(), any(), any(), any()) } returnsMany listOf(
            flowOf(
                BrainChunk.Text("A1: First approach to consider for this problem.\n"),
                BrainChunk.Text("A2: Second approach to consider for this problem.\n"),
            ),
            flowOf(BrainChunk.Text("0.2")),
            flowOf(BrainChunk.Text("0.1")),
        )
        val t = tree(brain)
        assertNull(t.bestApproach(
            "I need a comprehensive comparison of the top three frameworks for building a mobile app in 2026",
            "model",
        ))
    }
}
