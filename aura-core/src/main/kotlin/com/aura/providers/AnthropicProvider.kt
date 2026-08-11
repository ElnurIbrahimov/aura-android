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
import kotlinx.serialization.json.JsonElement
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

    /**
     * Serialised as a forced `tool_choice` over a synthetic tool, when no real
     * tools are declared. Note this covers [ChatOptions.responseSchema] only:
     * Anthropic has no bare JSON mode, so [ResponseFormat.JSON] on its own puts
     * nothing on the wire and relies entirely on the prompt plus the caller's
     * lenient parse.
     */
    override val supportsResponseSchema: Boolean get() = true

    /** Live API key, looked up at call time. */
    private suspend fun apiKey(): String = providerKeys.keyForAwaiting(prefix) ?: ""

    @Volatile private var activeCall: okhttp3.Call? = null

    override fun isConfigured(): Boolean = providerKeys.isConfigured(prefix)

    override fun chat(model: String, messages: List<ProviderMessage>, options: ChatOptions, tools: List<ToolDefinition>): Flow<ProviderChunk> = flow {
        val key = apiKey()
        val (systemPrompt, anthropicMessages) = splitSystem(messages)
        val maxTokens = options.maxTokens ?: DEFAULT_MAX_TOKENS
        // Last line of defence for Anthropic's own invariant: max_tokens must
        // exceed budget_tokens, and budget_tokens must be at least 1024.
        //
        // TokenBudgetPolicy already guarantees both for anything routed through
        // Brain, but four Creative Studio call sites reached this provider with
        // budget_tokens >= max_tokens for months and took a non-retryable 400
        // every time, because Brain's budget block was skipped whenever a caller
        // set its own thinking budget. Policy belongs in Brain; the vendor's
        // invariant belongs here, where nothing can route around it.
        //
        // The budget is only ever clamped DOWN. Raising max_tokens to fit would
        // increase spend behind the caller's back, which a provider must never do.
        val rawThinkingBudget = options.thinkingBudget
            ?.coerceAtMost(maxTokens - 1)
            ?.takeIf { it >= MIN_THINKING_BUDGET }

        // Structured output. Anthropic has no `response_format`, so a schema is
        // expressed as a single synthetic tool the model is forced to call, and
        // the resulting `tool_use` input is streamed back to the caller as text
        // (see SYNTHETIC_SCHEMA_TOOL and the input_json_delta handling below).
        //
        // Only when the caller declared no real tools. Forcing a tool_choice
        // alongside a real tool set would destroy tool calling outright, and a
        // caller asking for a schema is asking for an answer, not a tool call.
        // Every current call site passes tools = emptyList(), so the gate costs
        // nothing and prevents a catastrophic interaction.
        val forcedSchema = options.responseSchema?.takeIf { tools.isEmpty() }

        // Extended thinking and forced tool use are mutually exclusive on
        // Anthropic. Dropping thinking is the cheaper loss: the caller asked for
        // a machine-readable answer, and losing the reasoning trace costs less
        // than a non-retryable 400 that loses the answer too.
        val thinkingBudget = if (forcedSchema != null) null else rawThinkingBudget

        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("max_tokens", maxTokens)
            put("temperature", options.temperature ?: ChatOptions.DEFAULT_TEMPERATURE)
            // Extended thinking: when budget is set, add the thinking block.
            thinkingBudget?.let { budget ->
                put("thinking", buildJsonObject {
                    put("type", "enabled")
                    put("budget_tokens", budget)
                })
                // Anthropic requires temperature=1 when thinking is enabled.
                put("temperature", 1.0)
            }
            systemField(systemPrompt, options.stableSystemPrefix)?.let { put("system", it) }
            // Thinking blocks are replayed only when this request has thinking
            // ON. With it off they are surplus at best, so the wire bytes stay
            // byte-identical to what shipped before for every non-thinking call.
            put("messages", buildAnthropicMessages(anthropicMessages, includeThinking = thinkingBudget != null))
            if (tools.isNotEmpty()) {
                put("tools", kotlinx.serialization.json.JsonArray(tools.mapIndexed { i, tool ->
                    buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("input_schema", tool.parameters.toJsonSchema())
                        // A second breakpoint, on the LAST tool. The tools array
                        // sits ahead of `system` in the request, so without one
                        // here the largest fixed part of the prompt — ~74 tool
                        // schemas, the bulk of the prefix — would be re-billed
                        // in full on every step. Anthropic allows four
                        // breakpoints; this uses two.
                        if (options.stableSystemPrefix > 0 && i == tools.lastIndex) {
                            put("cache_control", buildJsonObject { put("type", "ephemeral") })
                        }
                    }
                }))
            } else if (forcedSchema != null) {
                put("tools", kotlinx.serialization.json.JsonArray(listOf(
                    buildJsonObject {
                        put("name", forcedSchema.name)
                        put("description", "Return the result in this exact structure.")
                        put("input_schema", forcedSchema.schema)
                    },
                )))
                put("tool_choice", buildJsonObject {
                    put("type", "tool")
                    put("name", forcedSchema.name)
                })
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
                    // `resp.message` is the HTTP reason phrase, which HTTP/2
                    // abolished — Anthropic speaks HTTP/2, so this was the empty
                    // string on every failure and the user was shown a bare
                    // "http_400". The body says exactly which field was rejected
                    // ("Expected `thinking` … but found `tool_use`"), and the
                    // shared parser reads it with peekBody, leaving the source
                    // untouched, and redacts any echo of the key.
                    val message = OpenAiCompatProvider.failureMessage(null, resp, key)
                    emit(
                        ProviderChunk(
                            error = ProviderError(
                                "http_${resp.code}",
                                message,
                                retryable = resp.code == 429 || resp.code in 500..599,
                                // A 429 carries the server's own backoff. The
                                // loop waits it out and retries the SAME model
                                // rather than burning a failover slot.
                                retryAfterMs = if (resp.code == 429) {
                                    OpenAiCompatProvider.parseRetryAfterMs(resp)
                                } else {
                                    null
                                },
                            ),
                        ),
                    )
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
                // Content-block indices belonging to the synthetic schema tool.
                // Their deltas are re-emitted as TEXT rather than tool-call
                // arguments, so `responseSchema` means the same thing on every
                // provider: the text you collect off this flow is the JSON.
                // This is a deliberate lie about the wire shape, bought to keep
                // callers from having to special-case Anthropic.
                val schemaBlockIndices = mutableSetOf<Int>()
                // Usage arrives split across message_start (input, cache) and
                // message_delta (output). Held here until both halves exist.
                var inputTokens = 0
                var cacheReadTokens = 0
                var cacheWriteTokens = 0
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
                                // The synthetic schema tool is not a tool call the
                                // caller asked for — swallow the start event and
                                // mark the index so its deltas become text.
                                if (forcedSchema != null && name == forcedSchema.name) {
                                    if (index != null) schemaBlockIndices += index
                                    continue
                                }
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
                                // The HMAC Anthropic issues over the reasoning it
                                // just streamed, sent once after the last
                                // thinking_delta. There was no case for it here,
                                // so it was parsed past — and with it went any
                                // possibility of replaying the thinking block,
                                // which this API demands back on the assistant
                                // turn that issued a tool_use. Step 2 of every
                                // tool call answered 400 "Expected `thinking` or
                                // `redacted_thinking`, but found `tool_use`".
                                "signature_delta" -> {
                                    val signature = (delta["signature"] as? JsonPrimitive)?.content
                                    if (!signature.isNullOrEmpty()) {
                                        emit(ProviderChunk(thinkingSignature = signature))
                                    }
                                }
                                "input_json_delta" -> {
                                    val partial = (delta["partial_json"] as? JsonPrimitive)?.content
                                    if (partial != null) {
                                        // Resolve the tool id by `index` so parallel
                                        // tool_use blocks route their deltas
                                        // correctly. Fall back to "" for legacy
                                        // Brain fallback if index is missing.
                                        val index = (obj["index"] as? JsonPrimitive)?.intOrNull
                                        if (index != null && index in schemaBlockIndices) {
                                            // Forced-schema block: the accumulated
                                            // partial_json IS the answer.
                                            emit(ProviderChunk(text = partial))
                                        } else {
                                            val id = index?.let { pendingByIndex[it] } ?: ""
                                            emit(ProviderChunk(toolCall = ToolCall(id, "", partial)))
                                        }
                                    }
                                }
                            }
                        }
                        "content_block_stop" -> {
                            // Drop the index mapping once the block is complete
                            // to keep the map small across long streams.
                            (obj["index"] as? JsonPrimitive)?.intOrNull?.let {
                                pendingByIndex.remove(it)
                                schemaBlockIndices.remove(it)
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
                            // Anthropic splits usage across two events: input
                            // counts arrive on message_start, output on
                            // message_delta. Neither was read before, so this
                            // provider reported no usage at all and
                            // ProviderRegistry billed it on content.length.
                            //
                            // Emitted BEFORE the finish chunk: the loop stops
                            // collecting once it sees a finish reason, so usage
                            // emitted after it would be dropped on every turn.
                            val outputTokens = ((obj["usage"] as? JsonObject)
                                ?.get("output_tokens") as? JsonPrimitive)?.intOrNull
                            if (outputTokens != null || inputTokens > 0) {
                                emit(
                                    ProviderChunk(
                                        usage = Usage(
                                            promptTokens = inputTokens,
                                            completionTokens = outputTokens ?: 0,
                                            totalTokens = inputTokens + (outputTokens ?: 0),
                                            cachedPromptTokens = cacheReadTokens,
                                            cacheWritePromptTokens = cacheWriteTokens,
                                        ),
                                    ),
                                )
                            }

                            val stop = (obj["delta"] as? JsonObject)?.get("stop_reason")
                            val reason = when ((stop as? JsonPrimitive)?.content) {
                                "end_turn" -> FinishReason.stop
                                "max_tokens" -> FinishReason.length
                                "tool_use" -> FinishReason.tool_calls
                                else -> null
                            }
                            if (reason != null) emit(ProviderChunk(finishReason = reason))
                        }
                        "message_start" -> {
                            // Input counts, including the two cache figures that
                            // say whether a prompt-cache breakpoint worked.
                            // `cache_read_input_tokens` is billed at 0.1x and
                            // `cache_creation_input_tokens` at 1.25x, so a
                            // workload that writes a cache it never reads costs
                            // MORE than not caching. Held until message_delta,
                            // which is where the output count arrives.
                            val u = (obj["message"] as? JsonObject)?.get("usage") as? JsonObject
                            if (u != null) {
                                inputTokens = (u["input_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
                                cacheReadTokens =
                                    (u["cache_read_input_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
                                cacheWriteTokens =
                                    (u["cache_creation_input_tokens"] as? JsonPrimitive)?.intOrNull ?: 0
                                // Anthropic reports cache reads SEPARATELY from
                                // input_tokens, unlike OpenAI where cached is a
                                // subset. Fold them in so promptTokens means the
                                // same thing on both, and cachedPromptTokens
                                // stays a subset of it everywhere.
                                inputTokens += cacheReadTokens + cacheWriteTokens
                            }
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

    /**
     * System messages, kept SEPARATE rather than joined.
     *
     * They used to be collapsed into one string here, which is fine for a
     * `"system": "..."` field and impossible for a cache breakpoint — a
     * breakpoint marks the end of a *block*, so the blocks have to survive.
     * With caching off they are re-joined at serialisation time and the wire
     * bytes are exactly what shipped before.
     */
    private fun splitSystem(messages: List<ProviderMessage>): Pair<List<String>, List<ProviderMessage>> {
        val sys = messages.filter { it.role == ProviderMessage.Role.system }
            .map { it.content }
            .filter { it.isNotBlank() }
        val rest = messages.filter { it.role != ProviderMessage.Role.system }
        return sys to rest
    }

    /**
     * The `system` field: a plain string when caching is off, an array of text
     * blocks with a `cache_control` marker when it is on.
     *
     * The breakpoint goes on block `stableSystemPrefix - 1` — the last block of
     * the fixed prefix. Everything from there back, including the tools array
     * that precedes `system` in the request, becomes cacheable.
     *
     * Anthropic silently ignores a breakpoint below its minimum cacheable
     * length (1024 tokens, 2048 on Haiku). Nothing errors; the request simply
     * bills full price and reports `cache_read_input_tokens: 0`. That is why
     * the usage logging landed first — a short prompt reporting no cache hits
     * looks identical to a broken breakpoint, and only the numbers tell them
     * apart.
     */
    private fun systemField(blocks: List<String>, stablePrefix: Int): JsonElement? {
        if (blocks.isEmpty()) return null
        val breakpointAt = stablePrefix - 1
        if (stablePrefix <= 0 || breakpointAt !in blocks.indices) {
            return JsonPrimitive(blocks.joinToString("\n\n"))
        }
        return kotlinx.serialization.json.JsonArray(
            blocks.mapIndexed { i, text ->
                buildJsonObject {
                    put("type", "text")
                    put("text", text)
                    if (i == breakpointAt) {
                        put("cache_control", buildJsonObject { put("type", "ephemeral") })
                    }
                }
            },
        )
    }

    /**
     * Anthropic Messages wire shape. The API accepts only user/assistant
     * roles: assistant tool calls become `tool_use` content blocks, and
     * `role=tool` results become `tool_result` blocks inside a user
     * message. Adjacent same-role messages are merged because the API
     * requires user/assistant alternation.
     *
     * @param includeThinking whether this request enabled extended thinking. A
     *        prior turn's reasoning is replayed only then — the API requires the
     *        block back on any assistant turn that issued a `tool_use` while
     *        thinking is on, and has no use for it when it is off. Passing the
     *        request's own state rather than reading it from the message keeps
     *        the non-thinking wire bytes exactly as they were.
     */
    private fun buildAnthropicMessages(
        messages: List<ProviderMessage>,
        includeThinking: Boolean,
    ): JsonArray {
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
                    // The reasoning block, first and verbatim.
                    //
                    // With extended thinking on, Anthropic requires the assistant
                    // turn that issued a tool_use to come back WITH the thinking
                    // that preceded it, in first position. Sending the tool_use
                    // alone answers 400 "Expected `thinking` or
                    // `redacted_thinking`, but found `tool_use`" — which is every
                    // step 2 of every tool call, on a provider whose thinking is
                    // on by default.
                    //
                    // Both halves are required. The signature is the API's own
                    // HMAC over the text; a block missing it, or carrying one this
                    // account never received, is rejected outright. So an unsigned
                    // trace is dropped rather than guessed at — which is also what
                    // makes it impossible to replay another provider's reasoning
                    // here, since nothing but this provider ever fills the field.
                    //
                    // The third condition is about `appendBlocks` below, which
                    // MERGES an assistant message into the preceding one when
                    // both are assistant. A thinking block that lands anywhere
                    // but first in the merged content is rejected exactly like a
                    // missing one, so a message that will be merged does not
                    // contribute one. No current path produces two adjacent
                    // assistant messages both carrying signed reasoning — a
                    // tool_result always separates them — so this drops nothing
                    // today, and cannot start emitting a malformed block if some
                    // future path does.
                    val priorThinking = msg.thinking
                    val prioSignature = msg.thinkingSignature
                    val startsNewAssistantMessage = out.lastOrNull()?.first != "assistant"
                    if (includeThinking &&
                        startsNewAssistantMessage &&
                        !priorThinking.isNullOrBlank() &&
                        !prioSignature.isNullOrBlank()
                    ) {
                        blocks += buildJsonObject {
                            put("type", "thinking")
                            put("thinking", priorThinking)
                            put("signature", prioSignature)
                        }
                    }
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

        /**
         * Sent when no caller expressed an opinion. Anthropic requires
         * `max_tokens`, so unlike the OpenAI-compatible providers this field
         * cannot simply be omitted.
         */
        private const val DEFAULT_MAX_TOKENS = 4096

        /** Anthropic's documented floor for `budget_tokens`. Below it, omit the block. */
        private const val MIN_THINKING_BUDGET = 1024
    }
}
