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
     * Cancel an in-flight request. Implementations should propagate to the underlying HTTP/SDK call.
     */
    suspend fun cancel()
}

/**
 * Model metadata. [contextWindow] is in tokens (null = unknown —
 * caller should fall back to a sane default).
 */
data class ModelInfo(
    val name: String,
    val contextWindow: Int? = null,
)
