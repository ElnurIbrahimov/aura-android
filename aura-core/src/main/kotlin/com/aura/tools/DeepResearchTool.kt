package com.aura.tools

import com.aura.agent.Tool
import com.aura.agent.ToolContext
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.core.url.SsrfGuard
import com.aura.core.url.SsrfValidation
import com.aura.data.UserPreferences
import com.aura.core.util.buildFirecrawlBody
import com.aura.providers.ChatOptions
import com.aura.providers.ProviderKeys
import com.aura.providers.ProviderMessage
import com.aura.providers.ProviderRegistry
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import com.aura.tools.DuckDuckGoSearch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deep research tool with citations and multi-step reasoning.
 *
 * Pipeline (SOTA):
 * 1. Search the web (Tavily > Brave > DuckDuckGo).
 * 2. Fetch top URLs IN PARALLEL (async + awaitAll).
 * 3. Synthesize a draft answer.
 * 4. Detect gaps: "What information is missing to fully answer the query?"
 * 5. If gaps found and iterations remain: search again for the gaps,
 *    fetch new sources IN PARALLEL, append to context.
 * 6. Final synthesis with all accumulated sources.
 * 7. Return JSON with "answer" and "citations".
 *
 * Risk: REMOTE_COST. Timeout: 120 seconds (up from 60 for multi-step).
 */
