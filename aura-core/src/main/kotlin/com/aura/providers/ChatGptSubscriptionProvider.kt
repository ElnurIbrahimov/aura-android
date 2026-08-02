package com.aura.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ChatGPT subscription auth via OAuth bearer token.
 *
 * The user gets a ChatGPT Plus/Pro/Go subscription token by running
 * `codex login` on a machine with a browser, then exporting the resulting
 * token (or CODEX_AUTH_JSON) to Aura via the Settings UI. The token is
 * then routed through OpenAI's ChatGPT Responses API as a Bearer token
 * (Authorization: Bearer <access_token>), which is what the Codex CLI does.
 *
 * Model ids for this provider are derived from the openai catalog (the
 * user can also use a model catalog cached by the openai provider) but
 * routed through the subscription token. We treat the live token from
 * the user as the source of truth; if the user wants to use their
 * OpenAI API key instead, they can use the regular `openai` provider.
 */
class ChatGptSubscriptionProvider(
    private val providerKeys: ProviderKeys,
    private val httpClient: OkHttpClient,
    /**
     * Base URL for the OpenAI ChatGPT backend. Override in tests; default
     * is the production Codex/ChatGPT endpoint.
     */
    private val baseUrl: String = "https://chatgpt.com/backend-api/codex",
) : Provider {
    override val prefix = "chatgpt"
    override val displayName = "ChatGPT Subscription"
    private val apiKey: String get() = providerKeys.keyFor(prefix).orEmpty()
    @Volatile private var activeEventSource: EventSource? = null

    override fun isConfigured(): Boolean = apiKey.isNotBlank()

    override fun chat(
        model: String,
        messages: List<ProviderMessage>,
        options: ChatOptions,
        tools: List<ToolDefinition>,
    ): Flow<ProviderChunk> = flow {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("temperature", options.temperature)
            put("top_p", options.topP)
            options.maxTokens?.let { put("max_tokens", it) }
            // ChatGPT Responses API supports reasoning_effort
            options.thinkingBudget?.let { budget ->
                val effort = when {
                    budget >= 20_000 -> "high"
                    budget >= 8_000 -> "medium"
                    else -> "low"
                }
                put("reasoning_effort", effort)
            }
            put("input", JsonArray(messages.map { msg ->
                buildJsonObject {
                    put("role", msg.role.name)
                    put("content", msg.content)
                }
            }))
            // Tool declarations (OpenAI function calling format)
            if (tools.isNotEmpty()) {
                put("tools", JsonArray(tools.map { tool ->
                    buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject {
                                    tool.parameters.properties.forEach { (key, prop) ->
                                        put(key, buildJsonObject {
                                            put("type", prop.type)
                                            prop.description?.let { put("description", it) }
                                        })
                                    }
                                })
                                if (tool.parameters.required.isNotEmpty()) {
                                    put("required", JsonArray(tool.parameters.required.map { JsonPrimitive(it) }))
                                }
                            })
                        })
                    }
                }))
            }
        }
        val request = Request.Builder()
            .url("$baseUrl/responses")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("OpenAI-Beta", "responses=experimental")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val channel = kotlinx.coroutines.channels.Channel<ProviderChunk>(capacity = kotlinx.coroutines.channels.Channel.BUFFERED)
        val sourceHolder = EventSourceHolder()
        activeEventSource = sourceHolder
        // P0-AGENTIC-F2: track tool_calls by index so parallel tool-call deltas
        // route to the correct ToolCall. The responses API may send multiple
        // function_call events in one response; chat-completions-style deltas
        // include an `index` field.
        val toolCallsByIndex = mutableMapOf<Int, ToolCallBuilder>()
        // Per-stream counter for synthetic tool call ids to prevent
        // id collisions when parallel calls arrive in the same millisecond.
        var toolCallCounter = 0
        EventSources.createFactory(httpClient).newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                sourceHolder.source = eventSource
                activeEventSource = eventSource
                if (data == "[DONE]") { channel.trySend(ProviderChunk(finishReason = FinishReason.stop)); channel.close(); return }
                val obj = try { Json.parseToJsonElement(data).jsonObject } catch (e: Exception) { return }
                // The responses API streams "output_text.delta" events. Fall back to chat-completions style for forward compat.
                val text = (obj["delta"] as? JsonObject)?.get("text").let { (it as? JsonPrimitive)?.content }
                    ?: (obj["output_text"] as? JsonPrimitive)?.content
                if (text != null) channel.trySend(ProviderChunk(text = text))
                // Parse tool calls from the Responses API, index-aware.
                val delta = obj["delta"] as? JsonObject
                val toolCallArray = delta?.get("tool_calls")?.jsonArray
                if (toolCallArray != null) {
                    for (item in toolCallArray) {
                        val toolCallObj = item.jsonObject
                        val idx = toolCallObj["index"]?.jsonPrimitive?.intOrNull ?: 0
                        val builder = toolCallsByIndex.getOrPut(idx) { ToolCallBuilder() }
                        toolCallObj["id"]?.jsonPrimitive?.content?.let { builder.id = it }
                        val fnObj = toolCallObj["function"]?.jsonObject
                        fnObj?.get("name")?.jsonPrimitive?.content?.let { builder.name = it }
                        fnObj?.get("arguments")?.jsonPrimitive?.content?.let { builder.arguments.append(it) }
                        if (builder.isComplete()) {
                            channel.trySend(ProviderChunk(toolCall = builder.toToolCall()))
                            toolCallsByIndex.remove(idx)
                        }
                    }
                } else {
                    val toolCallObj = delta?.get("tool_call")?.jsonObject
                        ?: obj["tool_call"]?.jsonObject
                    if (toolCallObj != null) {
                        val fnName = (toolCallObj["name"] ?: toolCallObj["function"]?.jsonObject?.get("name"))?.jsonPrimitive?.content ?: ""
                        val fnArgs = (toolCallObj["arguments"] ?: toolCallObj["function"]?.jsonObject?.get("arguments"))?.jsonPrimitive?.content ?: "{}"
                        val callId = toolCallObj["id"]?.jsonPrimitive?.content
                            ?: "chatgpt_${System.currentTimeMillis()}_${toolCallCounter++}_${fnName.hashCode()}"
                        if (fnName.isNotBlank()) {
                            channel.trySend(ProviderChunk(toolCall = ToolCall(id = callId, name = fnName, arguments = fnArgs)))
                        }
                    }
                }
                val done = (obj["type"] as? JsonPrimitive)?.content in setOf("response.completed", "response.done")
                if (done) channel.trySend(ProviderChunk(finishReason = FinishReason.stop))
            }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                val code = response?.code ?: 0
                // 401/400/403 are not retryable — bad key, bad request,
                // or forbidden won't fix themselves. 429 (rate limit) and
                // 5xx (server error) benefit from failover to another
                // configured provider.
                val retryable = code == 429 || code in 500..599
                channel.trySend(ProviderChunk(error = ProviderError("http_error", t?.message ?: "HTTP $code", retryable = retryable)))
                channel.close()
            }
            override fun onClosed(eventSource: EventSource) { channel.close() }
        })
        try {
            // 5-min defensive timeout matching OpenAiCompatProvider. The
            // ChatGPT Responses API occasionally stops sending chunks
            // mid-stream without closing; without this, the for-loop
            // would suspend forever and the agent loop would hang.
            kotlinx.coroutines.withTimeout(STREAM_READ_TIMEOUT_MS) {
                for (chunk in channel) emit(chunk)
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            // Synthesize a finish so downstream loops terminate cleanly.
            emit(ProviderChunk(finishReason = FinishReason.stop))
        } finally {
            activeEventSource?.cancel()
            activeEventSource = null
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        // 5 minutes: long enough for a slow model + max_tokens worth of
        // tokens, short enough that a misbehaving server doesn't hang
        // the agent loop. Mirrors OpenAiCompatProvider.STREAM_READ_TIMEOUT_MS.
        const val STREAM_READ_TIMEOUT_MS = 5L * 60L * 1000L
    }

    override suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        // The ChatGPT subscription token authenticates against
        // chatgpt.com/backend-api/codex, NOT api.openai.com. The
        // /v1/models endpoint will always 401 with a subscription
        // token because it's a session token, not an API key.
        // The chatgpt.com backend doesn't expose a models-listing
        // endpoint, so we return the known ChatGPT Plus/Pro/Go model
        // set directly. Users who want the full OpenAI catalog should
        // use the regular `openai` provider with an API key.
        listOf(
            "gpt-5", "gpt-5-mini", "gpt-5-nano",
            "gpt-4.1", "gpt-4.1-mini",
            "gpt-4o", "gpt-4o-mini",
            "o3", "o4-mini",
        )
    }

    override suspend fun cancel() {
        activeEventSource?.cancel()
        activeEventSource = null
    }

/**
 * Mutable accumulator for streaming OpenAI tool-call deltas.
 */
private class ToolCallBuilder {
    var id: String = ""
    var name: String = ""
    val arguments = StringBuilder()

    fun isComplete(): Boolean = id.isNotBlank() && name.isNotBlank()
    fun toToolCall(): ToolCall = ToolCall(id = id, name = name, arguments = arguments.toString().ifBlank { "{}" })
}
}
