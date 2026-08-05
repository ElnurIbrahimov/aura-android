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
 * DuckDuckGo Instant Answer API — free, no key, structured JSON.
 * Returns abstract text, related topics, and redirect URLs.
 * More stable than HTML scraping (DuckDuckGoSearch.kt).
 */
@Singleton
class DdgInstantAnswerTool @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun definition() = ToolDefinition(
        name = "ddg_instant_answer",
        description = "Get an instant answer from DuckDuckGo. Free, no API key. " +
            "Returns a concise answer, abstract, and related topics. " +
            "Best for definitions, calculations, and quick facts.",
        parameters = ToolParameters(
            properties = mapOf(
                "query" to ToolProperty(type = "string", description = "Search query"),
            ),
            required = listOf("query"),
        ),
    )

    val tool = Tool(
        name = "ddg_instant_answer",
        description = definition().description,
        risk = ToolRisk.READ_ONLY,
        parameters = definition().parameters,
        execute = { call, _ ->
            val query = call.arguments["query"] as? String
                ?: return@Tool ToolResult.Error("missing 'query' argument", "bad_args")
            try {
                val result = search(query)
                ToolResult.Ok(result)
            } catch (e: Exception) {
                ToolResult.Error("DuckDuckGo instant answer failed: ${e.message}", "http_error")
            }
        },
        category = "web",
    )

    internal fun search(query: String): String {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "AuraAndroid/1.0")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("DuckDuckGo HTTP ${resp.code}")
            val body = resp.body?.string() ?: return "No results."
            val root = json.parseToJsonElement(body).jsonObject

            val abstractText = root["AbstractText"]?.jsonPrimitive?.contentOrNull ?: ""
            val abstractSource = root["AbstractSource"]?.jsonPrimitive?.contentOrNull ?: ""
            val abstractUrl = root["AbstractURL"]?.jsonPrimitive?.contentOrNull ?: ""
            val answerType = root["AnswerType"]?.jsonPrimitive?.contentOrNull ?: ""
            val answer = root["Answer"]?.jsonPrimitive?.contentOrNull ?: ""
            val definition = root["Definition"]?.jsonPrimitive?.contentOrNull ?: ""
            val definitionSource = root["DefinitionSource"]?.jsonPrimitive?.contentOrNull ?: ""

            val parts = mutableListOf<String>()

            if (answer.isNotBlank()) {
                parts.add("Answer ($answerType): $answer")
            }
            if (abstractText.isNotBlank()) {
                parts.add("Abstract: $abstractText")
                if (abstractSource.isNotBlank()) parts.add("Source: $abstractSource")
                if (abstractUrl.isNotBlank()) parts.add("URL: $abstractUrl")
            }
            if (definition.isNotBlank()) {
                parts.add("Definition: $definition")
                if (definitionSource.isNotBlank()) parts.add("Source: $definitionSource")
            }

            // Related topics — top 5
            val relatedTopics = root["RelatedTopics"]?.jsonArray
            if (relatedTopics != null && relatedTopics.isNotEmpty()) {
                val topics = relatedTopics.take(5).mapNotNull { item ->
                    val obj = item.jsonObject
                    val text = obj["Text"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val firstUrl = obj["FirstURL"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (text.isNotBlank()) {
                        if (firstUrl.isNotBlank()) "- $text\n  $firstUrl"
                        else "- $text"
                    } else null
                }
                if (topics.isNotEmpty()) {
                    parts.add("Related topics:\n${topics.joinToString("\n")}")
                }
            }

            return if (parts.isEmpty()) {
                "No instant answer found for '$query'. Try web_search for broader results."
            } else {
                parts.joinToString("\n\n")
            }
        }
    }
}