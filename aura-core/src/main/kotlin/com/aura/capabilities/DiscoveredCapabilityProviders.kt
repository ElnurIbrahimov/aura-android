package com.aura.capabilities

import com.aura.capabilities.openaicompat.OpenAiCompatImageProvider
import com.aura.capabilities.openaicompat.OpenAiCompatSpeechProvider
import com.aura.capabilities.openaicompat.OpenAiCompatVideoProvider
import com.aura.providers.ModelCapability
import com.aura.providers.ModelCatalogRepository
import com.aura.providers.ModelDescriptor
import com.aura.providers.ProviderKeys
import com.aura.providers.ProviderRegistry
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Capability backends derived from what the user's configured providers
 * actually offer, rather than from a list compiled into the app.
 *
 * The static Hilt multibinding in [com.aura.capabilities.di.CapabilityModule]
 * can only ever know about vendors somebody hand-wrote an adapter for. Connect a
 * token to a service that serves images from the same base URL as its chat —
 * Agnes AI serves `agnes-image-2.1-flash` and `agnes-video-v2.0` that way — and
 * nothing happens, because there is no binding and the map is generated at
 * compile time.
 *
 * This reads the model catalog instead. Every non-chat model classified by
 * [ModelCapability], whose provider advertises the matching OpenAI-shaped
 * endpoint and has a key, becomes a usable backend. No code change, no rebuild.
 *
 * Deliberately a *pull*, not a subscription: [current] reads
 * `ModelCatalogRepository.catalog.value` on each call. The catalog is already a
 * StateFlow maintained elsewhere, and the alternative — holding a coroutine
 * scope here to mirror it into a second StateFlow — would add a cache that can
 * disagree with its source for no benefit, since resolution happens once per
 * tool call and not in a hot loop.
 */
@Singleton
class DiscoveredCapabilityProviders @Inject constructor(
    private val catalogRepository: ModelCatalogRepository,
    private val providerRegistry: ProviderRegistry,
    private val providerKeys: ProviderKeys,
    private val httpClient: OkHttpClient,
) {
    /**
     * Backends discoverable right now, in catalog order.
     *
     * A model is skipped when its capability has no [CapabilityKind]
     * (chat, embeddings, rerank, moderation), when its provider does not
     * advertise the endpoint that capability needs, or when there is no key.
     */
    fun current(): List<CapabilityProvider> =
        catalogRepository.catalog.value.allModels.mapNotNull(::toBackend)

    /** Discoverable backends for one [kind]. */
    fun forKind(kind: CapabilityKind): List<CapabilityProvider> =
        current().filter { it.kind == kind }

    private fun toBackend(model: ModelDescriptor): CapabilityProvider? {
        val kind = model.capability.toCapabilityKind() ?: return null
        val provider = providerRegistry.get(model.providerPrefix) ?: return null
        // Read the key lazily, per call: the user can add or clear one in
        // Settings at any time and this object outlives that.
        val key = { providerKeys.keyFor(model.providerPrefix) }
        if (key().isNullOrBlank()) return null

        val endpoint = when (kind) {
            CapabilityKind.ImageGeneration -> provider.imagesEndpoint
            CapabilityKind.VideoGeneration -> provider.videosEndpoint
            CapabilityKind.TextToSpeech -> provider.speechEndpoint
            // Transcription needs a multipart upload of the audio, which none
            // of these adapters do; it is served by TranscriptionTool directly.
            // WebSearch and World3D are services, not models — nothing in a
            // model catalog can imply them.
            CapabilityKind.Transcription,
            CapabilityKind.WebSearch,
            CapabilityKind.World3DGeneration,
            -> null
        } ?: return null

        return when (kind) {
            CapabilityKind.ImageGeneration -> OpenAiCompatImageProvider(
                providerPrefix = model.providerPrefix,
                providerDisplayName = provider.displayName,
                modelName = model.name,
                endpoint = endpoint,
                apiKey = key,
                client = httpClient,
            )
            CapabilityKind.VideoGeneration -> OpenAiCompatVideoProvider(
                providerPrefix = model.providerPrefix,
                providerDisplayName = provider.displayName,
                modelName = model.name,
                endpoint = endpoint,
                apiKey = key,
                client = httpClient,
            )
            CapabilityKind.TextToSpeech -> OpenAiCompatSpeechProvider(
                providerPrefix = model.providerPrefix,
                providerDisplayName = provider.displayName,
                modelName = model.name,
                endpoint = endpoint,
                apiKey = key,
                client = httpClient,
            )
            else -> null
        }
    }
}
