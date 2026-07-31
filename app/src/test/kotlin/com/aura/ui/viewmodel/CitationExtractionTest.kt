package com.aura.ui.viewmodel

import com.aura.tools.Citation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CitationExtractionTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `web_search citations extracted from numbered format`() {
        val result = """
            1. Example Page
               https://example.com/page1
               This is a snippet about the page.

            2. Another Result
               https://another.com/article
               Another snippet here.
        """.trimIndent()

        val citations = extractCitationsForTest("web_search", result)
        assertEquals(2, citations.size)
        assertEquals("Example Page", citations[0].title)
        assertEquals("https://example.com/page1", citations[0].url)
        assertEquals(1, citations[0].index)
        assertEquals(2, citations[1].index)
    }

    @Test
    fun `brave_search citations extracted from markdown format`() {
        val result = """
            - [Example Page](https://example.com/page1): A snippet about the page.
            - [Another Result](https://another.com/article): Another snippet.
        """.trimIndent()

        val citations = extractCitationsForTest("brave_search", result)
        assertEquals(2, citations.size)
        assertEquals("Example Page", citations[0].title)
        assertEquals("https://example.com/page1", citations[0].url)
    }

    @Test
    fun `deep_research citations extracted from JSON`() {
        val result = """{"citations":[{"title":"Source A","url":"https://a.com"},{"title":"Source B","url":"https://b.com"}]}"""
        val citations = extractCitationsForTest("deep_research", result)
        assertEquals(2, citations.size)
        assertEquals("Source A", citations[0].title)
        assertEquals("https://a.com", citations[0].url)
    }

    @Test
    fun `unknown tool returns empty citations`() {
        val result = "some tool result"
        val citations = extractCitationsForTest("unknown_tool", result)
        assertTrue(citations.isEmpty())
    }

    @Test
    fun `web_search with no results returns empty`() {
        val result = "No results found."
        val citations = extractCitationsForTest("web_search", result)
        assertTrue(citations.isEmpty())
    }

    private fun extractCitationsForTest(toolName: String, result: String): List<Citation> {
        // Mirror the production extraction logic for testing
        return when (toolName) {
            "deep_research" -> {
                runCatching {
                    val obj = json.parseToJsonElement(result).jsonObject
                    val arr = obj["citations"]?.jsonArray ?: return@runCatching emptyList<Citation>()
                    arr.mapIndexed { idx, el ->
                        val map = el.jsonObject
                        Citation(
                            index = idx + 1,
                            title = map["title"]?.jsonPrimitive?.content ?: "Source",
                            url = map["url"]?.jsonPrimitive?.content ?: "",
                        )
                    }
                }.getOrDefault(emptyList())
            }
            "brave_search", "tavily_search" -> {
                val regex = """- \[([^\]]+)\]\(([^)]+)\):""".toRegex()
                regex.findAll(result).mapIndexed { idx, match ->
                    Citation(index = idx + 1, title = match.groupValues[1], url = match.groupValues[2])
                }.toList()
            }
            "web_search" -> {
                val regex = """\d+\.\s+(.+?)\n\s+(https?://\S+)""".toRegex(RegexOption.DOT_MATCHES_ALL)
                regex.findAll(result).mapIndexed { idx, match ->
                    Citation(index = idx + 1, title = match.groupValues[1].trim(), url = match.groupValues[2].trim())
                }.toList()
            }
            else -> emptyList()
        }
    }
}