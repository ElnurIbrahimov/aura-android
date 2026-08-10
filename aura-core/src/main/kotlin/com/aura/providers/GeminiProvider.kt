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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Google Gemini provider (Text-only).
 *
 * Uses the [streamGenerateContent] endpoint which returns newline-delimited
 * JSON objects (not SSE). The API key is passed via the `X-Goog-Api-Key`
 * header.
 *
 * API: POST https://generativelanguage.googleapis.com/v1beta/models/{model}:streamGenerateContent
 *
 * Text-only (no image support in this pass). Image support will be added in
 * Task 4.1 via a [ProviderMessage.imageData] field.
 *
 * The API key is read from [ProviderKeys] on every [chat] call, not at
 * construction time, so changes the user makes in the Settings UI take
 * effect immediately without restarting the app.
 */
class GeminiProvider(
    private val providerKeys: ProviderKeys,
    private val httpClient: OkHttpClient,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
) : Provider {

    override val prefix = "gemini"
    override val displayName = "Google Gemini"

    /** Serialised as `generationConfig.responseSchema`, when no tools are declared. */
    override val supportsResponseSchema: Boolean get() = true

    /** Live API key, looked up at call time. */
    private suspend fun apiKey(): String = providerKeys.keyForAwaiting(prefix) ?: ""

    @Volatile private var activeCall: okhttp3.Call? = null

    override fun isConfigured(): Boolean = providerKeys.isConfigured(prefix)

    override fun chat(
        model: String,
        messages: List<ProviderMessage>,
        options: ChatOptions,
        tools: List<ToolDefinition>,
    ): Flow<ProviderChunk> = flow {
        val key = apiKey()
        val body = buildRequestBody(messages, options, tools)
        if (key.isBlank()) {
            emit(ProviderChunk(error = ProviderError("missing_api_key", "Gemini API key not configured", retryable = false)))
            return@flow
        }
        val request = Request.Builder()
            .url("$baseUrl/models/$model:streamGenerateContent")
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Goog-Api-Key", key)
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
                    // Try to read error detail from body
                    val errorDetail = try {
                        val errBody = resp.body?.string() ?: ""
                        val errObj = Json.parseToJsonElement(errBody).jsonObject
                        val error = errObj["error"]?.jsonObject
                        val msg = error?.get("message")?.jsonPrimitive?.content
                        msg ?: resp.message
                    } catch (_: Exception) {
                        resp.message
                    }
                    emit(ProviderChunk(
                        error = ProviderError("http_${resp.code}", errorDetail, retryable = resp.code == 429 || resp.code in 500..599)
                    ))
                    return@use
                }
                val source = resp.body?.source() ?: return@use
                var sawFinish = false
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    val obj = try { Json.parseToJsonElement(line).jsonObject } catch (_: Exception) { continue }

                    // Parse candidates[0].content.parts[0].text and functionCall
                    val candidates = obj["candidates"]?.jsonArray
                    val candidate = candidates?.firstOrNull()?.jsonObject
                    val content = candidate?.get("content")?.jsonObject
                    val parts = content?.get("parts")?.jsonArray
                    if (parts != null) {
                        var partIndex = 0
                        for (part in parts) {
                            val partObj = part as? JsonObject ?: continue
                            // Text part
                            val text = partObj["text"]?.jsonPrimitive?.content
                            if (text != null) {
                                // Gemini thinking parts have thought=true
                                val isThought = partObj["thought"]?.jsonPrimitive?.content?.toBoolean() == true
                                if (isThought) emit(ProviderChunk(thinking = text))
                                else emit(ProviderChunk(text = text))
                            }
                            // Function call part (Gemini tool calling)
                            val fnCall = partObj["functionCall"]?.jsonObject
                            if (fnCall != null) {
                                val fnName = fnCall["name"]?.jsonPrimitive?.content ?: ""
                                val fnArgs = fnCall["args"]?.toString() ?: "{}"
                                // P0-AGENTIC-F2: stable per-part ids so parallel
                                // calls to the same function don't collide. Index
                                // within the response is deterministic.
                                val callId = "gemini_${partIndex}_${fnName}"
                                emit(ProviderChunk(toolCall = ToolCall(id = callId, name = fnName, arguments = fnArgs)))
                            }
                            partIndex++
                        }
                    }

                    // Check finish reason on the candidate
                    val finishReason = candidate?.get("finishReason")?.jsonPrimitive?.content
                    if (finishReason != null) {
                        sawFinish = true
                        val reason = when (finishReason) {
                            "STOP" -> FinishReason.stop
                            "MAX_TOKENS" -> FinishReason.length
                            "SAFETY", "RECITATION", "PROHIBITED" -> FinishReason.stop
                            else -> FinishReason.stop
                        }
                        // Also check usage metadata if present on the final chunk
                        val usage = parseUsage(obj)
                        emit(ProviderChunk(finishReason = reason, usage = usage))
                    }
                }
                // If the stream ended without a finishReason, emit a terminal stop
                if (!sawFinish) {
                    emit(ProviderChunk(finishReason = FinishReason.stop))
                }
            }
            } // withTimeout
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                emit(ProviderChunk(error = ProviderError("stream_error", e.message ?: "unknown", retryable = true)))
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
                .url("$baseUrl/models")
                .addHeader("Content-Type", "application/json")
                .addHeader("X-Goog-Api-Key", key)
                .get()
                .build()
                httpClient.newCall(request).execute().use { response ->
                    when (response.code) {
                        401 -> throw ProviderCatalogException.AuthenticationException()
                        429 -> throw ProviderCatalogException.RateLimitedException(
                            retryAfterMs = response.header("Retry-After")?.toLongOrNull()?.times(1_000L),
                        )
                        in 200..299 -> Unit
                        else -> throw ProviderCatalogException.NetworkException(
                            message = "Gemini catalog request failed with HTTP ${response.code}.",
                            statusCode = response.code,
                        )
                    }
                    val body = response.body?.string()?.takeIf(String::isNotBlank)
                        ?: throw ProviderCatalogException.MalformedResponseException(
                            "Gemini returned an empty model catalog response.",
                        )
                    val models = try {
                        Json.parseToJsonElement(body).jsonObject["models"] as? JsonArray
                            ?: throw ProviderCatalogException.MalformedResponseException(
                                "Missing models[] in Gemini response.",
                            )
                    } catch (e: ProviderCatalogException) {
                        throw e
                    } catch (e: Exception) {
                        throw ProviderCatalogException.MalformedResponseException(
                            "Gemini returned malformed model catalog JSON.",
                            e,
                        )
                    }
                    models.filter { it.supportsChat() }
                        .mapNotNull { (it as? JsonObject)?.get("name")?.let { n -> (n as? JsonPrimitive)?.content } }
                        .mapNotNull { fullName ->
                            fullName.removePrefix("models/").takeIf { it.isNotBlank() }
                        }.ifEmpty { throw ProviderCatalogException.EmptyCatalogException() }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: ProviderCatalogException) {
            throw e
        } catch (e: java.io.IOException) {
            currentCoroutineContext().ensureActive()
            throw ProviderCatalogException.NetworkException(cause = e)
        } catch (e: Exception) {
            throw ProviderCatalogException.MalformedResponseException(
                "Gemini model catalog could not be read.",
                e,
            )
        }
    }

    /**
     * Whether this catalog entry can hold a conversation.
     *
     * Gemini's catalog is not a list of chat models — of 58 entries, 16 are
     * embedders, Imagen, Veo, or live audio/streaming models that reject
     * `generateContent` outright. Offering them in a chat picker put
     * "Imagen 4.0 Ultra Generate" in the model list, where selecting it
     * produced a conversation that could never answer.
     *
     * Each entry declares what it supports, so ask rather than pattern-match
     * on the name: new families keep arriving and a name-based filter would
     * silently drop real chat models or admit new image ones.
     */
    private fun JsonElement.supportsChat(): Boolean {
        val methods = (this as? JsonObject)?.get("supportedGenerationMethods") as? JsonArray
        // An entry that declares nothing is kept: better a model that errors
        // on use than a silently missing one, and older responses omitted it.
            ?: return true
        return methods.any { (it as? JsonPrimitive)?.content == "generateContent" }
    }

    override suspend fun cancel() {
        activeCall?.cancel()
        activeCall = null
    }

    /**
     * Gemini's /v1beta/models returns a `inputTokenLimit`
     * field per model — the real context window. We
     * re-fetch the catalog to get both name and limit,
     * stripping the "models/" prefix to match the
     * model name format used elsewhere in the app.
     */
    override suspend fun listModelsWithContext(): List<ModelInfo> = withContext(Dispatchers.IO) {
        try {
            val key = apiKey()
            val requestBuilder = Request.Builder()
                .url("$baseUrl/models?pageSize=100")
            if (key.isNotBlank()) {
                requestBuilder.addHeader("X-Goog-Api-Key", key)
            }
            val request = requestBuilder.build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList<ModelInfo>()
                val body = response.body?.string() ?: return@use emptyList<ModelInfo>()
                val models = Json.parseToJsonElement(body).jsonObject["models"] as? JsonArray
                    ?: return@use emptyList<ModelInfo>()
                models.mapNotNull { item ->
                    val obj = item as? JsonObject ?: return@mapNotNull null
                    if (!obj.supportsChat()) return@mapNotNull null
                    val fullName = (obj["name"] as? JsonPrimitive)?.content
                        ?: return@mapNotNull null
                    val name = fullName.removePrefix("models/").takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    val inputTokenLimit = (obj["inputTokenLimit"] as? JsonPrimitive)
                        ?.content?.toIntOrNull()
                    // `outputTokenLimit` is a sibling of `inputTokenLimit` in the
                    // same object, and was parsed past for as long as this method
                    // has existed. It is the only per-model output cap any
                    // provider here reports; without it `max_tokens` was derived
                    // from the input window, which is a different number.
                    val outputTokenLimit = (obj["outputTokenLimit"] as? JsonPrimitive)
                        ?.content?.toIntOrNull()
                    ModelInfo(
                        name = name,
                        contextWindow = inputTokenLimit,
                        maxOutputTokens = outputTokenLimit,
                    )
                }
            }
        } catch (e: Exception) {
            // If /v1beta/models fails, fall through to plain
            // listModels() with null context windows — the
            // compactor uses the 32K default.
            listModels().map { ModelInfo(name = it, contextWindow = null) }
        }
    }

    // ---- internal helpers ----

    private fun buildRequestBody(
        messages: List<ProviderMessage>,
        options: ChatOptions,
        tools: List<ToolDefinition> = emptyList(),
    ): JsonObject = buildJsonObject {
        // Gemini supports system_instruction at the top level
        val systemMessages = messages.filter { it.role == ProviderMessage.Role.system }
        if (systemMessages.isNotEmpty()) {
            val systemText = systemMessages.joinToString("\n\n") { it.content }
            put("system_instruction", buildJsonObject {
                put("parts", JsonArray(listOf(buildJsonObject {
                    put("text", systemText)
                })))
            })
        }

        // Filter out system messages (already handled above) and map roles.
        // Assistant tool calls become functionCall parts; role=tool results
        // become functionResponse parts in a user message — Gemini matches
        // responses by function NAME (its ids are synthetic, never sent back).
        val chatMessages = messages.filter { it.role != ProviderMessage.Role.system }
        put("contents", JsonArray(chatMessages.map { msg ->
            buildJsonObject {
                when (msg.role) {
                    ProviderMessage.Role.assistant -> {
                        put("role", "model")
                        val parts = mutableListOf<JsonObject>()
                        if (msg.content.isNotBlank() || msg.toolCalls.isNullOrEmpty()) {
                            parts += buildJsonObject { put("text", msg.content) }
                        }
                        for (call in msg.toolCalls.orEmpty()) {
                            parts += buildJsonObject {
                                put("functionCall", buildJsonObject {
                                    put("name", call.name)
                                    put("args", parseArgsObject(call.arguments))
                                })
                            }
                        }
                        put("parts", JsonArray(parts))
                    }
                    ProviderMessage.Role.tool -> {
                        put("role", "user")
                        put("parts", JsonArray(listOf(buildJsonObject {
                            put("functionResponse", buildJsonObject {
                                put("name", msg.name ?: "")
                                put("response", buildJsonObject {
                                    put("result", msg.content)
                                })
                            })
                        })))
                    }
                    else -> {
                        put("role", "user")
                        put("parts", JsonArray(listOf(buildJsonObject {
                            put("text", msg.content)
                        })))
                    }
                }
            }
        }))

        put("generationConfig", buildJsonObject {
            put("temperature", options.temperature ?: ChatOptions.DEFAULT_TEMPERATURE)
            put("topP", options.topP ?: ChatOptions.DEFAULT_TOP_P)
            options.maxTokens?.let { put("maxOutputTokens", it) }
            if (options.stop.isNotEmpty()) {
                put("stopSequences", JsonArray(options.stop.map { JsonPrimitive(it) }))
            }
            // Extended thinking: Gemini supports thinkingBudget in generationConfig.
            options.thinkingBudget?.let { budget ->
                put("thinkingConfig", buildJsonObject {
                    put("thinkingBudget", budget)
                })
            }
            // Structured output. Gated on `tools.isEmpty()`: Gemini rejects
            // responseSchema alongside functionDeclarations, and a caller
            // asking for a schema is asking for an answer, not a tool call.
            // The schema goes through the same sanitizer as tool schemas —
            // Gemini's OpenAPI subset rejects $ref/$defs/additionalProperties
            // and friends, and responseSchema takes a caller-supplied
            // JsonObject that may well contain them.
            if (tools.isEmpty()) {
                val schema = options.responseSchema
                when {
                    schema != null -> {
                        put("responseMimeType", "application/json")
                        put("responseSchema", sanitizeForGemini(schema.schema))
                    }
                    options.responseFormat == ResponseFormat.JSON ->
                        put("responseMimeType", "application/json")
                }
            }
        })

        // Tool declarations (Gemini function calling format)
        if (tools.isNotEmpty()) {
            put("tools", buildJsonObject {
                put("functionDeclarations", JsonArray(tools.map { tool ->
                    buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        // Share the renderer every other provider uses, then adapt it to
                        // Gemini's OpenAPI subset. The hand-rolled version this replaces
                        // emitted only type/description/required, so `enum` never reached
                        // the wire — see sanitizeForGemini's KDoc. The `props.isNotEmpty()`
                        // guard stays: toJsonSchema always emits `properties`, even empty,
                        // and Gemini rejects an empty properties object on no-arg tools.
                        if (tool.parameters.properties.isNotEmpty()) {
                            put("parameters", sanitizeForGemini(tool.parameters.toJsonSchema()))
                        }
                    }
                }))
            })
        }
    }

    /** Tool-call arguments as a JSON object; empty object when unparseable. */
    private fun parseArgsObject(arguments: String): JsonObject =
        runCatching { Json.parseToJsonElement(arguments) as? JsonObject }.getOrNull()
            ?: buildJsonObject {}

    /**
     * Parses usage metadata from the final Gemini response chunk.
     *
     * Gemini returns usageMetadata at the top level:
     * { "usageMetadata": { "promptTokenCount": N, "candidatesTokenCount": M, "totalTokenCount": T } }
     */
    private fun parseUsage(obj: JsonObject): Usage? {
        return try {
            val meta = obj["usageMetadata"]?.jsonObject ?: return null
            Usage(
                promptTokens = meta["promptTokenCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                completionTokens = meta["candidatesTokenCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                totalTokens = meta["totalTokenCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                // Implicit context caching. A subset of promptTokenCount, same
                // convention as OpenAI. Present only once a request repeats a
                // long enough prefix for Gemini to have cached it.
                cachedPromptTokens =
                    meta["cachedContentTokenCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            )
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val STREAM_READ_TIMEOUT_MS = 5L * 60L * 1000L
    }
}
