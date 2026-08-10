package com.aura.tools

import com.aura.agent.ToolResult
import com.aura.providers.CheapModelResolver
import com.aura.providers.ChatOptions
import com.aura.providers.FinishReason
import com.aura.providers.ProviderChunk
import com.aura.providers.ProviderMessage
import com.aura.providers.ProviderRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [KnowledgeGraphTool].
 *
 * Mocks ProviderRegistry so no real LLM call is made.
 *
 * These tests used to stub `chat("default", ...)`, which is how the production
 * bug survived: `ProviderRegistry.parse` requires a non-blank `provider:model`
 * pair, so the real registry always threw on `"default"` and the catch-all
 * fallback was the only path that ever ran — but a mockk answers `"default"`
 * happily, so the suite asserted the broken behaviour and reported green. The
 * model id is now supplied by [CheapModelResolver], and
 * `never asks the registry for the literal string default` pins the contract
 * that a mock cannot silently absorb again.
 */
class KnowledgeGraphToolTest {

    private companion object {
        const val CHEAP_MODEL = "anthropic:claude-haiku-4-5-20251001"
    }

    /** A resolver that hands back [model], or null to force the fallback path. */
    private fun resolver(model: String? = CHEAP_MODEL): CheapModelResolver = mockk {
        coEvery { resolve(any(), any()) } returns model
    }

    /** A registry that answers any well-formed model id with [response]. */
    private fun registryReturning(response: String): ProviderRegistry = mockk {
        coEvery {
            chat(CHEAP_MODEL, any<List<ProviderMessage>>(), any<ChatOptions>())
        } returns flowOf(
            ProviderChunk(text = response),
            ProviderChunk(finishReason = FinishReason.stop),
        )
    }

    @Test
    fun `extracts nodes and edges from text`() = runTest {
        val llmResponse = """
            {
              "nodes": [
                {"label": "Kotlin", "type": "skill"},
                {"label": "Android", "type": "project"}
              ],
              "edges": [
                {"type": "used_for", "source_label": "Kotlin", "target_label": "Android", "weight": 0.9}
              ]
            }
        """.trimIndent()

        val tool = KnowledgeGraphTool(registryReturning(llmResponse), resolver()).tool
        val result = tool.execute(
            call("knowledge_graph_extract", "text" to "Kotlin is used for Android development."),
            ctx(),
        )

        assertTrue("expected Ok, got $result") { result is ToolResult.Ok }
        val json = (result as ToolResult.Ok).output
        assertTrue(json.contains("\"nodes\""), "should contain nodes: $json")
        assertTrue(json.contains("\"edges\""), "should contain edges: $json")
        assertTrue(json.contains("Kotlin"), "should contain node label Kotlin: $json")
        assertTrue(json.contains("Android"), "should contain node label Android: $json")
        assertTrue(json.contains("\"type\":\"skill\""), "should have skill type: $json")
        assertTrue(json.contains("\"type\":\"project\""), "should have project type: $json")
    }

    @Test
    fun `missing text returns error`() = runTest {
        val tool = KnowledgeGraphTool(mockk(), resolver()).tool
        val result = tool.execute(call("knowledge_graph_extract"), ctx())

        assertTrue("expected Error, got $result") { result is ToolResult.Error }
        assertEquals("bad_args", (result as ToolResult.Error).code)
    }

    @Test
    fun `LLM returns empty JSON when no entities found`() = runTest {
        val tool = KnowledgeGraphTool(registryReturning("""{"nodes":[],"edges":[]}"""), resolver()).tool
        val result = tool.execute(
            call("knowledge_graph_extract", "text" to "Just some random text."),
            ctx(),
        )

        assertTrue("expected Ok, got $result") { result is ToolResult.Ok }
        val json = (result as ToolResult.Ok).output
        assertTrue(json.contains("\"nodes\":[]"), "should have empty nodes: $json")
        assertTrue(json.contains("\"edges\":[]"), "should have empty edges: $json")
    }

    @Test
    fun `LLM response with unknown node type maps to unknown`() = runTest {
        val llmResponse = """
            {
              "nodes": [
                {"label": "MysteryThing", "type": "alien_species"}
              ],
              "edges": []
            }
        """.trimIndent()

        val tool = KnowledgeGraphTool(registryReturning(llmResponse), resolver()).tool
        val result = tool.execute(
            call("knowledge_graph_extract", "text" to "Some alien species."),
            ctx(),
        )

        assertTrue("expected Ok, got $result") { result is ToolResult.Ok }
        val json = (result as ToolResult.Ok).output
        assertTrue(json.contains("MysteryThing"), "should contain the node label: $json")
        assertTrue(json.contains("\"type\":\"unknown\""), "unknown type should map to 'unknown': $json")
    }

    @Test
    fun `malformed LLM response falls back to empty`() = runTest {
        val tool = KnowledgeGraphTool(registryReturning("This is not JSON at all."), resolver()).tool
        val result = tool.execute(
            call("knowledge_graph_extract", "text" to "Something."),
            ctx(),
        )

        assertTrue("expected Ok, got $result") { result is ToolResult.Ok }
        val json = (result as ToolResult.Ok).output
        assertEquals("""{"nodes":[],"edges":[]}""", json)
    }

    // -----------------------------------------------------------------
    // Model resolution
    // -----------------------------------------------------------------

