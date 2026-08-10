package com.aura.providers

import com.aura.integrations.IntegrationTokenStore
import com.aura.integrations.OAuthFlow
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
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.contentOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * ChatGPT subscription auth via OAuth bearer token.
 *
 * The user signs in with `codex login` on a machine with a browser and pastes
 * the resulting `auth.json` into Settings. Requests then go to OpenAI's
 * ChatGPT Responses API as `Authorization: Bearer <access_token>`, which is
 * what the Codex CLI does.
 *
 * This is an OAuth grant, not an API key, and it used to be stored as one —
 * a bare access token in the `chatgpt_api_key` slot, with nowhere to keep the
 * `refresh_token` sitting beside it in that same file. Access tokens last
 * about an hour, so the provider went dead an hour after every sign-in and the
 * only fix was to paste a fresh token. The grant now lives in
 * [IntegrationTokenStore] alongside its refresh token and expiry, and is
 * renewed on demand.
 */
class ChatGptSubscriptionProvider(
    private val providerKeys: ProviderKeys,
    private val httpClient: OkHttpClient,
    private val tokenStore: IntegrationTokenStore,
    private val oauthFlow: OAuthFlow,
    /**
     * Base URL for the OpenAI ChatGPT backend. Override in tests; default
     * is the production Codex/ChatGPT endpoint.
     */
    private val baseUrl: String = "https://chatgpt.com/backend-api/codex",
) : Provider {
    override val prefix = "chatgpt"
    override val displayName = "ChatGPT Subscription"

    /** Serialised as the Responses-API `text.format` block. */
    override val supportsResponseSchema: Boolean get() = true
    @Volatile private var activeEventSource: EventSource? = null

    /**
     * A currently-valid bearer token, refreshing it first if it has expired.
     *
     * The legacy branch adopts a token pasted into the old API-key field so an
     * upgrading user isn't silently signed out. It can only ever be reached
     * once: the token moves into the OAuth store and the old slot is cleared.
     * If that token is already dead we still return it rather than an empty
     * string — a real 401 from OpenAI names the problem, a blank `Bearer`
     * header just produces a malformed-request error that explains nothing.
     */
    private suspend fun apiKey(): String {
        tokenStore.getValidChatGptAccessToken { oauthFlow.refreshChatGptToken(it) }?.let { return it }

        val legacy = providerKeys.keyForAwaiting(prefix).orEmpty()
        if (legacy.isBlank()) return ""
        if (tokenStore.migrateLegacyChatGptToken(legacy)) {
            providerKeys.set(prefix, "")
        }
        return tokenStore.getValidChatGptAccessToken { oauthFlow.refreshChatGptToken(it) } ?: legacy
    }

    /**
     * The OAuth store is the source of truth; the API-key slot is only
     * consulted so a not-yet-migrated install stays visible to
     * [ProviderRegistry] — which short-circuits the model catalog before any
     * network call when a provider reports itself unconfigured.
     */
    override fun isConfigured(): Boolean =
        tokenStore.chatgptConnected.value || providerKeys.keyFor(prefix).orEmpty().isNotBlank()

    override fun chat(
        model: String,
        messages: List<ProviderMessage>,
        options: ChatOptions,
        tools: List<ToolDefinition>,
    ): Flow<ProviderChunk> = flow {
        val key = apiKey()
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            // Required, and rejected with 400 "store must be set to false" if
            // omitted — the default is true. The subscription backend will not
            // persist responses for this client, so it refuses rather than
            // silently ignoring the request. Nothing infers it from the
            // absence of the field.
            put("store", false)
            // No temperature, top_p or max_tokens. The subscription backend
            // rejects each with 400 "Unsupported parameter: <name>" — checked
            // one at a time against the live endpoint, because sending them
            // meant every message failed while the model list looked fine.
            // Sampling is the server's to decide on a subscription.
            //
            // Reasoning effort is a nested object here. Top-level
            // `reasoning_effort`, which this used to send, is rejected the
            // same way; `reasoning: {effort}` is accepted.
            options.thinkingBudget?.let { budget ->
                val effort = when {
                    budget >= 20_000 -> "high"
                    budget >= 8_000 -> "medium"
                    else -> "low"
                }
                put("reasoning", buildJsonObject { put("effort", effort) })
            }
            // This API insists that function calls and their outputs come in
            // pairs, and rejects the whole request otherwise:
            //   a call with no output    -> 400 No tool output found for function call <id>
            //   an output with no call   -> 400 No tool call found for function call output <id>
            // A history can hold a half-pair for ordinary reasons — the user
            // hit stop mid-tool, the app was killed, or a bug ended the turn
            // before the tool ran. Replaying it verbatim then rejects every
            // future message in that conversation, permanently. Dropping the
            // unmatched half costs one turn of context and keeps the chat
            // usable.
            val answeredCallIds = messages.filter { it.role == ProviderMessage.Role.tool }
                .mapNotNull { it.toolCallId }.toSet()
            val issuedCallIds = messages.flatMap { it.toolCalls.orEmpty() }.map { it.id }.toSet()
            put("input", JsonArray(messages.flatMap { msg ->
                when {
                    // Responses API: tool results are function_call_output items,
                    // matched to the call by call_id — not role-based messages.
                    msg.role == ProviderMessage.Role.tool ->
                        listOfNotNull(
                            msg.toolCallId?.takeIf { it in issuedCallIds }?.let { callId ->
                                buildJsonObject {
                                    put("type", "function_call_output")
                                    put("call_id", callId)
                                    put("output", msg.content)
                                }
                            },
                        )
                    // Assistant tool calls replay as function_call items after
                    // the assistant text (if any).
                    msg.role == ProviderMessage.Role.assistant && !msg.toolCalls.isNullOrEmpty() -> {
                        val items = mutableListOf<JsonObject>()
                        if (msg.content.isNotBlank()) {
                            items += buildJsonObject {
                                put("role", "assistant")
                                put("content", msg.content)
                            }
                        }
                        for (call in msg.toolCalls.filter { it.id in answeredCallIds }) {
                            items += buildJsonObject {
                                put("type", "function_call")
                                put("call_id", call.id)
                                put("name", call.name)
                                put("arguments", call.arguments)
                            }
                        }
                        items
                    }
                    else -> listOf(buildJsonObject {
                        // The Responses API expects "developer" for system messages,
                        // not "system". Passing "system" causes a 400 error.
                        put("role", if (msg.role == ProviderMessage.Role.system) "developer" else msg.role.name)
                        put("content", msg.content)
                    })
                }
            }))
            // Tool declarations, Responses-API shape: name, description and
            // parameters sit at the top of each entry. The Chat Completions
            // shape this used to send — wrapping them in a nested "function"
            // object — is rejected with 400 "Missing required parameter:
            // 'tools[0].name'". Aura's agent loop always declares tools, so
            // that alone failed every message.
            //
            // Schema comes from the shared toJsonSchema() rather than being
            // rebuilt here; the hand-rolled version dropped everything except
            // type and description, and omitting "type":"object" is what
            // caused the original HTTP 400 on DeepSeek.
            if (tools.isNotEmpty()) {
                put("tools", JsonArray(tools.map { tool ->
                    buildJsonObject {
                        put("type", "function")
                        put("name", tool.name)
                        put("description", tool.description)
                        put("parameters", tool.parameters.toJsonSchema())
                    }
                }))
            }
            // Structured output, Responses-API shape. Note `name`, `schema` and
            // `strict` are SIBLINGS of `type` inside `format` — not nested under
            // a `json_schema` key as they are in Chat Completions. Same class of
            // divergence as the tool declarations above, which took a 400 for
            // exactly that reason.
            options.responseSchema?.let { schema ->
                put("text", buildJsonObject {
                    put("format", buildJsonObject {
                        put("type", "json_schema")
                        put("name", schema.name)
                        put("strict", schema.strict)
                        put("schema", schema.schema)
                    })
                })
            } ?: run {
                if (options.responseFormat == ResponseFormat.JSON) {
                    put("text", buildJsonObject {
                        put("format", buildJsonObject { put("type", "json_object") })
                    })
                }
            }
        }
        val request = Request.Builder()
            .url("$baseUrl/responses")
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .header("OpenAI-Beta", "responses=experimental")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val channel = kotlinx.coroutines.channels.Channel<ProviderChunk>(capacity = kotlinx.coroutines.channels.Channel.UNLIMITED)
        val sourceHolder = EventSourceHolder()
        activeEventSource = sourceHolder
        // Whether this response asked for a tool. The agent loop stops on
        // `finishReason == "stop"` (MemoryAugmentedAgenticLoop), so reporting
        // stop after a tool call ends the turn with the call recorded and
        // never executed — the model says "I'll check the docs" and then
        // nothing happens. Worse, that leaves a function_call in the history
        // with no output, which the API rejects outright on the next message.
        var sawToolCall = false
        EventSources.createFactory(sseClient).newEventSource(request, object : EventSourceListener() {
            /**
             * Dispatch on the event type the backend actually sends.
             *
             * This previously looked for Chat-Completions-shaped payloads —
             * `delta.text`, `delta.tool_calls[]` with an `index` — none of
             * which this API emits. Verified against the live stream: text
             * arrives as `response.output_text.delta` whose `delta` is a
             * **bare string**, and a completed tool call arrives whole in
             * `response.output_item.done`.
             */
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                sourceHolder.source = eventSource
                if (data == "[DONE]") { channel.trySend(ProviderChunk(finishReason = finishReason(sawToolCall))); channel.close(); return }
                val obj = try { Json.parseToJsonElement(data).jsonObject } catch (e: Exception) { return }
                when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
                    "response.output_text.delta" ->
                        (obj["delta"] as? JsonPrimitive)?.contentOrNull
                            ?.let { channel.trySend(ProviderChunk(text = it)) }

                    "response.reasoning_summary_text.delta" ->
                        (obj["delta"] as? JsonPrimitive)?.contentOrNull
                            ?.let { channel.trySend(ProviderChunk(thinking = it)) }

                    // Emitted once per tool call, carrying the finished
                    // arguments. The per-token
                    // `response.function_call_arguments.delta` events are
                    // deliberately ignored — accumulating them only risks
                    // dropping or double-counting fragments to arrive at the
                    // string this event already provides complete.
                    "response.output_item.done" -> {
                        val item = obj["item"] as? JsonObject
                        if ((item?.get("type") as? JsonPrimitive)?.contentOrNull == "function_call") {
                            val name = (item["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                            val callId = (item["call_id"] as? JsonPrimitive)?.contentOrNull
                                ?: (item["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                            val arguments = (item["arguments"] as? JsonPrimitive)?.contentOrNull
                                ?.takeIf { it.isNotBlank() } ?: "{}"
                            if (name.isNotBlank()) {
                                sawToolCall = true
                                channel.trySend(
                                    ProviderChunk(toolCall = ToolCall(id = callId, name = name, arguments = arguments)),
                                )
                            }
                        }
                    }

                    "response.completed", "response.done" ->
                        channel.trySend(ProviderChunk(finishReason = finishReason(sawToolCall)))

                    // Terminal states that are not successes. Without these
                    // the stream would just stop and look like a short reply.
                    "response.failed", "response.incomplete" -> {
                        val reason = ((obj["response"] as? JsonObject)?.get("error") as? JsonObject)
                            ?.get("message").let { (it as? JsonPrimitive)?.contentOrNull }
                        channel.trySend(
                            ProviderChunk(error = ProviderError("stream_failed", reason ?: "The response ended early.")),
                        )
                        channel.close()
                    }
                }
            }
            override fun onFailure(eventSource: EventSource, t: Throwable?, response: okhttp3.Response?) {
                val code = response?.code ?: 0
                // 401/400/403/404/422 are not retryable — bad key, bad
                // request, or forbidden won't fix themselves. 429 (rate
                // limit, carrying the server's Retry-After) and 5xx
                // (server error) benefit from a delayed retry / failover.
                val retryable = code == 429 || code in 500..599
                val retryAfterMs = if (code == 429) OpenAiCompatProvider.parseRetryAfterMs(response) else null
                channel.trySend(ProviderChunk(error = ProviderError("http_error", OpenAiCompatProvider.failureMessage(t, response, key), retryable = retryable, retryAfterMs = retryAfterMs)))
                channel.close()
            }
            override fun onClosed(eventSource: EventSource) { channel.close() }
        }).also { sourceHolder.source = it }
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
            // Cancel only THIS stream's source; clear the shared field behind
            // an identity check so a concurrent stream isn't clobbered.
            sourceHolder.cancel()
            if (activeEventSource === sourceHolder) activeEventSource = null
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        // 5 minutes: long enough for a slow model + max_tokens worth of
        // tokens, short enough that a misbehaving server doesn't hang
        // the agent loop. Mirrors OpenAiCompatProvider.STREAM_READ_TIMEOUT_MS.
        const val STREAM_READ_TIMEOUT_MS = 5L * 60L * 1000L

        /**
         * Sent as `client_version` on the catalog request. The backend
         * requires it — without it `/models` returns 400 — and each model
         * advertises a `minimal_client_version`, so the server gates its
         * catalog on this value. It tracks the Codex CLI release whose
         * grant this provider rides on.
         */
        private const val CLIENT_VERSION = "0.146.1"

        /** The header the subscription backend omits on its SSE responses. */
        private const val SSE_MEDIA_TYPE = "text/event-stream"

        /**
         * `tool_calls` when the response asked for a tool, so the agent loop
         * dispatches it instead of treating the turn as finished.
         */
        private fun finishReason(sawToolCall: Boolean): FinishReason =
            if (sawToolCall) FinishReason.tool_calls else FinishReason.stop
    }

    private val catalogJson = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The client used for streaming, with one job: label the response body as
     * `text/event-stream`.
     *
     * The subscription backend returns the SSE stream with **no Content-Type
     * header at all** (verified on the wire). OkHttp's `EventSource` requires
     * that header and, when it is missing, calls `onFailure` — with the 200
     * response still attached. So a perfectly good stream was reported as a
     * failure, and the error path dumped the entire raw body into the chat as
     * "HTTP 200: event: response.created data: {…}".
     *
     * A network interceptor is the narrow fix: it only fills in a header the
     * server omitted, and only for this provider's calls, leaving the shared
     * client and all the existing cancellation machinery untouched.
     */
    private val sseClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val body = response.body
                if (body != null && response.header("Content-Type") == null) {
                    // Rebuilding the body is the part that counts: EventSource
                    // reads `body.contentType()`, which is fixed when the body
                    // is created, so setting the header alone changes nothing.
                    // Wrapping the existing source keeps this streaming rather
                    // than buffering the whole response.
                    response.newBuilder()
                        .header("Content-Type", SSE_MEDIA_TYPE)
                        .body(body.source().asResponseBody(SSE_MEDIA_TYPE.toMediaType(), body.contentLength()))
                        .build()
                } else {
                    response
                }
            }
            .build()
    }

    override suspend fun listModels(): List<String> =
        fetchCatalog().map { it.name }

    /**
     * Live model catalog from the ChatGPT backend, with real context windows.
     *
     * The previous implementation returned a hardcoded list — `gpt-5`,
     * `gpt-4.1`, `gpt-4o`, `o3` and friends — behind a comment asserting
     * that "the chatgpt.com backend doesn't expose a models-listing
     * endpoint". That was wrong on both counts: the endpoint exists, and
     * by the time anyone checked, **not one** of those nine ids was still
     * offered to a subscription. Users saw a picker full of models the
     * backend would refuse.
     *
     * `GET /models` needs a `client_version` query parameter — omitting it
     * returns 400, which is presumably how it came to be read as
     * unavailable. It answers with `{"models":[{slug, display_name,
     * context_window, visibility, …}]}`.
     *
     * `visibility` filters the list: entries marked anything other than
     * `list` are internal (a watermarking variant, the auto-review model)
     * and are not user-selectable.
     */
    override suspend fun listModelsWithContext(): List<ModelInfo> = fetchCatalog()

    private suspend fun fetchCatalog(): List<ModelInfo> = withContext(Dispatchers.IO) {
        val key = apiKey()
        if (key.isBlank()) return@withContext emptyList()
        val request = Request.Builder()
            .url("$baseUrl/models?client_version=$CLIENT_VERSION")
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .header("User-Agent", "codex_cli_rs/$CLIENT_VERSION")
            .build()
        runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw ProviderCatalogException.NetworkException(
                        message = "ChatGPT model catalog request failed with HTTP ${response.code}.",
                        statusCode = response.code,
                    )
                }
                val body = response.body?.string().orEmpty()
                val models = catalogJson.parseToJsonElement(body)
                    .jsonObject["models"] as? JsonArray
                    ?: throw ProviderCatalogException.MalformedResponseException(
                        "Missing models[] in ChatGPT catalog response.",
                    )
                models.mapNotNull { entry ->
                    val model = entry as? JsonObject ?: return@mapNotNull null
                    val slug = (model["slug"] as? JsonPrimitive)?.contentOrNull
                        ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val visibility = (model["visibility"] as? JsonPrimitive)?.contentOrNull
                    if (visibility != null && visibility != "list") return@mapNotNull null
                    ModelInfo(
                        name = slug,
                        contextWindow = (model["context_window"] as? JsonPrimitive)?.intOrNull,
                    )
                }.ifEmpty { throw ProviderCatalogException.EmptyCatalogException() }
            }
        }.getOrElse { error ->
            when (error) {
                is ProviderCatalogException -> throw error
                is CancellationException -> throw error
                else -> throw ProviderCatalogException.NetworkException(cause = error as? Exception)
            }
        }
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
