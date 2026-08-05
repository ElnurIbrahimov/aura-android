package com.aura.tools

import com.aura.agent.Brain
import com.aura.agent.BrainChunk
import com.aura.agent.ToolContext
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ParallelResearchTool contract tests.
 *
 * The tool decomposes a question into research angles (with a keyword
 * fallback that needs no LLM), runs each angle as a subagent, and
 * synthesizes. These tests pin the deterministic pieces: keyword
 * decomposition for comparison questions, and the fallback when the
 * decomposition model call fails.
 */
class ParallelResearchToolTest {

    private fun tool(): ParallelResearchTool {
        val brain = mockk<Brain>(relaxed = true)
        val registry = mockk<com.aura.providers.ProviderRegistry>(relaxed = true)
        return ParallelResearchTool(
            brain = brain,
            subagentManager = mockk(relaxed = true),
            providerRegistry = registry,
            webSearchTool = mockk(relaxed = true),
            wikipediaSearchTool = mockk(relaxed = true),
            ddgInstantAnswerTool = mockk(relaxed = true),
        )
    }

    @Test
    fun `keywordAngles splits comparison questions into 3 angles`() {
        val t = tool()
        val angles = t.keywordAngles("iPhone vs Android which is better for privacy")
        assertEquals(3, angles.size)
        assertTrue(angles[0].contains("iPhone"), "angle 0 should be the first side, got: ${angles[0]}")
        assertTrue(angles[1].contains("Android"), "angle 1 should be the second side, got: ${angles[1]}")
    }

    @Test
    fun `keywordAngles falls back to generic angles for plain questions`() {
        val t = tool()
        val angles = t.keywordAngles("What is the best way to learn quantum computing")
        assertEquals(3, angles.size)
        assertTrue(angles.all { it.length in 8..150 })
    }

    @Test
    fun `decompose falls back to keywords when model call fails`() = runTest {
        val brain = mockk<Brain>(relaxed = true)
        val registry = mockk<com.aura.providers.ProviderRegistry>(relaxed = true)
        // Model stream returns empty (simulates a failed decomposition call)
        coEvery { brain.stream(any(), any(), any(), any()) } returns flowOf()
        val t = ParallelResearchTool(
            brain = brain,
            subagentManager = mockk(relaxed = true),
            providerRegistry = registry,
            webSearchTool = mockk(relaxed = true),
            wikipediaSearchTool = mockk(relaxed = true),
            ddgInstantAnswerTool = mockk(relaxed = true),
        )
        val angles = t.decompose("A vs B comparison", "model")
        assertEquals(3, angles.size, "decompose should fall back to keyword angles")
    }

    @Test
    fun `decompose parses model output into lines`() = runTest {
        val brain = mockk<Brain>(relaxed = true)
        val registry = mockk<com.aura.providers.ProviderRegistry>(relaxed = true)
        coEvery { brain.stream(any(), any(), any(), any()) } returns flowOf(
            BrainChunk.Text("History and background of the topic\n"),
            BrainChunk.Text("Current state and market landscape\n"),
            BrainChunk.Text("Future outlook and risks\n"),
        )
        val t = ParallelResearchTool(
            brain = brain,
            subagentManager = mockk(relaxed = true),
            providerRegistry = registry,
            webSearchTool = mockk(relaxed = true),
            wikipediaSearchTool = mockk(relaxed = true),
            ddgInstantAnswerTool = mockk(relaxed = true),
        )
        val angles = t.decompose("Tell me about renewable energy", "model")
        assertEquals(3, angles.size)
        assertTrue(angles[0].contains("History"))
        assertTrue(angles[2].contains("Future"))
    }

    @Test
    fun `tool rejects short questions`() = runTest {
        val t = tool()
        val result = t.tool.execute(
            com.aura.agent.ToolCall(id = "1", name = "parallel_research", arguments = mapOf("question" to "hi")),
            mockk<ToolContext>(relaxed = true),
        )
        assertTrue(result is com.aura.agent.ToolResult.Error)
        assertTrue(
            (result as com.aura.agent.ToolResult.Error).message.contains("too short"),
            "short questions should suggest web_search instead",
        )
    }
}
