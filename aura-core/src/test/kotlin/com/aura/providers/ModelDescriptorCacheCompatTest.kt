package com.aura.providers

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `ModelDescriptor` gained a `capability` field, and it is cached to disk.
 *
 * `SecureModelCatalogCache` persists `List<ModelDescriptor>` as JSON under
 * `model_catalog_cache_v1`, and treats a decode failure as a cache miss — so
 * getting this wrong would not crash, it would silently discard every user's
 * cached catalog and force a full network refresh on next launch, on a screen
 * that shows "Loading" while it happens. Quiet enough to ship unnoticed.
 *
 * The claim being tested is that no cache-key bump was needed. Both directions
 * matter: old JSON must still decode, and new JSON must survive a downgrade.
 */
class ModelDescriptorCacheCompatTest {

    /** Mirrors SecureModelCatalogCache's configuration exactly. */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `JSON written before the capability field still decodes`() {
        val old = """{"id":"openai:gpt-4o","name":"gpt-4o","providerPrefix":"openai"}"""

        val decoded = json.decodeFromString<ModelDescriptor>(old)

        assertEquals("openai:gpt-4o", decoded.id)
        // Defaults to Chat, which is what every cached entry was — listModels
        // filtered non-chat models out before they could ever be cached.
        assertEquals(ModelCapability.Chat, decoded.capability)
    }

    @Test
    fun `a list of pre-capability entries decodes`() {
        val old = """[
            {"id":"openai:gpt-4o","name":"gpt-4o","providerPrefix":"openai"},
            {"id":"agnes:agnes-2.0-flash","name":"agnes-2.0-flash","providerPrefix":"agnes"}
        ]"""

        val decoded = json.decodeFromString<List<ModelDescriptor>>(old)

        assertEquals(2, decoded.size)
        assertEquals(listOf(ModelCapability.Chat, ModelCapability.Chat), decoded.map { it.capability })
    }

    @Test
    fun `a downgrade ignores the new field rather than failing`() {
        // ignoreUnknownKeys is what buys this; without it an older build
        // reading a newer cache would throw and lose the catalog.
        val new = """{"id":"a:b","name":"b","providerPrefix":"a","capability":"Image","futureField":42}"""

        val decoded = json.decodeFromString<ModelDescriptor>(new)

        assertEquals("a:b", decoded.id)
    }

    @Test
    fun `capability round-trips`() {
        val original = ModelDescriptor(
            id = "agnes:agnes-image-2.1-flash",
            name = "agnes-image-2.1-flash",
            providerPrefix = "agnes",
            capability = ModelCapability.Image,
        )

        val restored = json.decodeFromString<ModelDescriptor>(json.encodeToString(original))

        assertEquals(original, restored)
        assertEquals(ModelCapability.Image, restored.capability)
    }
}
