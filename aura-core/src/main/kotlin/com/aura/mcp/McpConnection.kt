package com.aura.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/**
 * Single MCP server connection. Implements the MCP Streamable HTTP
 * transport: JSON-RPC 2.0 over HTTP POST with SSE response support.
 *
 * This is a minimal implementation. The official Kotlin MCP SDK will
 * replace this class when validated on Android.
 */
internal class McpConnection(
    val config: McpServerConfig,
    private val httpClient: OkHttpClient,
    private val authToken: kotlin.String? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaTypeJson = "application/json".toMediaType()

    @Volatile
    private var _health: McpServerHealth = McpServerHealth(
        serverId = config.id,
        state = McpConnectionState.DISCONNECTED,
    )
    val health: McpServerHealth get() = _health

    fun isConnected(): kotlin.Boolean = _health.state == McpConnectionState.CONNECTED

    companion object {
        /** Client version sent in the MCP initialize handshake. */
        const val CLIENT_VERSION = "0.51.2"
        /** Timeout for the initialize handshake. */
        private const val INIT_TIMEOUT_MS = 15_000L
        /** Max response body size for initialize/listTools/listResources. */
        private const val MAX_META_RESPONSE_BYTES = 2_000_000
        /** Max response size for tool call results (10MB). */
        private const val MAX_TOOL_RESPONSE_BYTES = 10_000_000
    }

    suspend fun initialize(): McpServerHealth = withContext(Dispatchers.IO) {
        try {
            val request = buildJsonRpcRequest("initialize", buildJsonObject {
                put("protocolVersion", "2025-03-26")
                putJsonObject("capabilities") { }
                put("clientInfo", buildJsonObject {
                    put("name", "aura-android")
                    put("version", CLIENT_VERSION)
                })
            })
            val response = withTimeoutOrNull(INIT_TIMEOUT_MS) {
                sendRequest(request)
            }
            val error = response?.get("error")
            if (response != null && (error == null || error is kotlinx.serialization.json.JsonNull)) {
                _health = _health.copy(
                    state = McpConnectionState.CONNECTED,
                    lastConnectedAt = System.currentTimeMillis(),
                    lastError = "",
                )
                // Streamable HTTP handshake: after a successful initialize
                // the client MUST send notifications/initialized before any
                // other request. Best-effort — some servers don't require
                // it, but spec-compliant ones reject tools/list without it.
                sendNotification("notifications/initialized")
            } else if (response != null) {
                // JSON-RPC error member set: the server REFUSED the
                // initialize (e.g. unsupported protocol version). That is
                // not a connection.
                val message = error?.let { err ->
                    (err as? JsonObject)?.get("message")?.jsonPrimitive?.content ?: err.toString()
                } ?: "unknown error"
                _health = _health.copy(
                    state = McpConnectionState.ERROR,
                    lastError = "Initialize rejected: $message",
                )
            } else {
                _health = _health.copy(
                    state = McpConnectionState.ERROR,
                    lastError = "Initialize timed out or returned no valid response",
                )
            }
        } catch (e: Exception) {
            _health = _health.copy(
                state = McpConnectionState.ERROR,
                lastError = e.message ?: "Unknown error",
            )
        }
        _health
    }

    suspend fun listTools(): List<McpToolInfo> = withContext(Dispatchers.IO) {
        if (!isConnected()) return@withContext emptyList()
        try {
            val request = buildJsonRpcRequest("tools/list", JsonObject(emptyMap()))
            val response = sendRequest(request) ?: return@withContext emptyList()
            val toolsArray = response["result"]?.jsonObject?.get("tools")?.jsonArray ?: return@withContext emptyList()
            val tools = toolsArray.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                // Tool annotations, which the protocol defines precisely so a
                // client does not have to guess what a tool does. They were
                // arriving in this object and being dropped, which is why every
                // MCP tool was classified by what it costs rather than by what
                // it can do.
                val annotations = obj["annotations"] as? JsonObject
                McpToolInfo(
                    serverId = config.id,
                    name = name,
                    description = obj["description"]?.jsonPrimitive?.content ?: "",
                    inputSchemaJson = obj["inputSchema"]?.toString() ?: "{}",
                    serverName = config.name,
                    readOnlyHint = annotations?.get("readOnlyHint")?.jsonPrimitive?.booleanOrNull,
                    destructiveHint = annotations?.get("destructiveHint")?.jsonPrimitive?.booleanOrNull,
                )
            }.take(config.maxTools)
            _health = _health.copy(toolCount = tools.size)
            tools
        } catch (e: Exception) {
            android.util.Log.w("McpConnection", "listTools failed for ${config.name}: ${e.message}")
            emptyList()
        }
    }

    suspend fun listResources(): List<McpResourceInfo> = withContext(Dispatchers.IO) {
        if (!isConnected()) return@withContext emptyList()
        try {
            val request = buildJsonRpcRequest("resources/list", JsonObject(emptyMap()))
            val response = sendRequest(request) ?: return@withContext emptyList()
            val resourcesArray = response["result"]?.jsonObject?.get("resources")?.jsonArray ?: return@withContext emptyList()
            resourcesArray.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val uri = obj["uri"]?.jsonPrimitive?.content ?: return@mapNotNull null
                McpResourceInfo(
                    serverId = config.id,
                    uri = uri,
                    name = obj["name"]?.jsonPrimitive?.content ?: "",
                    description = obj["description"]?.jsonPrimitive?.content ?: "",
                    mimeType = obj["mimeType"]?.jsonPrimitive?.content ?: "",
                )
            }
        } catch (e: Exception) {
            android.util.Log.w("McpConnection", "listResources failed for ${config.name}: ${e.message}")
            emptyList()
        }
    }

    suspend fun callTool(
        toolName: kotlin.String,
        arguments: Map<kotlin.String, Any?>,
        timeoutMs: kotlin.Long,
    ): McpToolResult = withTimeoutOrNull(timeoutMs) {
        withContext(Dispatchers.IO) {
            try {
                val argsObj = buildJsonObject {
                    arguments.forEach { (key, value) ->
                        put(key, toJsonElement(value))
                    }
                }
                val request = buildJsonRpcRequest("tools/call", buildJsonObject {
                    put("name", toolName)
                    put("arguments", argsObj)
                })
                val response = sendRequest(request, maxResponseBytes = MAX_TOOL_RESPONSE_BYTES)
                    ?: return@withContext McpToolResult.Failure("No response from server", "no_response")
                val result = response["result"]?.jsonObject
                    ?: return@withContext McpToolResult.Failure("Missing result", "malformed_response")
                val content = result["content"]?.jsonArray
                    ?: return@withContext McpToolResult.Failure("Missing content array", "malformed_response")
                val output = content.mapNotNull { item ->
                    (item as? JsonObject)?.get("text")?.jsonPrimitive?.content
                }.joinToString("\n")
                // Enforce response size limit
                val truncated = if (output.length > config.maxResponseBytes) {
                    output.take(config.maxResponseBytes) + "\n[truncated at ${config.maxResponseBytes} bytes]"
                } else output
                McpToolResult.Success(truncated, isError = result["isError"]?.jsonPrimitive?.content == "true")
            } catch (e: Exception) {
                McpToolResult.Failure(e.message ?: "Tool call failed", "call_error")
            }
        }
    } ?: McpToolResult.Timeout(config.name)

    suspend fun disconnect() {
        _health = _health.copy(state = McpConnectionState.DISCONNECTED)
    }

    private fun buildJsonRpcRequest(method: kotlin.String, params: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", UUID.randomUUID().toString())
        put("method", method)
        put("params", params)
    }

    /**
     * MCP Streamable HTTP session id, captured from the initialize
     * response's `Mcp-Session-Id` header and echoed on every subsequent
     * request. Servers that assign one reject session-less follow-ups
     * with 400/404.
     */
    @Volatile
    private var sessionId: kotlin.String? = null

    private fun buildHttpRequest(bodyJson: kotlin.String): Request {
        val builder = Request.Builder().url(config.url)
            .post(bodyJson.toRequestBody(mediaTypeJson))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
        // Attach auth token if provided by McpClientManager (from SecureDataStore)
        if (!authToken.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $authToken")
        }
        sessionId?.let { builder.header("Mcp-Session-Id", it) }
        return builder.build()
    }

    /**
     * Fire-and-forget JSON-RPC notification (no id, no response
     * expected). Failures are logged and swallowed — a server that
     * doesn't accept notifications/initialized still served a valid
     * initialize.
     */
    private fun sendNotification(method: kotlin.String) {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
        }
        try {
            httpClient.newCall(buildHttpRequest(body.toString())).execute().use { response ->
                if (!response.isSuccessful) {
                    android.util.Log.w("McpConnection", "$method returned HTTP ${response.code} for ${config.name}")
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("McpConnection", "$method failed for ${config.name}: ${e.message}")
        }
    }

    private fun sendRequest(
        requestBody: JsonObject,
        maxResponseBytes: Int = MAX_META_RESPONSE_BYTES,
    ): JsonObject? {
        val requestId = requestBody["id"]?.jsonPrimitive?.content
        return try {
            httpClient.newCall(buildHttpRequest(requestBody.toString())).execute().use { response ->
                if (!response.isSuccessful) return null
                // Capture the server-assigned session id (Streamable HTTP).
                response.header("Mcp-Session-Id")?.takeIf { it.isNotBlank() }?.let { sessionId = it }
                // Enforce max response size on metadata calls (initialize/listTools)
                // to prevent OOM from a malicious server returning huge JSON.
                // Read as bytes first (not string) so the size check is by
                // byte count, not character count — a 1MB char limit on UTF-8
                // with non-ASCII content could be 3-4MB in bytes.
                val bytes = response.body?.bytes() ?: return null
                if (bytes.size > maxResponseBytes) {
                    android.util.Log.w("McpConnection", "Response from ${config.name} exceeded ${maxResponseBytes} bytes, truncating")
                    return null
                }
                val raw = bytes.toString(Charsets.UTF_8)
                val contentType = response.header("Content-Type").orEmpty()
                val message = if (contentType.startsWith("text/event-stream")) {
                    // Streamable HTTP servers may frame the JSON-RPC
                    // response as SSE. Extract the message for our request
                    // id instead of failing to parse the whole body.
                    parseSseJsonRpc(raw, requestId)
                } else {
                    json.parseToJsonElement(raw) as? JsonObject
                } ?: return null
                // JSON-RPC responses MUST echo the request id; a mismatch
                // means this is a response to something else (or a bogus
                // server) and treating it as ours corrupts state.
                val responseId = message["id"]?.jsonPrimitive?.content
                if (requestId != null && responseId != requestId) {
                    android.util.Log.w(
                        "McpConnection",
                        "JSON-RPC id mismatch from ${config.name}: sent $requestId, got $responseId",
                    )
                    return null
                }
                message
            }
        } catch (e: Exception) {
            android.util.Log.w("McpConnection", "sendRequest failed for ${config.name}: ${e.message}")
            null
        }
    }

    /**
     * Parse an SSE-framed body into the JSON-RPC message matching
     * [requestId]. Events are separated by blank lines; each event's
     * `data:` lines are joined per the SSE spec. Server-initiated
     * notifications (no id) and unrelated ids are skipped — the caller
     * treats "no matching message" as an error, mirroring the id check
     * on plain-JSON responses.
     */
    internal fun parseSseJsonRpc(raw: kotlin.String, requestId: kotlin.String?): JsonObject? {
        val events = raw.split("\n\n", "\r\n\r\n")
        for (event in events) {
            val data = event.lines()
                .filter { it.startsWith("data:") }
                .joinToString("\n") { it.removePrefix("data:").trim() }
            if (data.isBlank()) continue
            val obj = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: continue
            if (obj["jsonrpc"] == null) continue
            val id = obj["id"]?.jsonPrimitive?.content
            if (requestId == null) {
                if (obj["result"] != null || obj["error"] != null) return obj
            } else if (id == requestId) {
                return obj
            }
        }
        return null
    }

    /**
     * Convert a Kotlin value into a JSON element for MCP request arguments.
     * Handles null, primitives, nested maps, lists, arrays, and collections —
     * previously Lists/Arrays/nulls were silently dropped.
     */
    private fun toJsonElement(value: Any?): kotlinx.serialization.json.JsonElement = when (value) {
        null -> kotlinx.serialization.json.JsonNull
        is kotlin.String -> kotlinx.serialization.json.JsonPrimitive(value)
        is kotlin.Boolean -> kotlinx.serialization.json.JsonPrimitive(value)
        is Number -> kotlinx.serialization.json.JsonPrimitive(value)
        is Map<*, *> -> kotlinx.serialization.json.buildJsonObject {
            value.forEach { (k, v) -> put(k.toString(), toJsonElement(v)) }
        }
        is List<*> -> kotlinx.serialization.json.buildJsonArray {
            value.forEach { add(toJsonElement(it)) }
        }
        is Array<*> -> kotlinx.serialization.json.buildJsonArray {
            value.forEach { add(toJsonElement(it)) }
        }
        is Collection<*> -> kotlinx.serialization.json.buildJsonArray {
            value.forEach { add(toJsonElement(it)) }
        }
        else -> kotlinx.serialization.json.JsonPrimitive(value.toString())
    }
}