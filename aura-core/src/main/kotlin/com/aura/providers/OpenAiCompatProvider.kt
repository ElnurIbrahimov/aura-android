package com.aura.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
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
 * Models are always discovered live from `/models`. There is deliberately no
 * hardcoded-list escape hatch: a `defaultModels` constructor parameter used
 * to exist for that, went unused by every one of the fifteen providers built
 * on this class, and existed only as an invitation to bake in ids that rot.
 * The one provider that did hardcode its catalog — ChatGPT Subscription —
 * ended up advertising nine model ids that the backend no longer offered.
 */
open class OpenAiCompatProvider(
    override val prefix: String,
    override val displayName: String,
    protected val baseUrl: String,
    protected val providerKeys: ProviderKeys,
    protected val httpClient: OkHttpClient,
) : Provider {

    @Volatile private var activeEventSource: EventSource? = null

    /**
     * Every OpenAI-compatible base URL exposes this path by convention.
     *
     * Advertised unconditionally rather than gated on a per-provider allowlist:
     * such a list would need updating whenever a provider adds image support,
     * and being absent from it is a silent "no" that nobody would think to
     * check. Providers that do not serve images return an HTTP error, which
     * `ImageGenTool` already handles by falling back to Pollinations — a
     * visible failure with a working result, rather than an invisible one.
     */
    override val imagesEndpoint: String get() = "$baseUrl/images/generations"

    /**
     * The remaining OpenAI-shaped capability paths, by the same convention and
     * for the same reason: advertised unconditionally rather than gated on a
     * per-provider allowlist, because such a list rots and absence from it is a
     * silent "no". A provider that does not serve one returns an HTTP error,
     * which the capability tools handle visibly.
     *
     * These are what let a provider's non-chat models be reached at all. A
     * catalog entry classified as [ModelCapability.Video] is useless without a
     * URL to POST it to, which is why discovery pairs the two.
     */
    override val videosEndpoint: String get() = "$baseUrl/videos"
    override val speechEndpoint: String get() = "$baseUrl/audio/speech"
    override val transcriptionsEndpoint: String get() = "$baseUrl/audio/transcriptions"

    /**
     * The current API key, looked up at call time. Returns blank if the user
     * hasn't set a key for this provider; [isConfigured] will return false in
     * that case and the chat request will fail with a clear 401.
     */
    private suspend fun apiKey(): String = providerKeys.keyForAwaiting(prefix) ?: ""

    override fun isConfigured(): Boolean = providerKeys.isConfigured(prefix)

    override fun chat(
        model: String,
        messages: List<ProviderMessage>,
        options: ChatOptions,
        tools: List<ToolDefinition>,
    ): Flow<ProviderChunk> = flow {
        val key = apiKey()
        val request = buildRequest(model, messages, options, tools, stream = true, key = key)
        val channel = kotlinx.coroutines.channels.Channel<ProviderChunk>(capacity = kotlinx.coroutines.channels.Channel.UNLIMITED)
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
                // 400/401/403/404/422 are not retryable — a bad request,
                // bad key, forbidden, unknown model/endpoint, or invalid
                // payload won't fix itself on retry. 429 and 5xx (and
                // pure network failures, code 0) are retryable.
                val code = response?.code ?: 0
                val retryable = code !in NON_RETRYABLE_STATUS_CODES
                val retryAfterMs = if (code == 429) parseRetryAfterMs(response) else null
                val message = failureMessage(t, response, key)
                android.util.Log.w("OpenAiCompat", "$prefix/$model stream failed: $message")
                channel.trySend(ProviderChunk(error = ProviderError("http_error", message, retryable = retryable, retryAfterMs = retryAfterMs)))
                channel.close()
            }
            override fun onClosed(eventSource: EventSource) { channel.close() }
        }
        sourceHolder.source = EventSources.createFactory(httpClient).newEventSource(request, listener)
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
            // Cancel only THIS stream's EventSource so the remote stops
            // generating billable tokens. The shared field is cleared behind
            // an identity check — this @Singleton serves concurrent streams
            // (chat + WriteGate + MoA references), and cancelling through the
            // shared field used to kill whichever stream wrote it last.
            sourceHolder.cancel()
            if (activeEventSource === sourceHolder) activeEventSource = null
        }
    }
    /**
     * Chat-usable models only. Non-chat entries are filtered out here because
     * every consumer of this method is conversational — the picker, the model
     * catalog, the agentic loop's failover. Capability discovery needs the
     * unfiltered view: see [listModelsWithCapability].
     */
    override suspend fun listModels(): List<String> =
        fetchCatalog().filter { it.capability.isChatUsable }.map { it.name }
            .ifEmpty { throw ProviderCatalogException.EmptyCatalogException() }

    /**
     * Every catalog entry, classified — nothing filtered.
     *
     * Note it does NOT throw [ProviderCatalogException.EmptyCatalogException]
     * on an all-non-chat catalog, unlike [listModels]: a provider that serves
     * only image models has a perfectly good catalog, it just has nothing to
     * say to a chat picker.
     */
    override suspend fun listModelsWithCapability(): List<ClassifiedModel> = fetchCatalog()

    /** Fetch and classify `/models` once; both public views read from this. */
    private suspend fun fetchCatalog(): List<ClassifiedModel> = withContext(Dispatchers.IO) {
        val key = apiKey()
        try {
            val request = Request.Builder()
                .url("$baseUrl/models")
                .addHeader("Authorization", "Bearer $key")
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
                    val obj = item as? JsonObject ?: return@mapNotNull null
                    val id = (obj["id"] as? JsonPrimitive)?.content?.takeIf(String::isNotBlank)
                        ?: return@mapNotNull null
                    ClassifiedModel(name = id, capability = classify(obj, id))
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

    /**
     * Whether a `/models` catalog entry can hold a conversation.
     *
     * `/v1/models` is not a list of chat models. Agnes AI returns
     * `agnes-image-2.1-flash` alongside its chat models, and selecting it in
     * the chat picker produced:
     *
     *     HTTP 400 {"code":"invalid_request","message":"Model
     *     agnes-image-2.1-flash is an image model. Use /v1/images/generations."}
     *
     * — a model in the picker that could never answer. `f69f353a` fixed this
     * for ChatGPT and Gemini, but per-provider; every OpenAI-compatible
     * provider shared this unfiltered path.
     *
     * Two-stage, and the order matters. Ask the catalog first: some providers
     * label entries (`type`, `object`, `capabilities`, `modality`), and a
     * declared answer beats any guess. Only when the entry declares nothing do
     * we fall back to the id, which [GeminiProvider.supportsChat] rightly warns
     * against as a sole strategy — "new families keep arriving and a name-based
     * filter would silently drop real chat models or admit new image ones."
     *
     * The fallback is therefore deliberately narrow: whole `-` or `_` delimited
     * segments only, so `gpt-image-1` is excluded but a hypothetical
     * `imagen-reasoner` or `visionary-7b` is not. An unrecognised entry is
     * KEPT — a model that errors on use is a smaller failure than a real chat
     * model silently missing from the picker.
     */
    internal fun canChat(entry: JsonObject, id: String): Boolean =
        classify(entry, id).isChatUsable

    /**
     * What a `/models` catalog entry actually is.
     *
     * The same two-stage rule [canChat] documents, but keeping the answer
     * instead of collapsing it to a boolean. Knowing a model is specifically an
     * *image* model — not merely "not chat" — is what lets a configured token's
     * non-chat models be routed to the right capability backend automatically,
     * rather than requiring a hand-written adapter per vendor.
     *
     * Returns [ModelCapability.Unknown] rather than guessing, and `Unknown`
     * counts as chat-usable, preserving the bias [canChat] established: a model
     * that errors when used announces itself, a real chat model missing from
     * the picker does not.
     */
    internal fun classify(entry: JsonObject, id: String): ModelCapability {
        val declared = listOf("type", "object", "modality", "model_type")
            .mapNotNull { (entry[it] as? JsonPrimitive)?.content?.lowercase() }
        if (declared.isNotEmpty()) {
            // "model" is OpenAI's generic `object` value and says nothing about
            // capability, so it does not count as a declaration either way.
            val informative = declared.filter { it != "model" }
            if (informative.isNotEmpty()) {
                informative.firstNotNullOfOrNull { DECLARATION_CAPABILITY[it] }?.let { return it }
                // Declared, and none of the declarations names a non-chat kind.
                return ModelCapability.Chat
            }
        }
        val segments = id.lowercase().split('-', '_', '/', '.').toSet()
        return segments.firstNotNullOfOrNull { ID_SEGMENT_CAPABILITY[it] } ?: ModelCapability.Unknown
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
        key: String,
    ): Request {
        val body = buildJsonObject {
            put("model", model)
            put("stream", stream)
            put("temperature", options.temperature ?: ChatOptions.DEFAULT_TEMPERATURE)
            put("top_p", options.topP ?: ChatOptions.DEFAULT_TOP_P)
            options.maxTokens?.let { put("max_tokens", it) }
            // Extended thinking — provider-specific injection.
            // OpenAI o-series uses reasoning_effort. DeepSeek uses both
            // reasoning_effort and thinking:{type:enabled}. Ollama uses
            // think:true/high. Subclasses can override injectThinking().
            injectThinking(this, options.thinkingBudget)
            put("messages", JsonArray(messages.map { it.toOpenAiJson() }))
            if (tools.isNotEmpty()) {
                put("tools", JsonArray(tools.map { tool ->
                    buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.parameters.toJsonSchema())
                        })
                    }
                }))
            }
        }
        // Log the tuning parameters we put on the wire (never the messages —
        // those are the user's conversation). When a provider 400s, the
        // rejected parameter is almost always in here.
        android.util.Log.d(
            "OpenAiCompat",
            "$prefix request: " + JsonObject(body.filterKeys { it != "messages" && it != "tools" }) +
                " toolCount=${tools.size}",
        )
        return Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    /**
     * Inject provider-specific extended thinking / reasoning parameters
     * into the request body. Subclasses override this to use their
     * provider's native thinking API.
     *
     * Default: OpenAI o-series `reasoning_effort` (low/medium/high).
     * Override in [OllamaCloudProvider] for Ollama's `think` parameter.
     * Override in DeepSeek subclasses for `thinking:{type:enabled}`.
     */
    protected open fun injectThinking(body: kotlinx.serialization.json.JsonObjectBuilder, budget: Int?) {
        if (budget == null) return
        val effort = when {
            budget >= 20_000 -> "high"
            budget >= 8_000 -> "medium"
            else -> "low"
        }
        body.put("reasoning_effort", effort)
    }

    companion object {
        // 5 minutes: long enough for a slow model + max_tokens worth of tokens,
        // short enough that a misbehaving server doesn't hang the agent loop.
        const val STREAM_READ_TIMEOUT_MS = 5L * 60L * 1000L

        /** HTTP statuses that won't fix themselves on retry/failover. */
        internal val NON_RETRYABLE_STATUS_CODES = setOf(400, 401, 403, 404, 422)

        /**
         * A catalog entry's declared type/modality mapped to what it can do.
         * Consulted before any id heuristic — see [classify].
         */
        internal val DECLARATION_CAPABILITY: Map<String, ModelCapability> = mapOf(
            "image" to ModelCapability.Image,
            "images" to ModelCapability.Image,
            "image_generation" to ModelCapability.Image,
            "text-to-image" to ModelCapability.Image,
            "video" to ModelCapability.Video,
            "video_generation" to ModelCapability.Video,
            "text-to-video" to ModelCapability.Video,
            "embedding" to ModelCapability.Embedding,
            "embeddings" to ModelCapability.Embedding,
            "text_embedding" to ModelCapability.Embedding,
            "audio" to ModelCapability.Speech,
            "speech" to ModelCapability.Speech,
            "tts" to ModelCapability.Speech,
            "text-to-speech" to ModelCapability.Speech,
            "transcription" to ModelCapability.Transcription,
            "stt" to ModelCapability.Transcription,
            "moderation" to ModelCapability.Moderation,
            "rerank" to ModelCapability.Rerank,
            "reranker" to ModelCapability.Rerank,
        )

        /** Declared values that mean "not a chat model", derived from [DECLARATION_CAPABILITY]. */
        internal val NON_CHAT_DECLARATIONS: Set<String> = DECLARATION_CAPABILITY.keys

        /**
         * Id segments that identify a non-chat model when the entry declares
         * nothing. Matched as whole `-`/`_`/`/`/`.` delimited segments, never as
         * substrings: "image" must not match "imagen-reasoner", and "tts" must
         * not match "gpt-4o-tts-preview"'s neighbours. Narrow on purpose — an
         * unrecognised entry is kept, because a model that errors when used is
         * a smaller failure than a real chat model missing from the picker.
         */
        internal val ID_SEGMENT_CAPABILITY: Map<String, ModelCapability> = buildMap {
            listOf(
                // "dall" rather than "dall-e": splitting on '-' makes the
                // segments of `dall-e-3` [dall, e, 3], and "e" is far too short
                // to match on.
                "image", "images", "imagegen", "dall", "midjourney", "flux", "stability", "sd",
            ).forEach { put(it, ModelCapability.Image) }
            listOf("video", "veo", "sora", "kling", "runway").forEach { put(it, ModelCapability.Video) }
            listOf("embed", "embedding", "embeddings").forEach { put(it, ModelCapability.Embedding) }
            listOf("tts", "speech").forEach { put(it, ModelCapability.Speech) }
            // "audio" is ambiguous — OpenAI uses it for both directions. Treated
            // as transcription because that is the commoner audio-in model, and
            // because a wrong non-chat answer only affects which capability
            // list it appears in, never whether it is offered for chat.
            listOf("stt", "whisper", "transcribe", "audio").forEach { put(it, ModelCapability.Transcription) }
            put("moderation", ModelCapability.Moderation)
            listOf("rerank", "reranker").forEach { put(it, ModelCapability.Rerank) }
        }

        /** Id segments that mean "not a chat model", derived from [ID_SEGMENT_CAPABILITY]. */
        internal val NON_CHAT_ID_SEGMENTS: Set<String> = ID_SEGMENT_CAPABILITY.keys

        /**
         * Parse a `Retry-After` header (delta-seconds form) into
         * milliseconds. HTTP-date form is ignored — no provider in the
         * catalog uses it and parsing dates buys nothing here.
         */
        internal fun parseRetryAfterMs(response: okhttp3.Response?): Long? =
            response?.header("Retry-After")?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.times(1_000L)

        /** Max bytes of a provider error body we surface. Enough for any JSON error envelope. */
        private const val ERROR_BODY_LIMIT = 2_048L

        /**
         * Build the user-facing message for a failed stream.
         *
         * A bare `"HTTP 400"` is undebuggable — every OpenAI-compatible
         * provider explains WHY it rejected the request in the response
         * body ("Invalid max_tokens", "unknown field `thinking`", "model
         * not found"), and we used to throw that away. `peekBody` reads
         * without consuming the source, so the SSE machinery is unaffected.
         *
         * [apiKey] is redacted out of the body before it is surfaced —
         * some providers echo the offending Authorization value back in
         * a 401/403 body, and this message reaches the chat UI and the
         * error log. Same no-key-leakage contract the catalog path holds
         * (see OpenAiCompatProviderMockWebServerTest).
         */
        internal fun failureMessage(t: Throwable?, response: okhttp3.Response?, apiKey: String = ""): String {
            val code = response?.code ?: 0
            val body = response?.let { r ->
                runCatching { r.peekBody(ERROR_BODY_LIMIT).string() }.getOrNull()
            }?.trim()?.takeIf { it.isNotEmpty() }?.let { redactKey(it, apiKey) }
            return when {
                body != null -> "HTTP $code: $body"
                t != null -> redactKey(t.message ?: t.toString(), apiKey)
                else -> "HTTP $code"
            }
        }

        /** Replace any echo of the caller's API key with a placeholder. */
        private fun redactKey(text: String, apiKey: String): String =
            if (apiKey.length >= MIN_REDACTABLE_KEY_LENGTH) text.replace(apiKey, "***") else text

        /**
         * Below this length a "key" is more likely a test stub or empty
         * string, and blind replacement would mangle unrelated text.
         */
        private const val MIN_REDACTABLE_KEY_LENGTH = 8
    }

}
