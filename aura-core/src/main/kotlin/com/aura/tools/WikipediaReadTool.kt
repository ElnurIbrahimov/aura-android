package com.aura.tools

import com.aura.agent.Tool
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton
import com.aura.providers.ToolDefinition
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty

/**
 * Read a Wikipedia article's first section as plain text.
 * Free, no API key. Uses the REST summary endpoint for a clean
 * one-paragraph summary, then falls back to the parse API for
 * the full first section if the summary is too short.
 */
@Singleton
class WikipediaReadTool @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun definition() = ToolDefinition(
        name = "wikipedia_read",
        description = "Read a Wikipedia article's summary as plain text. " +
            "Free, no API key. Input the article title (e.g. 'Quantum entanglement').",
        parameters = ToolParameters(
            properties = mapOf(
                "title" to ToolProperty(type = "string", description = "Wikipedia article title"),
            ),
            required = listOf("title"),
        ),
    )

    val tool = Tool(
        name = "wikipedia_read",
        description = definition().description,
        risk = ToolRisk.READ_ONLY,
        parameters = definition().parameters,
        execute = { call, _ ->
            val title = call.arguments["title"] as? String
                ?: return@Tool ToolResult.Error("missing 'title' argument", "bad_args")
            try {
                val summary = readSummary(title)
                if (summary.isNotBlank()) {
                    ToolResult.Ok(summary)
                } else {
                    ToolResult.Ok("No Wikipedia article found for '$title'.")
                }
            } catch (e: Exception) {
                ToolResult.Error("Wikipedia read failed: ${e.message}", "http_error")
            }
        },
        category = "web",
    )

    internal fun readSummary(title: String): String {
        // REST v1 summary endpoint — returns a clean extract.
        val encoded = java.net.URLEncoder.encode(title.replace(" ", "_"), "UTF-8")
        val url = "https://en.wikipedia.org/api/rest_v1/page/summary/$encoded"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "AuraAndroid/1.0 (personal assistant)")
            .header("Accept", "application/json")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (resp.code == 404) return ""
            if (!resp.isSuccessful) throw RuntimeException("Wikipedia HTTP ${resp.code}")
            val body = resp.body?.string() ?: return ""
            val root = json.parseToJsonElement(body).jsonObject
            val extract = root["extract"]?.jsonPrimitive?.contentOrNull ?: ""
            val titleResolved = root["title"]?.jsonPrimitive?.contentOrNull ?: title
            val desc = root["description"]?.jsonPrimitive?.contentOrNull ?: ""
            return buildString {
                append("# $titleResolved")
                if (desc.isNotBlank()) append("\n$desc")
                if (extract.isNotBlank()) append("\n\n$extract")
                append("\n\nSource: https://en.wikipedia.org/wiki/${titleResolved.replace(" ", "_")}")
            }
        }
    }
}