package com.aura.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
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
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/**
 * Generic provider for any OpenAI-compatible chat completions API.
 *
 * Works with OpenAI, DeepSeek, Ollama Cloud, Groq, NVIDIA, vLLM, Together, Fireworks,
 * and any other service that exposes `/v1/chat/completions` and `/v1/models`.
 *
 * The API key is read from [ProviderKeys] on every [chat] call, not at
 * construction time, so changes the user makes in the Settings UI take
 * effect immediately without restarting the app.
 *
 * Subclasses can override [defaultModels] to provide a hardcoded list instead
 * of fetching from the `/models` endpoint.
 */
open class OpenAiCompatProvider(
    override val prefix: String,
    override val displayName: String,
    protected val baseUrl: String,
    protected val providerKeys: ProviderKeys,
    protected val httpClient: OkHttpClient,
    protected val defaultModels: List<String> = emptyList(),
) : Provider {

    @Volatile private var activeEventSource: EventSource? = null

    /**
     * The current API key, looked up at call time. Returns blank if the user
     * hasn't set a key for this provider; [isConfigured] will return false in
     * that case and the chat request will fail with a clear 401.
     */
    private val apiKey: String get() = providerKeys.keyFor(prefix) ?: ""

    override fun isConfigured(): Boolean = providerKeys.isConfigured(prefix)

    override fun chat(
        model: String,
        messages: List<ProviderMessage>,
        options: ChatOptions,
        tools: List<ToolDefinition>,
    ): Flow<ProviderChunk> = flow {
        val request = buildRequest(model, messages, options, tools, stream = true)
        val channel = kotlinx.coroutines.channels.Channel<ProviderChunk>(capacity = kotlinx.coroutines.channels.Channel.BUFFERED)
        val sseParser = OpenAiSseParser()
        // P0-PROVIDERS-CANCEL: wrap the listener so the source reference is
        // visible to cancel() immediately. We assign a mutable holder first,
        // pass it into the listener, then create the EventSource with that
        // listener. The holder is updated in onOpen and is also checked by
        // cancel() as a fallback.
        val sourceHolder = EventSourceHolder()
        activeEventSource = sourceHolder
        val listener = object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                sourceHolder.source = eventSource
                activeEventSource = eventSource
                val chunks = sseParser.parseEvent(data)
                // P0-AGENTIC-F1: parseEvent returns a list to support parallel
                // tool calls batched in a single SSE event. Emit every chunk;
                // close the channel only when the stream signals finish.
                var finished = false
                for (chunk in chunks) {
                    channel.trySend(chunk)
                    if (chunk.finishReason != null) finished = true
                }
                if (finished) channel.close()
            }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                // 401/400/403 are not retryable — retrying with the same
                // key won't help. 429 and 5xx are retryable.
                val code = response?.code ?: 0
                val retryable = code != 401 && code != 400 && code != 403
                channel.trySend(ProviderChunk(error = ProviderError("http_error", t?.message ?: "HTTP $code", retryable = retryable)))
                channel.close()
            }
            override fun onClosed(eventSource: EventSource) { channel.close() }
        }
        EventSources.createFactory(httpClient).newEventSource(request, listener)
        // Bound the per-request read so a misbehaving server that never sends
        // [DONE] and never closes the stream can't hang the agent forever. The
        // OkHttp read timeout (configured in ProviderModule) is the primary
        // backstop; this is a defensive upper bound.
        try {
            kotlinx.coroutines.withTimeout(STREAM_READ_TIMEOUT_MS) {
                for (chunk in channel) emit(chunk)
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            // Synthesize a finish so downstream loops terminate cleanly.
            emit(ProviderChunk(finishReason = FinishReason.stop))
        } finally {
            // Cancel the EventSource so the remote stream stops generating
            // billable tokens. Without this, hitting "stop" in the UI only
            // stops consuming the channel — the remote model keeps generating
            // to completion.
            activeEventSource?.cancel()
            activeEventSource = null
            sourceHolder.cancel()
        }
    }
    override suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        if (defaultModels.isNotEmpty()) return@withContext defaultModels
        try {
            val request = Request.Builder()
                .url("$baseUrl/models")
                .addHeader("Authorization", "Bearer $apiKey")
                .build()
            httpClient.newCall(request).execute().use { response ->
                when (response.code) {
                    401 -> throw ProviderCatalogException.AuthenticationException()
                    429 -> {
                        val retryAfterMs = response.header("Retry-After")
                            ?.toLongOrNull()
                            ?.times(1_000L)
                        throw ProviderCatalogException.RateLimitedException(retryAfterMs = retryAfterMs)
                    }
                    in 200..299 -> Unit
                    else -> throw ProviderCatalogException.NetworkException(
                        message = "Provider catalog request failed with HTTP ${response.code}.",
                        statusCode = response.code,
                    )
                }
                val body = response.body?.string()?.takeIf { it.isNotBlank() }
                    ?: throw ProviderCatalogException.MalformedResponseException(
                        "Provider returned an empty model catalog response.",
                    )
                val data = try {
                    val root = Json.parseToJsonElement(body).jsonObject
                    root["data"] as? JsonArray
                        ?: throw ProviderCatalogException.MalformedResponseException(
                            "Missing data[] in provider response.",
                        )
                } catch (e: ProviderCatalogException) {
                    throw e
                } catch (e: Exception) {
                    throw ProviderCatalogException.MalformedResponseException(
                        "Provider returned malformed model catalog JSON.",
                        e,
                    )
                }
                data.mapNotNull { item ->
                    (item as? JsonObject)
                        ?.get("id")
                        ?.let { it as? JsonPrimitive }
                        ?.content
                        ?.takeIf(String::isNotBlank)
                }.ifEmpty { throw ProviderCatalogException.EmptyCatalogException() }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: ProviderCatalogException) {
            throw e
        } catch (e: java.io.IOException) {
            throw ProviderCatalogException.NetworkException(cause = e)
        } catch (e: Exception) {
            throw ProviderCatalogException.MalformedResponseException(
                "Provider model catalog could not be read.",
                e,
            )
        }
    }

    override suspend fun cancel() {
        val holder = activeEventSource
        if (holder is EventSourceHolder) {
            holder.cancel()
        } else {
            activeEventSource?.cancel()
        }
        activeEventSource = null
    }

    /**
     * OpenAI-compatible /v1/models does NOT return a
     * context window per model (just id + owned_by). So
     * we fall back to [ProviderContextWindows] which has
     * a snapshot for OpenAI, Groq, ChatGPT. Unknown
     * models return null context — the compactor uses
     * the 32K default. CustomOpenAiCompat (user's own
     * endpoint) and OpenRouter (which DOES return
     * context_length in the response) override this
     * method.
     */
    override suspend fun listModelsWithContext(): List<ModelInfo> {
        return listModels().map { name ->
            ModelInfo(name = name, contextWindow = ProviderContextWindows.lookup(prefix, name))
        }
    }

    private fun buildRequest(
        model: String,
        messages: List<ProviderMessage>,
        options: ChatOptions,
        tools: List<ToolDefinition>,
        stream: Boolean,
    ): Request {
        val body = buildJsonObject {
            put("model", model)
            put("stream", stream)
            put("temperature", options.temperature)
            put("top_p", options.topP)
            options.maxTokens?.let { put("max_tokens", it) }
            put("messages", JsonArray(messages.map { msg ->
                buildJsonObject {
                    put("role", msg.role.name)
                    put("content", msg.content)
                }
            }))
            if (tools.isNotEmpty()) {
                put("tools", JsonArray(tools.map { tool ->
                    buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", Json.parseToJsonElement(Json.encodeToString(ToolParameters.serializer(), tool.parameters)))
                        })
                    }
                }))
            }
        }
        return Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    companion object {
        // 5 minutes: long enough for a slow model + max_tokens worth of tokens,
        // short enough that a misbehaving server doesn't hang the agent loop.
        const val STREAM_READ_TIMEOUT_MS = 5L * 60L * 1000L
    }

/**
 * Holds an [EventSource] that may not be assigned yet. Used as a
 * cancellation bridge during the brief window between SSE source creation
 * and the [onOpen] callback.
 */
private class EventSourceHolder : EventSource {
    @Volatile var source: EventSource? = null

    override fun request(): okhttp3.Request = source?.request() ?: okhttp3.Request.Builder().url("http://localhost").build()
    override fun cancel() { source?.cancel() }
}
}
