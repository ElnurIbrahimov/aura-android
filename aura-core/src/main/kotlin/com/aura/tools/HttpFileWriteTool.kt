package com.aura.tools

import com.aura.agent.Tool
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.core.url.SsrfGuard
import com.aura.core.url.SsrfValidation
import com.aura.providers.ToolDefinition
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Write text content to a remote file over HTTP(S). Useful for WebDAV or
 * any endpoint that accepts a PUT/POST body.
 * Risk: REMOTE_COST.
 */
@Singleton
class HttpFileWriteTool @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    fun definition() = ToolDefinition(
        name = "http_file_write",
        description = "Write text content to a remote file over HTTP(S). Sends PUT by default; set method='POST' if the endpoint requires it.",
        parameters = ToolParameters(
            properties = mapOf(
                "url" to ToolProperty(type = "string", description = "Destination URL"),
                "content" to ToolProperty(type = "string", description = "Text content to write"),
                "method" to ToolProperty(type = "string", description = "PUT or POST (default PUT)"),
                "content_type" to ToolProperty(type = "string", description = "Content-Type (default text/plain; charset=utf-8)"),
            ),
            required = listOf("url", "content"),
        ),
    )

    val tool = Tool(
        name = "http_file_write",
        description = definition().description,
        risk = ToolRisk.WRITE_REMOTE,
        parameters = definition().parameters,
        execute = { call, _ ->
            val url = call.arguments["url"] as? String
                ?: return@Tool ToolResult.Error("missing 'url'", "bad_args")
            val content = call.arguments["content"] as? String
                ?: return@Tool ToolResult.Error("missing 'content'", "bad_args")
            val ssrfResult = SsrfGuard.inspect(url)
            if (ssrfResult is SsrfValidation.Blocked) return@Tool ToolResult.Error(ssrfResult.reason, "ssrf_guard")
            val safe = ssrfResult as SsrfValidation.Safe
            val method = (call.arguments["method"] as? String ?: "PUT").uppercase()
            if (method !in setOf("PUT", "POST")) {
                return@Tool ToolResult.Error("method must be PUT or POST", "bad_args")
            }
            val contentType = call.arguments["content_type"] as? String ?: "text/plain; charset=utf-8"

            try {
                val body = content.toRequestBody(contentType.toMediaType())
                val req = Request.Builder()
                    .url(url)
                    .apply { if (method == "PUT") put(body) else post(body) }
                    .build()
                val pinnedClient = SsrfGuard.pinnedClient(httpClient, safe)
                pinnedClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        ToolResult.Ok("Wrote ${content.length} chars to $url (HTTP ${resp.code})")
                    } else {
                        ToolResult.Error("HTTP ${resp.code}: ${resp.body?.string() ?: ""}", "http_error")
                    }
                }
            } catch (e: Exception) {
                ToolResult.Error("Write failed: ${e.message}", "exception")
            }
        },
        category = "web",
    )
}
