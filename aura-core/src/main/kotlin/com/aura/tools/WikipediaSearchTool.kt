package com.aura.tools

import com.aura.agent.Tool
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton
import com.aura.providers.ToolDefinition
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty

/**
 * Search Wikipedia's API — completely free, no key, no rate limit.
 * Uses the MediaWiki Action API (action=query&list=search).
 */
@Singleton
class WikipediaSearchTool @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun definition() = ToolDefinition(
        name = "wikipedia_search",
        description = "Search Wikipedia and return article titles + snippets. " +
            "Free, no API key needed. Best for factual and encyclopedic queries.",
        parameters = ToolParameters(
            properties = mapOf(
                "query" to ToolProperty(type = "string", description = "Search query"),
                "max_results" to ToolProperty(type = "integer", description = "Number of results (default 5, max 10)"),
            ),
            required = listOf("query"),
        ),
    )

    val tool = Tool(
        name = "wikipedia_search",
        description = definition().description,
        risk = ToolRisk.READ_ONLY,
        parameters = definition().parameters,
        execute = { call, _ ->
            val query = call.arguments["query"] as? String
                ?: return@Tool ToolResult.Error("missing 'query' argument", "bad_args")
            val maxResults = (call.arguments["max_results"] as? Int ?: 5).coerceIn(1, 10)
            try {
                val results = search(query, maxResults)
                ToolResult.Ok(formatResults(results))
            } catch (e: Exception) {
                ToolResult.Error("Wikipedia search failed: ${e.message}", "http_error")
            }
        },
        category = "web",
    )

    internal fun search(query: String, maxResults: Int): List<WebSearchResult> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "https://en.wikipedia.org/w/api.php?action=query&list=search" +
            "&srsearch=$encoded&format=json&srlimit=$maxResults"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "AuraAndroid/1.0 (personal assistant)")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("Wikipedia HTTP ${resp.code}")
            val body = resp.body?.string() ?: return emptyList()
            val root = json.parseToJsonElement(body).jsonObject
            val queryObj = root["query"]?.jsonObject ?: return emptyList()
            val searchResults = queryObj["search"]?.jsonArray ?: return emptyList()
            return searchResults.take(maxResults).mapNotNull { item ->
                val obj = item.jsonObject
                val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val snippet = obj["snippet"]?.jsonPrimitive?.contentOrNull?.replace("<[^>]+>".toRegex(), "") ?: ""
                val pageUrl = "https://en.wikipedia.org/wiki/${title.replace(" ", "_")}"
                WebSearchResult(title = title, url = pageUrl, snippet = snippet)
            }
        }
    }

    private fun formatResults(results: List<WebSearchResult>): String =
        if (results.isEmpty()) "No Wikipedia articles found."
        else results.mapIndexed { i, r ->
            "${i + 1}. ${r.title}\n   ${r.url}\n   ${r.snippet}"
        }.joinToString("\n\n")
}