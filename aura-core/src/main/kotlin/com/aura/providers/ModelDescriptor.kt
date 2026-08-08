package com.aura.providers

import kotlinx.serialization.Serializable

/**
 * Describes a single model discovered from a provider's model catalog.
 *
 * The [id] is **namespaced exactly once** with the provider prefix,
 * e.g. `"ollama:deepseek-v4-pro:cloud"`. The [name] is the raw model
 * identifier the provider advertises, without any prefix. Callers that
 * dispatch requests use the [id] as the canonical model reference.
 *
 * @property id Fully-qualified model id: `"$providerPrefix:$name"`.
 * @property name Model name as returned by the provider (e.g. `"gpt-4o"`).
 * @property providerPrefix Provider that owns this model (e.g. `"openai"`).
 */
@Serializable
data class ModelDescriptor(
    val id: String,
    val name: String,
    val providerPrefix: String,
    /**
     * What this model can do.
     *
     * Defaulted, which is what makes it safe against the on-disk catalog cache:
     * `SecureModelCatalogCache` decodes with `ignoreUnknownKeys = true` and
     * treats a decode failure as a cache miss, so entries written before this
     * field existed still load (as [ModelCapability.Chat]) and a downgrade
     * ignores it. No cache-key version bump needed.
     *
     * Consumers that offer models for CONVERSATION must filter on
     * [ModelCapability.isChatUsable] — the catalog now carries image, video and
     * speech models too, and handing one to a chat request produces
     * `HTTP 400 … "Model agnes-image-2.1-flash is an image model."`
     */
    val capability: ModelCapability = ModelCapability.Chat,
)
