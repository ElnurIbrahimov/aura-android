package com.aura.providers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSources
import java.io.IOException

/**
 * Anthropic Messages API. Tool use blocks are converted to ToolCall chunks.
 *
 * Like [OllamaCloudProvider], the API key is read from [ProviderKeys] on
 * every [chat] call so user changes in Settings take effect immediately.
 */
class AnthropicProvider(
    private val providerKeys: ProviderKeys,
    private val httpClient: OkHttpClient,
    /**
     * Base URL for the Anthropic API. Injectable so tests can point at a
     * MockWebServer without changing production defaults.
     */
    private val baseUrl: String = "https://api.anthropic.com",
    /**
     * Endpoint path for model catalog discovery, relative to [baseUrl].
     */
    private val modelsEndpoint: String = "/v1/models",
) : Provider {
    override val prefix = "anthropic"
    override val displayName = "Anthropic"

    /** Live API key, looked up at call time. */
    private suspend fun apiKey(): String = providerKeys.keyForAwaiting(prefix) ?: ""

    @Volatile private var activeCall: okhttp3.Call? = null

    override fun isConfigured(): Boolean = providerKeys.isConfigured(prefix)

    override fun chat(model: String, messages: List<ProviderMessage>, options: ChatOptions, tools: List<ToolDefinition>): Flow<ProviderChunk> = flow {
        val key = apiKey()
        val (systemPrompt, anthropicMessages) = splitSystem(messages)
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("max_tokens", options.maxTokens ?: 4096)
            put("temperature", options.temperature ?: ChatOptions.DEFAULT_TEMPERATURE)
            // Extended thinking: when budget is set, add the thinking block.
            // Anthropic requires max_tokens >= budget_tokens + 1, and
            // temperature must be 1.0 when thinking is enabled.
            options.thinkingBudget?.let { budget ->
                put("thinking", buildJsonObject {
                    put("type", "enabled")
                    put("budget_tokens", budget)
                })
                // Anthropic requires temperature=1 when thinking is enabled.
                put("temperature", 1.0)
            }
            systemPrompt?.let { put("system", it) }
            put("messages", buildAnthropicMessages(anthropicMessages))
            if (tools.isNotEmpty()) {
                put("tools", kotlinx.serialization.json.JsonArray(tools.map { tool ->
                    buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("input_schema", kotlinx.serialization.json.Json.parseToJsonElement(Json.encodeToString(ToolParameters.serializer(), tool.parameters)))
                    }
                }))
            }
        }
        val request = Request.Builder()
            .url("$baseUrl/v1/messages")
            .addHeader("x-api-key", key)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val call = httpClient.newCall(request)
        activeCall = call
        coroutineScope {
            val cancellationGuard = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
            kotlinx.coroutines.withTimeout(STREAM_READ_TIMEOUT_MS) {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    emit(ProviderChunk(error = ProviderError("http_${resp.code}", resp.message, retryable = resp.code == 429 || resp.code in 500..599)))
                    return@use
                }
                val source = resp.body?.source() ?: return@use
                // SSE stream. readUtf8Line() blocks until a line arrives (or returns
                // null at EOF). We process each 'data: ...' line. The two terminal
                // events we care about are 'message_stop' (Anthropic's normal end)
                // and finish_reason=tool_calls in 'message_delta' (which we also
                // map to FinishReason.tool_calls so the loop dispatches tools).
                //
                // Anthropic parallel tool calls: each content_block_start and
                // content_block_delta carries an `index` field that links deltas
                // to the originating tool id. We track `index -> id` in
                // `pendingByIndex` so a delta's id is filled in even when the
                // previous delta just came in (no name re-emit). Without this,
                // two parallel `tool_use` blocks would have their deltas
                // mis-routed by the Brain's "last seen id" fallback heuristic.
                val pendingByIndex = mutableMapOf<Int, String>()
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isEmpty()) continue
                    if (!line.startsWith("data: ")) continue
                    val data = line.removePrefix("data: ").trim()
                    if (data.isEmpty()) continue
                    val obj = try { Json.parseToJsonElement(data).jsonObject } catch (e: Exception) { continue }
                    when ((obj["type"] as? JsonPrimitive)?.content) {
                        "content_block_start" -> {
                            val block = (obj["content_block"] as? JsonObject)
                            if (block?.get("type")?.let { (it as? JsonPrimitive)?.content } == "tool_use") {
                                val id = (block["id"] as? JsonPrimitive)?.content ?: ""
                                val name = (block["name"] as? JsonPrimitive)?.content ?: ""
                                val index = (obj["index"] as? JsonPrimitive)?.intOrNull
                                if (index != null && id.isNotEmpty()) {
                                    pendingByIndex[index] = id
                                }
                                // Emit with empty arguments; downstream BrainChunk
                                // emits ToolCallStart (id+name, no args) which the
                                // agentic loop will associate with subsequent
                                // input_json_delta events keyed by id.
                                emit(ProviderChunk(toolCall = ToolCall(id, name, "")))
                            }
                        }
                        "content_block_delta" -> {
                            val delta = (obj["delta"] as? JsonObject) ?: continue
                            when ((delta["type"] as? JsonPrimitive)?.content) {
                                "text_delta" -> {
                                    val text = (delta["text"] as? JsonPrimitive)?.content
                                    if (text != null) emit(ProviderChunk(text = text))
                                }
                                "thinking_delta" -> {
                                    val thinking = (delta["thinking"] as? JsonPrimitive)?.content
                                    if (thinking != null) emit(ProviderChunk(thinking = thinking))
                                }
                                "input_json_delta" -> {
                                    val partial = (delta["partial_json"] as? JsonPrimitive)?.content
                                    if (partial != null) {
                                        // Resolve the tool id by `index` so parallel
                                        // tool_use blocks route their deltas
                                        // correctly. Fall back to "" for legacy
                                        // Brain fallback if index is missing.
                                        val index = (obj["index"] as? JsonPrimitive)?.intOrNull
                                        val id = index?.let { pendingByIndex[it] } ?: ""
                                        emit(ProviderChunk(toolCall = ToolCall(id, "", partial)))
                                    }
                                }
                            }
                        }
                        "content_block_stop" -> {
                            // Drop the index mapping once the block is complete
                            // to keep the map small across long streams.
                            (obj["index"] as? JsonPrimitive)?.intOrNull?.let {
                                pendingByIndex.remove(it)
                            }
                        }
                        // `message_stop` is the protocol-level end-of-stream
                        // signal. We DO NOT emit a `FinishReason.stop` chunk
                        // here — the `message_delta` event already emitted the
                        // real stop reason (tool_use / end_turn / max_tokens)
                        // one event earlier, and emitting a second finish
                        // reason would overwrite the loop's `finishReason`
                        // variable, causing the loop to exit cleanly and skip
                        // tool execution. EOF (readUtf8Line returning null)
                        // is the natural end-of-stream signal.
                        "message_stop" -> { /* no-op: see comment above */ }
                        "message_delta" -> {
                            val stop = (obj["delta"] as? JsonObject)?.get("stop_reason")
                            val reason = when ((stop as? JsonPrimitive)?.content) {
                                "end_turn" -> FinishReason.stop
                                "max_tokens" -> FinishReason.length
                                "tool_use" -> FinishReason.tool_calls
                                else -> null
                            }
                            if (reason != null) emit(ProviderChunk(finishReason = reason))
                        }
                    }
                }
            }
            } // withTimeout
            } finally {
                cancellationGuard.cancelAndJoin()
                if (activeCall === call) activeCall = null
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun listModels(): List<String> {
        val key = apiKey()
        return try {
            runInterruptible(Dispatchers.IO) {
                val request = Request.Builder()
                .url("$baseUrl$modelsEndpoint?limit=100")
                .addHeader("x-api-key", key)
                .addHeader("anthropic-version", "2023-06-01")
                .build()
                httpClient.newCall(request).execute().use { response ->
                    when (response.code) {
                        401 -> throw ProviderCatalogException.AuthenticationException()
                        429 -> throw ProviderCatalogException.RateLimitedException(
                            retryAfterMs = response.header("Retry-After")?.toLongOrNull()?.times(1_000L),
                        )
                        in 200..299 -> Unit
                        else -> throw ProviderCatalogException.NetworkException(
                            message = "Anthropic catalog request failed with HTTP ${response.code}.",
                            statusCode = response.code,
                        )
                    }
                    val body = response.body?.string()?.takeIf(String::isNotBlank)
                        ?: throw ProviderCatalogException.MalformedResponseException(
                            "Anthropic returned an empty model catalog response.",
                        )
                    val data = try {
                        Json.parseToJsonElement(body).jsonObject["data"] as? JsonArray
                            ?: throw ProviderCatalogException.MalformedResponseException(
                                "Missing data[] in Anthropic response.",
                            )
                    } catch (e: ProviderCatalogException) {
                        throw e
                    } catch (e: Exception) {
                        throw ProviderCatalogException.MalformedResponseException(
                            "Anthropic returned malformed model catalog JSON.",
                            e,
                        )
                    }
                    data.mapNotNull { item ->
                        (item as? JsonObject)?.get("id")?.let { it as? JsonPrimitive }?.content
                    }.filter(String::isNotBlank)
                        .ifEmpty { throw ProviderCatalogException.EmptyCatalogException() }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderCatalogException) {
            throw e
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw ProviderCatalogException.NetworkException(cause = e)
        } catch (e: Exception) {
            throw ProviderCatalogException.MalformedResponseException(
                "Anthropic model catalog could not be read.",
                e,
            )
        }
    }

    override suspend fun cancel() {
        activeCall?.cancel()
        activeCall = null
    }

    /**
     * Anthropic's /v1/models endpoint does NOT return a
     * context_window field — only the model id. So we
     * look up the context from [ProviderContextWindows]
     * (hardcoded snapshot, falls through to null for
     * unknown models). The compactor handles null by
     * using its 32K default.
     */
    override suspend fun listModelsWithContext(): List<ModelInfo> {
        return listModels().map { name ->
            ModelInfo(name = name, contextWindow = ProviderContextWindows.lookup(prefix, name))
        }
    }

    private fun splitSystem(messages: List<ProviderMessage>): Pair<String?, List<ProviderMessage>> {
        val sys = messages.filter { it.role == ProviderMessage.Role.system }.joinToString("\n\n") { it.content }
        val rest = messages.filter { it.role != ProviderMessage.Role.system }
        return sys.ifBlank { null } to rest
    }

    /**
     * Anthropic Messages wire shape. The API accepts only user/assistant
     * roles: assistant tool calls become `tool_use` content blocks, and
     * `role=tool` results become `tool_result` blocks inside a user
     * message. Adjacent same-role messages are merged because the API
     * requires user/assistant alternation.
     */
    private fun buildAnthropicMessages(messages: List<ProviderMessage>): JsonArray {
        val out = mutableListOf<Pair<String, MutableList<JsonObject>>>() // role -> content blocks
        fun appendBlocks(role: String, blocks: List<JsonObject>) {
            if (blocks.isEmpty()) return
            val last = out.lastOrNull()
            if (last != null && last.first == role) {
                last.second += blocks
            } else {
                out += role to blocks.toMutableList()
            }
        }
        for (msg in messages) {
            when (msg.role) {
                ProviderMessage.Role.tool -> appendBlocks("user", listOf(buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", msg.toolCallId ?: "")
                    put("content", msg.content)
                }))
                ProviderMessage.Role.assistant -> {
                    val blocks = mutableListOf<JsonObject>()
                    if (msg.content.isNotBlank()) {
                        blocks += buildJsonObject {
                            put("type", "text")
                            put("text", msg.content)
                        }
                    }
                    for (call in msg.toolCalls.orEmpty()) {
                        blocks += buildJsonObject {
                            put("type", "tool_use")
                            put("id", call.id)
                            put("name", call.name)
                            put("input", parseArgsObject(call.arguments))
                        }
                    }
                    appendBlocks("assistant", blocks)
                }
                else -> appendBlocks("user", listOf(buildJsonObject {
                    put("type", "text")
                    put("text", msg.content)
                }))
            }
        }
        return JsonArray(out.map { (role, blocks) ->
            buildJsonObject {
                put("role", role)
                put("content", JsonArray(blocks))
            }
        })
    }

    /** Tool-call arguments as a JSON object; empty object when unparseable. */
    private fun parseArgsObject(arguments: String): JsonObject =
        runCatching { Json.parseToJsonElement(arguments) as? JsonObject }.getOrNull()
            ?: buildJsonObject {}

    companion object {
        private const val STREAM_READ_TIMEOUT_MS = 5L * 60L * 1000L
    }
}
