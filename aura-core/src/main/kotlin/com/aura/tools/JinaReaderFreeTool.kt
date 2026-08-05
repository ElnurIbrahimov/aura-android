package com.aura.tools

import com.aura.agent.Tool
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.core.url.SsrfGuard
import com.aura.core.url.SsrfValidation
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton
import com.aura.providers.ToolDefinition
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty

/**
 * Jina Reader free tier — converts any URL to clean markdown text.
 * Free without an API key (rate-limited to ~10 req/min).
 * With a Jina key configured, uses the authenticated endpoint for
 * higher rate limits.
 *
 * URL: https://r.jina.ai/{url} — no auth header = free tier
 * URL: https://r.jina.ai/{url} — with Bearer key = paid tier
 *
 * This is the free complement to `fetch_url` (which needs Firecrawl).
 */
@Singleton
class JinaReaderFreeTool @Inject constructor(
    private val httpClient: OkHttpClient,
    private val providerKeys: com.aura.providers.ProviderKeys,
) {
    fun definition() = ToolDefinition(
        name = "read_url",
        description = "Read any URL and return its content as clean markdown text. " +
            "Free (no API key needed, rate-limited ~10/min). " +
            "Strips ads, navigation, and JavaScript — returns only the article content. " +
            "Better than http_file_read for web pages because it extracts readable text.",
        parameters = ToolParameters(
            properties = mapOf(
                "url" to ToolProperty(type = "string", description = "The URL to read"),
                "max_chars" to ToolProperty(type = "integer", description = "Max chars to return (default 4000, max 8000)"),
            ),
            required = listOf("url"),
        ),
    )

    val tool = Tool(
        name = "read_url",
        description = definition().description,
        risk = ToolRisk.READ_ONLY,
        parameters = definition().parameters,
        execute = { call, _ ->
            val url = call.arguments["url"] as? String
                ?: return@Tool ToolResult.Error("missing 'url' argument", "bad_args")
            val maxChars = (call.arguments["max_chars"] as? Int ?: 4000).coerceIn(500, 8000)

            // SSRF guard — user-supplied URL
            val ssrfResult = SsrfGuard.inspect(url)
            if (ssrfResult is SsrfValidation.Blocked) {
                return@Tool ToolResult.Error(ssrfResult.reason, "ssrf_guard")
            }
            val safe = ssrfResult as SsrfValidation.Safe

            try {
                val jinaUrl = "https://r.jina.ai/${safe.url}"
                val builder = Request.Builder()
                    .url(jinaUrl)
                    .header("Accept", "text/plain")
                    .header("X-Return-Format", "text")

                // Use authenticated endpoint if a Jina key is configured
                // (higher rate limits). Free tier works without it.
                val jinaKey = providerKeys.keyFor("jina")
                if (!jinaKey.isNullOrBlank()) {
                    builder.header("Authorization", "Bearer $jinaKey")
                }

                val pinnedClient = SsrfGuard.pinnedClient(httpClient, safe)
                pinnedClient.newCall(builder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@Tool ToolResult.Error("Jina Reader HTTP ${resp.code}", "http_error")
                    }
                    val body = resp.body?.string() ?: return@Tool ToolResult.Ok("")
                    // Truncate to max chars
                    val truncated = if (body.length > maxChars) {
                        body.take(maxChars) + "\n\n[...truncated at $maxChars chars]"
                    } else {
                        body
                    }
                    ToolResult.Ok(truncated)
                }
            } catch (e: Exception) {
                ToolResult.Error("read_url failed: ${e.message}", "http_error")
            }
        },
        category = "web",
    )
}