package com.aura.tools

import com.aura.agent.ToolCall
import com.aura.agent.ToolContext
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.providers.ProviderKeys
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for [WebSearchTool] — argument validation, tool definition,
 * risk classification, and the M5 deterministic backend dispatch
 * (Tavily → Brave → DuckDuckGo).
 */
class WebSearchToolTest {

    private val client = OkHttpClient.Builder().build()
    private val providerKeys = mockk<ProviderKeys>()
    private val tavilyTool = mockk<TavilySearchTool>()
    private val braveTool = mockk<BraveSearchTool>()
    private lateinit var tool: WebSearchTool

    @Before
    fun setUp() {
        tool = WebSearchTool(client, providerKeys, tavilyTool, braveTool)
        coEvery { providerKeys.keyForAwaiting("tavily") } returns null
        coEvery { providerKeys.keyForAwaiting("brave") } returns null
    }

    private fun exec(args: Map<String, Any?>): ToolResult =
        runBlocking {
            tool.tool.execute(
                ToolCall(id = "", name = "web_search", arguments = args),
                ToolContext(conversationId = "test"),
            )
        }

    @Test
    fun `missing query returns bad_args error`() {
        val result = exec(emptyMap())
        assertTrue(result is ToolResult.Error)
        assertEquals("bad_args", (result as ToolResult.Error).code)
    }

    @Test
    fun `query with null value returns bad_args error`() {
        val result = exec(mapOf("query" to null))
        assertTrue(result is ToolResult.Error)
    }

    @Test
    fun `tool definition has correct name and required query`() {
        val def = tool.definition()
        assertEquals("web_search", def.name)
        assertEquals("query", def.parameters.required.first())
    }

    @Test
    fun `tool risk is READ_ONLY`() {
        assertEquals(ToolRisk.READ_ONLY, tool.tool.risk)
    }

    // --- M5: deterministic backend dispatch ---

    @Test
    fun `tavily is used when tavily key is configured`() = runBlocking {
        coEvery { providerKeys.keyForAwaiting("tavily") } returns "tv-key"
        coEvery { tavilyTool.searchStructured("kotlin", 5, "tv-key") } returns listOf(
            WebSearchResult("Kotlin docs", "https://kotlinlang.org", "Official docs")
        )

        val results = tool.search("kotlin", 5)

        assertEquals(1, results.size)
        assertEquals("Kotlin docs", results[0].title)
        coVerify(exactly = 0) { braveTool.searchStructured(any(), any(), any()) }
    }

    @Test
    fun `brave is used when only brave key is configured`() = runBlocking {
        coEvery { providerKeys.keyForAwaiting("brave") } returns "br-key"
        coEvery { braveTool.searchStructured("kotlin", 5, "br-key") } returns listOf(
            WebSearchResult("Brave hit", "https://example.com", "snippet")
        )

        val results = tool.search("kotlin", 5)

        assertEquals("Brave hit", results[0].title)
        coVerify(exactly = 0) { tavilyTool.searchStructured(any(), any(), any()) }
    }

    @Test
    fun `exec dispatches to tavily backend when configured`() = runBlocking {
        coEvery { providerKeys.keyForAwaiting("tavily") } returns "tv-key"
        coEvery { tavilyTool.searchStructured("kotlin", 5, "tv-key") } returns listOf(
            WebSearchResult("Kotlin docs", "https://kotlinlang.org", "Official docs")
        )

        val result = exec(mapOf("query" to "kotlin"))

        assertIs<ToolResult.Ok>(result)
        assertTrue(result.output.contains("Kotlin docs"))
        assertTrue(result.output.contains("https://kotlinlang.org"))
    }
}
