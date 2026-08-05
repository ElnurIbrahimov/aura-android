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
 * SearXNG meta-search — free, no key, aggregates Google + Bing + DDG.
 * Uses public instances with JSON output. Falls back through a list
 * of instances if one is down.
 */
@Singleton
class SearxngSearchTool @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        // Public SearXNG instances that support JSON output.
        // Ordered by reliability — first one that responds wins.
        private val INSTANCES = listOf(
            "https://search.inetol.net",
            "https://searx.be",
            "https://search.mdosch.de",
            "https://searx.tiekoetter.com",
        )
        private val TIMEOUT_MS = 8_000L
    }

    fun definition() = ToolDefinition(
        name = "searxng_search",
        description = "Search the web via SearXNG meta-search (free, no key). " +
            "Aggregates results from Google, Bing, and DuckDuckGo. " +
            "Use when web_search returns poor results or as a free alternative.",
        parameters = ToolParameters(
            properties = mapOf(
                "query" to ToolProperty(type = "string", description = "Search query"),
                "max_results" to ToolProperty(type = "integer", description = "Number of results (default 5, max 10)"),
            ),
            required = listOf("query"),
        ),
    )

    val tool = Tool(
        name = "searxng_search",
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
                ToolResult.Error("SearXNG search failed: ${e.message}", "http_error")
            }
        },
        category = "web",
    )

    internal fun search(query: String, maxResults: Int): List<WebSearchResult> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        for (instance in INSTANCES) {
            try {
                val url = "$instance/search?q=$encoded&format=json&safesearch=1"
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "AuraAndroid/1.0")
                    .header("Accept", "application/json")
                    .build()
                val timeoutClient = httpClient.newBuilder()
                    .callTimeout(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build()
                timeoutClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string() ?: return@use
                    val root = json.parseToJsonElement(body).jsonObject
                    val resultsArr = root["results"]?.jsonArray ?: return@use
                    val results = resultsArr.take(maxResults).mapNotNull { item ->
                        val obj = item.jsonObject
                        val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val resultUrl = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: ""
                        WebSearchResult(title = title, url = resultUrl, snippet = content)
                    }
                    if (results.isNotEmpty()) return results
                }
            } catch (e: Exception) {
                // Try next instance
                continue
            }
        }
        return emptyList()
    }

    private fun formatResults(results: List<WebSearchResult>): String =
        if (results.isEmpty()) "No SearXNG results found. All instances may be down."
        else results.mapIndexed { i, r ->
            "${i + 1}. ${r.title}\n   ${r.url}\n   ${r.snippet}"
        }.joinToString("\n\n")
}