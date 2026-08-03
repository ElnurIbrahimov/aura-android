package com.aura.tools

import com.aura.agent.Tool
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.providers.ProviderKeys
import com.aura.providers.ToolDefinition
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/** Structured search hit shared by all web-search backends. */
data class WebSearchResult(val title: String, val url: String, val snippet: String)

/**
 * Consolidated web search tool (M5 from 2026-08-03 audit).
 *
 * The app previously exposed four overlapping search tools
 * (`web_search` DDG, `brave_search`, `tavily_search`,
 * `web_search_capability`) and let the LLM guess which one fit.
 * This tool is the single LLM-visible `web_search`: it picks the best
 * configured backend deterministically — Tavily, then Brave, then the
 * free DuckDuckGo fallback — so behavior no longer depends on the
 * model's tool-selection whims.
 *
 * The individual Tavily/Brave tools remain registered in the
 * ToolRegistry (direct calls and tests keep working) but the agentic
 * loop's filter hides them from the model's tool list.
 */
@Singleton
class WebSearchTool @Inject constructor(
    private val httpClient: OkHttpClient,
    private val providerKeys: ProviderKeys,
    private val tavilySearchTool: TavilySearchTool,
    private val braveSearchTool: BraveSearchTool,
) {
    private val ddg by lazy { DuckDuckGoSearch(httpClient) }

    fun definition() = ToolDefinition(
        name = "web_search",
        description = "Search the web and return top results as title/URL/snippet. " +
            "Automatically uses the best configured backend (Tavily, Brave, or DuckDuckGo).",
        parameters = ToolParameters(
            properties = mapOf(
                "query" to ToolProperty(type = "string", description = "Search query"),
                "max_results" to ToolProperty(type = "integer", description = "Number of results (default 5, max 10)"),
            ),
            required = listOf("query"),
        ),
    )

    val tool = Tool(
        name = "web_search",
        description = definition().description,
        risk = ToolRisk.READ_ONLY,
        parameters = definition().parameters,
        execute = { call, ctx ->
            val query = call.arguments["query"] as? String
                ?: return@Tool ToolResult.Error("missing 'query' argument", "bad_args")
            val maxResults = (call.arguments["max_results"] as? Int ?: 5).coerceIn(1, 10)
            try {
                val results = search(query, maxResults)
                ToolResult.Ok(formatResults(results))
            } catch (e: Exception) {
                ToolResult.Error("search failed: ${e.message}", "http_error")
            }
        },
    category = "web")

    /**
     * Deterministic backend selection: Tavily (richest results, AI
     * answer) when its key is configured; Brave next; DuckDuckGo HTML
     * as the always-available free fallback.
     */
    internal suspend fun search(query: String, maxResults: Int): List<WebSearchResult> {
        val tavilyKey = providerKeys.keyForAwaiting("tavily")
        if (!tavilyKey.isNullOrBlank()) {
            return tavilySearchTool.searchStructured(query, maxResults, tavilyKey)
        }
        val braveKey = providerKeys.keyForAwaiting("brave")
        if (!braveKey.isNullOrBlank()) {
            return braveSearchTool.searchStructured(query, maxResults, braveKey)
        }
        return ddg.search(query, maxResults).map { WebSearchResult(it.title, it.url, it.snippet) }
    }

    private fun formatResults(results: List<WebSearchResult>): String =
        if (results.isEmpty()) "No results found."
        else results.mapIndexed { i, r ->
            val urlPart = if (r.url.isNotBlank()) "\n   ${r.url}" else ""
            "${i + 1}. ${r.title}$urlPart\n   ${r.snippet}"
        }.joinToString("\n\n")
}