@Singleton
class DeepResearchTool @Inject constructor(
    private val httpClient: OkHttpClient,
    private val providerKeys: ProviderKeys,
    private val providerRegistry: ProviderRegistry,
    private val userPreferences: UserPreferences? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json".toMediaType()
    private val ddg by lazy { DuckDuckGoSearch(httpClient) }

    data class Citation(val index: Int, val title: String, val url: String)

    fun definition() = ToolParameters(
        properties = mapOf(
            "query" to ToolProperty(
                type = "string",
                description = "The research question or topic to investigate",
            ),
            "max_sources" to ToolProperty(
                type = "integer",
                description = "Maximum number of sources to fetch and cite (default 5, max 10)",
            ),
            "model" to ToolProperty(
                type = "string",
                description = "Optional fully-qualified synthesis model. Defaults to the background model selected in Settings.",
            ),
        ),
        required = listOf("query"),
    )

    val tool = Tool(
        name = "deep_research",
        description = "Perform deep research: search the web, fetch content from top sources, identify gaps, search again if needed, and synthesize a thorough answer with numbered citations.",
        risk = ToolRisk.REMOTE_COST,
        parameters = definition(),
        execute = { call, _ ->
            val query = call.arguments["query"] as? String
                ?: return@Tool ToolResult.Error("missing 'query' argument", "bad_args")
            val maxSources = (call.arguments["max_sources"] as? Int ?: 5).coerceIn(1, 10)
            val modelArg = call.arguments["model"] as? String

            try {
                val result = runResearch(query, maxSources, modelArg)
                ToolResult.Ok(result)
            } catch (e: TimeoutCancellationException) {
                ToolResult.Error("Research timed out after ${RESEARCH_TIMEOUT_MS / 1000}s", "timeout")
            } catch (e: Exception) {
                ToolResult.Error("Research failed: ${e.message}", "research_error")
            }
        },
        category = "web",
    )

    // ------------------------------------------------------------------
    // Pipeline
    // ------------------------------------------------------------------

    private suspend fun runResearch(
        query: String,
        maxSources: Int,
        modelArg: String?,
    ): String = withTimeout(RESEARCH_TIMEOUT_MS) {
        val modelId = modelArg?.takeIf(String::isNotBlank)
            ?: userPreferences?.backgroundModel?.first()
            ?: userPreferences?.defaultModel?.first()
            ?: throw IllegalStateException("Choose a background model in Settings for research synthesis")

        // Accumulated state across iterations
        val allContents = mutableMapOf<String, String>()
        val allCitations = mutableListOf<Citation>()
        val seenUrls = mutableSetOf<String>()
        var contextBlock = ""

        // Step 1: Initial search
        val initialResults = performSearch(query, maxSources * 2)
        if (initialResults.isEmpty()) {
            return@withTimeout """{"answer":"No search results found for the query.","citations":[]}"""
        }

        // Step 2: Fetch initial sources IN PARALLEL
        val initialCitations = initialResults
            .take(maxSources)
            .filter { it.url !in seenUrls }
            .mapIndexed { idx, r -> Citation(idx + 1, r.title, r.url) }

        val initialContents = fetchSourcesParallel(initialCitations)
        initialContents.forEach { (url, content) ->
            allContents[url] = content
            seenUrls.add(url)
        }
        allCitations.addAll(initialCitations)
        contextBlock = buildContextBlock(allCitations, allContents)

        // Step 3: Multi-step gap detection loop (up to MAX_ITERATIONS)
        for (iteration in 1..MAX_ITERATIONS) {
            // Detect gaps
            val gaps = detectGaps(query, contextBlock, modelId)
            if (gaps.isNullOrBlank() || gaps == "NONE") break

            // Search for the gaps
            val gapResults = performSearch(gaps, maxSources)
            val newCitations = gapResults
                .filter { it.url !in seenUrls }
                .take(maxSources - 1) // leave room, don't exceed maxSources total per iteration
                .mapIndexed { idx, r ->
                    Citation(
                        index = allCitations.size + idx + 1,
                        title = r.title,
                        url = r.url,
                    )
                }

            if (newCitations.isEmpty()) break // no new sources found, stop iterating

            // Fetch new sources IN PARALLEL
            val newContents = fetchSourcesParallel(newCitations)
            newContents.forEach { (url, content) ->
                allContents[url] = content
                seenUrls.add(url)
            }
            allCitations.addAll(newCitations)
            contextBlock = buildContextBlock(allCitations, allContents)
        }

        // Step 4: Final synthesis with all accumulated sources
        val answer = synthesizeAnswer(query, contextBlock, modelId)

        // Step 5: Build & return JSON output
        buildJsonOutput(answer, allCitations.distinctBy { it.url })
    }

    // ------------------------------------------------------------------
    // Parallel fetch
    // ------------------------------------------------------------------

    /**
     * Fetch content for multiple URLs in parallel. Returns a map of
     * url -> content for successful fetches only. Failed fetches are
     * silently omitted (the synthesis step handles missing content
     * gracefully).
     */
    private suspend fun fetchSourcesParallel(
        citations: List<Citation>,
    ): Map<String, String> = coroutineScope {
        val deferreds = citations.map { citation ->
            async {
                runCatching { fetchUrlContent(citation.url) }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { citation.url to it }
            }
        }
        deferreds.awaitAll().filterNotNull().toMap()
    }

    // ------------------------------------------------------------------
    // Gap detection
    // ------------------------------------------------------------------

    /**
     * Ask the model to identify what information is missing from the
     * current context to fully answer the query. Returns a search
     * query string for the missing info, or "NONE" if the context is
     * sufficient.
     */
    private suspend fun detectGaps(
        query: String,
        context: String,
        modelId: String,
    ): String? {
        val systemPrompt = buildString {
            append("You are a research analyst. You are given a research question and a set of sources. ")
            append("Your job is to identify what key information is MISSING to fully answer the question. ")
            append("If the sources are sufficient, respond with exactly: NONE\n")
            append("If information is missing, respond with a single search query that would find the missing info. ")
            append("Do not include any other text, explanation, or formatting.\n")
        }

        val userPrompt = buildString {
            append("Research question: $query\n\n")
            append("Current sources (truncated):\n")
            append(context.take(3000))
            append("\n\nWhat information is missing? Respond with a search query or NONE.")
        }

        val messages = listOf(
            ProviderMessage(role = ProviderMessage.Role.system, content = systemPrompt),
            ProviderMessage(role = ProviderMessage.Role.user, content = userPrompt),
        )

        return try {
            val options = ChatOptions(temperature = 0.3, maxTokens = 100)
            val flow = providerRegistry.chat(modelId, messages, options)
            val chunks = flow.toList()
            val response = chunks.filter { it.text != null }.joinToString("") { it.text!! }
            response.trim().ifBlank { null }
        } catch (e: Exception) {
            null // gap detection is best-effort
        }
    }

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    private data class SearchResult(val title: String, val url: String, val snippet: String)

    private fun performSearch(query: String, maxResults: Int): List<SearchResult> {
        val tavilyKey = providerKeys.keyFor("tavily")
        return if (!tavilyKey.isNullOrBlank()) {
            searchTavily(query, maxResults, tavilyKey)
        } else {
            searchBrave(query, maxResults)
        }
    }

    private fun searchTavily(query: String, maxResults: Int, apiKey: kotlin.String): List<SearchResult> {
        val requestBody = buildJsonObject {
            put("query", query)
            put("max_results", maxResults.coerceIn(5, 20))
            put("search_depth", "basic")
        }.toString()

        val req = Request.Builder()
            .url("https://api.tavily.com/search")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("User-Agent", "Mozilla/5.0 Aura/1.0")
            .post(requestBody.toRequestBody(mediaType))
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("Tavily API HTTP ${resp.code}")
            val bodyStr = resp.body?.string() ?: return emptyList()
            val root = json.parseToJsonElement(bodyStr).jsonObject
            val results = root["results"]?.jsonArray ?: return emptyList()
            return results.mapNotNull { el ->
                val obj = el.jsonObject
                val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val resultUrl = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val snippet = obj["content"]?.jsonPrimitive?.contentOrNull ?: ""
                SearchResult(title = title, url = resultUrl, snippet = snippet)
            }
        }
    }

    private fun searchBrave(query: String, maxResults: Int): List<SearchResult> {
        val braveKey = providerKeys.keyFor("brave")
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")

        return if (!braveKey.isNullOrBlank()) {
            searchBraveApi(maxResults, braveKey, encoded)
        } else {
            searchDuckDuckGo(encoded, maxResults)
        }
    }

    private fun searchBraveApi(
        maxResults: Int,
        apiKey: kotlin.String,
        encoded: String,
    ): List<SearchResult> {
        val url = "https://api.search.brave.com/res/v1/web/search?q=$encoded&count=$maxResults"
        val req = Request.Builder()
            .url(url)
            .header("X-Subscription-Token", apiKey)
            .header("User-Agent", "Mozilla/5.0 Aura/1.0")
            .header("Accept", "application/json")
            .build()

        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("Brave API HTTP ${resp.code}")
            val bodyStr = resp.body?.string() ?: return emptyList()
            val root = json.parseToJsonElement(bodyStr).jsonObject
            val web = root["web"]?.jsonObject ?: return emptyList()
            val results = web["results"]?.jsonArray ?: return emptyList()
            return results.mapNotNull { el ->
                val obj = el.jsonObject
                val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val resultUrl = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val snippet = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
                SearchResult(title = title, url = resultUrl, snippet = snippet)
            }
        }
    }

    private fun searchDuckDuckGo(encoded: String, maxResults: Int): List<SearchResult> {
        val query = java.net.URLDecoder.decode(encoded, "UTF-8")
        return ddg.search(query, maxResults).map { SearchResult(it.title, it.url, it.snippet) }
    }

    // ------------------------------------------------------------------
    // Fetch
    // ------------------------------------------------------------------

    private fun fetchUrlContent(url: String): String? {
        val target = SsrfGuard.inspect(url) as? SsrfValidation.Safe ?: return null

        val firecrawlKey = providerKeys.keyFor("firecrawl")
        if (!firecrawlKey.isNullOrBlank()) {
            return fetchViaFirecrawl(target.url, firecrawlKey)
        }
        return fetchDirect(target)
    }

    private fun fetchViaFirecrawl(url: kotlin.String, apiKey: kotlin.String): kotlin.String? {
        try {
            val body = buildFirecrawlBody(url)

            val req = Request.Builder()
                .url("https://api.firecrawl.dev/v1/scrape")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0 Aura/1.0")
                .post(body.toRequestBody(mediaType))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bodyStr = resp.body?.string() ?: return null
                val root = json.parseToJsonElement(bodyStr).jsonObject
                val data = root["data"]?.jsonObject ?: return null
                return data["markdown"]?.jsonPrimitive?.contentOrNull
            }
        } catch (_: Exception) {
            return null
        }
    }

    private fun fetchDirect(target: SsrfValidation.Safe): String? {
        try {
            val pinnedClient = SsrfGuard.pinnedClient(httpClient, target)
            val req = Request.Builder()
                .url(target.url)
                .header("User-Agent", "Mozilla/5.0 Aura/1.0")
                .header("Accept", "text/html,text/plain,*/*")
                .build()

            pinnedClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                return body
                    .replace(Regex("<[^>]+>"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(3000)
            }
        } catch (_: Exception) {
            return null
        }
    }

    // ------------------------------------------------------------------
    // Context assembly, synthesis, output formatting
    // ------------------------------------------------------------------

    private fun buildContextBlock(
        citations: List<Citation>,
        contents: Map<String, String>,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("You have the following sources to answer the research question.\n")
        for (c in citations) {
            val content = contents[c.url] ?: continue
            sb.appendLine("[${c.index}] ${c.title}")
            sb.appendLine("URL: ${c.url}")
            sb.appendLine("```")
            sb.appendLine(content.take(MAX_CHARS_PER_SOURCE))
            sb.appendLine("```")
            sb.appendLine()
        }
        val raw = sb.toString()
        return if (raw.length > CONTEXT_LIMIT) raw.take(CONTEXT_LIMIT) + "\n\n[...truncated]"
        else raw
    }

    private suspend fun synthesizeAnswer(
        query: String,
        context: String,
        modelId: String,
    ): String {
        val systemPrompt = "You are a research assistant. Synthesize an answer using ONLY the provided sources. " +
            "Cite using [1], [2], etc. Be concise but thorough. If sources are insufficient, " +
            "say what information is missing."

        val userPrompt = "Research question: $query\n\n" +
            "---\n$context\n---\n" +
            "Provide a well-structured answer based only on the sources above. " +
            "Use citation markers like [1], [2] after each claim."

        val messages = listOf(
            ProviderMessage(role = ProviderMessage.Role.system, content = systemPrompt),
            ProviderMessage(role = ProviderMessage.Role.user, content = userPrompt),
        )

        val options = ChatOptions(temperature = 0.5, maxTokens = 2048)
        val flow = providerRegistry.chat(modelId, messages, options)
        val chunks = flow.toList()

        val answer = chunks.filter { it.text != null }.joinToString("") { it.text!! }
        return answer.ifBlank { "No answer could be synthesized from the available sources." }
    }

    private fun buildJsonOutput(answer: String, citations: List<Citation>): String {
        val citationsJson = buildJsonArray {
            for (c in citations) {
                add(buildJsonObject {
                    put("index", c.index)
                    put("title", c.title)
                    put("url", c.url)
                })
            }
        }
        return buildJsonObject {
            put("answer", answer)
            put("citations", citationsJson)
        }.toString()
    }

    companion object {
        const val RESEARCH_TIMEOUT_MS = 120_000L
        const val CONTEXT_LIMIT = 20_000
        const val MAX_CHARS_PER_SOURCE = 4000
        const val MAX_ITERATIONS = 2
    }
}