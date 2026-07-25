package com.aura.tools

import com.aura.agent.Tool
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.core.url.SsrfGuard
import com.aura.core.url.SsrfValidation
import com.aura.providers.ToolDefinition
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read a remote file over HTTP(S) and return its contents. Serves as the
 * generic cloud/background-file bridge when the URL is a WebDAV endpoint,
 * S3 pre-signed URL, or any plain file URL.
 * Risk: REMOTE_COST.
 */
@Singleton
class HttpFileReadTool @Inject constructor(
    private val httpClient: OkHttpClient,
) {
    fun definition() = ToolDefinition(
        name = "http_file_read",
        description = "Read a remote file over HTTP(S) and return its content as text or base64. Supports public URLs, WebDAV, and pre-signed cloud storage URLs.",
        parameters = ToolParameters(
            properties = mapOf(
                "url" to ToolProperty(type = "string", description = "File URL"),
                "as_base64" to ToolProperty(type = "boolean", description = "Return base64 instead of UTF-8 text (default false)"),
                "max_chars" to ToolProperty(type = "integer", description = "Max text chars to return (default 8000, max 32000)"),
            ),
            required = listOf("url"),
        ),
    )

    val tool = Tool(
        name = "http_file_read",
        description = definition().description,
        risk = ToolRisk.REMOTE_COST,
        parameters = definition().parameters,
        execute = { call, _ ->
            val url = call.arguments["url"] as? String
                ?: return@Tool ToolResult.Error("missing 'url'", "bad_args")
            val ssrfResult = SsrfGuard.inspect(url)
            if (ssrfResult is SsrfValidation.Blocked) return@Tool ToolResult.Error(ssrfResult.reason, "ssrf_guard")
            val safe = ssrfResult as SsrfValidation.Safe
            val asBase64 = call.arguments["as_base64"] as? Boolean ?: false
            val maxChars = (call.arguments["max_chars"] as? Int ?: 8000).coerceIn(1, 32000)

            try {
                val req = Request.Builder().url(url).header("Accept", "*/*").build()
                val pinnedClient = SsrfGuard.pinnedClient(httpClient, safe)
                pinnedClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@Tool ToolResult.Error("HTTP ${resp.code} for $url", "http_error")
                    }
                    val maxBytes = maxChars * 4L // chars-to-bytes upper bound
                    val source = resp.body?.source() ?: return@Tool ToolResult.Ok("")
                    // Bound the wall-clock time we will wait for bytes.
                    // Without this, source.request() can block until the OkHttp
                    // read timeout (120s) on a streaming endpoint that never EOFs.
                    source.timeout().timeout(10_000L, TimeUnit.MILLISECONDS)
                    // Read at most maxBytes+1 to detect truncation, then cap.
                    source.request(maxBytes + 1L)
                    val bodyBytes = if (source.buffer.size > maxBytes) {
                        source.readByteArray(maxBytes)
                    } else {
                        source.readByteArray()
                    }
                        if (asBase64) {
                            val encoded = Base64.getEncoder().encodeToString(bodyBytes)
                            ToolResult.Ok(encoded.take(maxChars))
                        } else {
                            val text = String(bodyBytes, Charsets.UTF_8)
                            ToolResult.Ok(text.take(maxChars))
                        }
                }
            } catch (e: Exception) {
                ToolResult.Error("Read failed: ${e.message}", "exception")
            }
        },
        category = "web",
    )
}
