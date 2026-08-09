package com.aura.providers

import kotlinx.coroutines.flow.Flow

/**
 * Base interface every LLM provider implements.
 * Mirrors aura/providers/base.py BaseProvider.
 */
interface Provider {
    val prefix: String
    val displayName: String
    fun isConfigured(): Boolean

    /**
     * Send a chat request.
     * @return a Flow of ProviderChunks. The terminal chunk has done=true and may include usage.
     */
    fun chat(
        model: String,
        messages: List<ProviderMessage>,
        options: ChatOptions = ChatOptions(),
        tools: List<ToolDefinition> = emptyList(),
    ): Flow<ProviderChunk>

    /**
     * List model names this provider exposes. May be a hardcoded
     * list or fetched from /models. Implementations that know the
     * context window for each model should override
     * [listModelsWithContext] instead — the compactor uses that to
     * decide when to compact.
     */
    suspend fun listModels(): List<String>

    /**
     * List models with metadata (name + context window in tokens).
     * Default implementation returns models with null context window,
     * which means the compactor falls back to its default threshold.
     *
     * Providers should override this when they can return the real
     * context window from their /models endpoint, /api/show
     * endpoint, or a known static mapping.
     */
    suspend fun listModelsWithContext(): List<ModelInfo> {
        return listModels().map { ModelInfo(name = it, contextWindow = null) }
    }

    /**
     * Every model this provider exposes, each labelled with what it can do —
     * **including the ones [listModels] deliberately hides.**
     *
     * That distinction is the whole point. [listModels] filters non-chat models
     * out, because its consumers are the chat picker and the agentic loop's
     * failover, and offering an image model there produced
     * `HTTP 400 … "Model agnes-image-2.1-flash is an image model."`. But the
     * same filtering meant nothing in the app could ever *see* that a
     * configured provider has image or video models, so capability backends had
     * to be hand-written per vendor and a new token lit up nothing but chat.
     *
     * This is the unfiltered view, for capability discovery only. Do not use it
     * to populate anything conversational.
     *
     * Default implementation assumes a chat-only provider, which is correct for
     * every provider that does not serve other modalities.
     */
    suspend fun listModelsWithCapability(): List<ClassifiedModel> =
        listModels().map { ClassifiedModel(name = it, capability = ModelCapability.Chat) }

    /**
     * The provider's OpenAI-shaped `/images/generations` endpoint, or null when
     * it has none.
     *
     * `ImageGenTool` hardcoded `https://api.openai.com/v1/images/generations`
     * and read the OpenAI key, so image generation could only ever use OpenAI
     * or the free Pollinations fallback — no other configured provider was
     * reachable, however capable. Agnes AI, for instance, advertises
     * `agnes-image-2.1-flash` in its catalog and serves it from exactly this
     * endpoint, and there was no way to get to it.
     *
     * Exposed here rather than by making `baseUrl` public because that is the
     * only thing the caller needs, and a provider whose image API is not
     * OpenAI-shaped can decline by leaving this null rather than advertising a
     * URL that would be called wrongly.
     */
    val imagesEndpoint: String? get() = null

    /**
     * The provider's OpenAI-shaped `/videos` endpoint, or null when it has none.
     *
     * Less standardised than the others — OpenAI itself only shipped a video
     * API recently — so a provider whose video API differs in shape should
     * leave this null and be served by a hand-written [com.aura.capabilities]
     * adapter instead, as Kling is.
     */
    val videosEndpoint: String? get() = null

    /** The provider's OpenAI-shaped `/audio/speech` (text-to-speech) endpoint, or null. */
    val speechEndpoint: String? get() = null

    /** The provider's OpenAI-shaped `/audio/transcriptions` (speech-to-text) endpoint, or null. */
    val transcriptionsEndpoint: String? get() = null

    /**
     * Cancel an in-flight request. Implementations should propagate to the underlying HTTP/SDK call.
     */
    suspend fun cancel()
}

/**
 * Model metadata, in tokens. Null means unknown — the caller falls back to a
 * sane default rather than guessing.
 *
 * [contextWindow] is the **input** window; [maxOutputTokens] is the largest
 * response the model will produce. They are different numbers, and treating the
 * first as the second is what sent `max_tokens = 158_400` to Anthropic: 80% of
 * a 200K context window, against models that cap output far below it.
 *
 * Not to be confused with [ModelDescriptor], which is a separate catalog
 * pipeline — built from [Provider.listModelsWithCapability], carrying capability
 * rather than size, cached to disk, and feeding Settings, the model picker and
 * capability routing. This type is built from [Provider.listModelsWithContext]
 * and feeds only the budget resolvers. Unifying the two is worthwhile and
 * deliberately not attempted here.
 */
data class ModelInfo(
    val name: String,
    val contextWindow: Int? = null,
    val maxOutputTokens: Int? = null,
)