    @Test
    fun `never asks the registry for the literal string default`() = runTest {
        // The regression guard. "default" is not a valid provider:model pair,
        // so ProviderRegistry.parse always threw on it; only a mock ever made
        // it look like it worked.
        val registry = registryReturning("""{"nodes":[],"edges":[]}""")
        val tool = KnowledgeGraphTool(registry, resolver()).tool

        tool.execute(call("knowledge_graph_extract", "text" to "Something."), ctx())

        coVerify(exactly = 0) {
            registry.chat("default", any<List<ProviderMessage>>(), any<ChatOptions>())
        }
        coVerify(exactly = 1) {
            registry.chat(CHEAP_MODEL, any<List<ProviderMessage>>(), any<ChatOptions>())
        }
    }

    @Test
    fun `uses the model the cheap resolver picks`() = runTest {
        val registry = mockk<ProviderRegistry> {
            coEvery {
                chat("groq:llama-3.3-70b", any<List<ProviderMessage>>(), any<ChatOptions>())
            } returns flowOf(
                ProviderChunk(text = """{"nodes":[{"label":"Picked","type":"concept"}],"edges":[]}"""),
                ProviderChunk(finishReason = FinishReason.stop),
            )
        }
        val tool = KnowledgeGraphTool(registry, resolver("groq:llama-3.3-70b")).tool

        val result = tool.execute(call("knowledge_graph_extract", "text" to "x"), ctx())

        assertTrue("expected Ok, got $result") { result is ToolResult.Ok }
        assertTrue((result as ToolResult.Ok).output.contains("Picked"))
    }

    @Test
    fun `falls back to first configured provider when the resolver finds nothing`() = runTest {
        // The resolver returns null only when no catalog could be listed at all.
        // The walk over configured providers stays as a genuine last resort, and
        // still must not hardcode a provider:model — that was the 2026-07-07 bug
        // where ollama:deepseek-v4-pro was baked in and crashed users with no
        // Ollama key.
        val llmResponse = """{"nodes":[{"label":"Fallback","type":"concept"}],"edges":[]}"""

        val mockProvider = mockk<com.aura.providers.Provider> {
            every { prefix } returns "anthropic"
            coEvery { listModels() } returns listOf("claude-sonnet-4.6")
        }
        val mockRegistry = mockk<ProviderRegistry> {
            every { configured() } returns listOf(mockProvider)
            coEvery {
                chat("anthropic:claude-sonnet-4.6", any<List<ProviderMessage>>(), any<ChatOptions>())
            } returns flowOf(
                ProviderChunk(text = llmResponse),
                ProviderChunk(finishReason = FinishReason.stop),
            )
        }

        val tool = KnowledgeGraphTool(mockRegistry, resolver(model = null)).tool
        val result = tool.execute(
            call("knowledge_graph_extract", "text" to "Something."),
            ctx(),
        )

        assertTrue("expected Ok, got $result") { result is ToolResult.Ok }
        assertTrue((result as ToolResult.Ok).output.contains("Fallback"))
    }

    @Test
    fun `works when no resolver is injected at all`() = runTest {
        // cheapModelResolver is nullable-defaulted so the 9 existing positional
        // construction sites keep compiling; that path must still resolve.
        val llmResponse = """{"nodes":[{"label":"NoResolver","type":"concept"}],"edges":[]}"""
        val mockProvider = mockk<com.aura.providers.Provider> {
            every { prefix } returns "openai"
            coEvery { listModels() } returns listOf("gpt-5-mini")
        }
        val mockRegistry = mockk<ProviderRegistry> {
            every { configured() } returns listOf(mockProvider)
            coEvery {
                chat("openai:gpt-5-mini", any<List<ProviderMessage>>(), any<ChatOptions>())
            } returns flowOf(
                ProviderChunk(text = llmResponse),
                ProviderChunk(finishReason = FinishReason.stop),
            )
        }

        val result = KnowledgeGraphTool(mockRegistry).tool
            .execute(call("knowledge_graph_extract", "text" to "Something."), ctx())

        assertTrue("expected Ok, got $result") { result is ToolResult.Ok }
        assertTrue((result as ToolResult.Ok).output.contains("NoResolver"))
    }

    @Test
    fun `no configured providers returns error instead of crashing`() = runTest {
        // When even the fallback path finds no configured providers,
        // the tool should fail with a clear error rather than crash
        // with a NullPointerException or generic IllegalStateException.
        // This pins the error-message contract callers see.
        val mockRegistry = mockk<ProviderRegistry> {
            every { configured() } returns emptyList()
        }
        val tool = KnowledgeGraphTool(mockRegistry, resolver(model = null)).tool
        val result = tool.execute(
            call("knowledge_graph_extract", "text" to "Hello."),
            ctx(),
        )
        assertTrue("expected Error, got $result") { result is ToolResult.Error }
        assertEquals("extraction_error", (result as ToolResult.Error).code)
        assertTrue(
            "error message should mention the missing-provider condition",
        ) { result.message.contains("No configured providers", ignoreCase = true) }
    }

    @Test
    fun `tool definition has correct metadata`() {
        val tool = KnowledgeGraphTool(mockk()).tool
        assertEquals("knowledge_graph_extract", tool.name)
        assertEquals(com.aura.agent.ToolRisk.REMOTE_COST, tool.risk)
        assertTrue(tool.parameters.properties.containsKey("text"))
        assertTrue(tool.parameters.required.contains("text"))
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private fun call(name: String, vararg pairs: Pair<String, Any?>): com.aura.agent.ToolCall =
        com.aura.agent.ToolCall(id = "tc1", name = name, arguments = mapOf(*pairs))

    private fun ctx() = com.aura.agent.ToolContext(conversationId = "conv-1")
}
