package com.aura.capabilities

import com.aura.providers.ModelCapability
import com.aura.providers.ModelCatalog
import com.aura.providers.ModelCatalogRepository
import com.aura.providers.ModelDescriptor
import com.aura.providers.Provider
import com.aura.providers.ProviderKeys
import com.aura.providers.ProviderRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Capability backends derived from the catalog rather than compiled in.
 *
 * The case that motivated this: Agnes AI's catalog holds two chat models, two
 * image models and one video model, all behind one OpenAI-shaped base URL. The
 * static Hilt multibinding could never know about them, so connecting the token
 * lit up chat and nothing else.
 */
class DiscoveredCapabilityProvidersTest {

    private fun provider(prefix: String, name: String, images: String? = null, videos: String? = null, speech: String? = null): Provider =
        mockk<Provider>(relaxed = true).also {
            every { it.prefix } returns prefix
            every { it.displayName } returns name
            every { it.imagesEndpoint } returns images
            every { it.videosEndpoint } returns videos
            every { it.speechEndpoint } returns speech
        }

    private fun subject(
        models: List<ModelDescriptor>,
        providers: Map<String, Provider>,
        keys: Set<String> = providers.keys,
    ): DiscoveredCapabilityProviders {
        val repo = mockk<ModelCatalogRepository>(relaxed = true)
        every { repo.catalog } returns MutableStateFlow(ModelCatalog(emptyMap(), models))

        val registry = mockk<ProviderRegistry>(relaxed = true)
        providers.forEach { (p, impl) -> every { registry.get(p) } returns impl }
        every { registry.get(match { it !in providers.keys }) } returns null

        val providerKeys = mockk<ProviderKeys>(relaxed = true)
        every { providerKeys.keyFor(any()) } answers { if (firstArg<String>() in keys) "k" else null }

        return DiscoveredCapabilityProviders(repo, registry, providerKeys, OkHttpClient())
    }

    private fun model(prefix: String, name: String, capability: ModelCapability) =
        ModelDescriptor(id = "$prefix:$name", name = name, providerPrefix = prefix, capability = capability)

    // ── the Agnes case ─────────────────────────────────────────────

    @Test
    fun `an image model in the catalog becomes an image backend`() {
        val agnes = provider("agnes", "Agnes AI", images = "https://apihub.agnes-ai.com/v1/images/generations")

        val discovered = subject(
            models = listOf(model("agnes", "agnes-image-2.1-flash", ModelCapability.Image)),
            providers = mapOf("agnes" to agnes),
        ).forKind(CapabilityKind.ImageGeneration)

        assertEquals(1, discovered.size)
        assertEquals(CapabilityKind.ImageGeneration, discovered.single().kind)
        assertTrue(discovered.single().isConfigured())
        // Identity names both halves — which provider, which model.
        assertEquals("agnes/agnes-image-2.1-flash", discovered.single().prefix)
        assertTrue(discovered.single().displayName.contains("Agnes AI"))
    }

    @Test
    fun `a video model becomes a video backend`() {
        val agnes = provider("agnes", "Agnes AI", videos = "https://apihub.agnes-ai.com/v1/videos")

        val discovered = subject(
            models = listOf(model("agnes", "agnes-video-v2.0", ModelCapability.Video)),
            providers = mapOf("agnes" to agnes),
        ).forKind(CapabilityKind.VideoGeneration)

        assertEquals(1, discovered.size)
    }

    @Test
    fun `the whole Agnes catalog yields exactly the non-chat models`() {
        val agnes = provider(
            "agnes", "Agnes AI",
            images = "https://x/images/generations",
            videos = "https://x/videos",
        )

        val all = subject(
            models = listOf(
                model("agnes", "agnes-2.0-flash", ModelCapability.Unknown),
                model("agnes", "agnes-2.5-flash", ModelCapability.Unknown),
                model("agnes", "agnes-image-2.0-flash", ModelCapability.Image),
                model("agnes", "agnes-image-2.1-flash", ModelCapability.Image),
                model("agnes", "agnes-video-v2.0", ModelCapability.Video),
            ),
            providers = mapOf("agnes" to agnes),
        ).current()

        assertEquals(3, all.size)
        assertEquals(2, all.count { it.kind == CapabilityKind.ImageGeneration })
        assertEquals(1, all.count { it.kind == CapabilityKind.VideoGeneration })
    }

    // ── what must NOT be discovered ────────────────────────────────

    @Test
    fun `chat models never become capability backends`() {
        val p = provider("agnes", "Agnes AI", images = "https://x/images/generations")

        val all = subject(
            models = listOf(
                model("agnes", "agnes-2.0-flash", ModelCapability.Chat),
                model("agnes", "agnes-2.5-flash", ModelCapability.Unknown),
            ),
            providers = mapOf("agnes" to p),
        ).current()

        assertTrue("chat models are Provider's job, not the capability registry's", all.isEmpty())
    }

    @Test
    fun `embeddings, rerank and moderation are not capability backends`() {
        // They have no CapabilityKind. Registering them would advertise
        // backends that nothing can invoke.
        val p = provider("openai", "OpenAI", images = "https://x/images/generations")

        val all = subject(
            models = listOf(
                model("openai", "text-embedding-3-large", ModelCapability.Embedding),
                model("openai", "omni-moderation-latest", ModelCapability.Moderation),
                model("openai", "rerank-1", ModelCapability.Rerank),
            ),
            providers = mapOf("openai" to p),
        ).current()

        assertTrue(all.isEmpty())
    }

    @Test
    fun `a model whose provider advertises no endpoint is skipped`() {
        // Being classified as an image model is not enough — there has to be
        // somewhere to POST it.
        val p = provider("weird", "Weird", images = null)

        val all = subject(
            models = listOf(model("weird", "weird-image-1", ModelCapability.Image)),
            providers = mapOf("weird" to p),
        ).current()

        assertTrue(all.isEmpty())
    }

    @Test
    fun `a model whose provider has no key is skipped`() {
        val p = provider("agnes", "Agnes AI", images = "https://x/images/generations")

        val all = subject(
            models = listOf(model("agnes", "agnes-image-2.1-flash", ModelCapability.Image)),
            providers = mapOf("agnes" to p),
            keys = emptySet(),
        ).current()

        assertTrue(all.isEmpty())
    }

    @Test
    fun `transcription is not discovered because it needs a multipart upload`() {
        val p = provider("openai", "OpenAI", images = "https://x/images/generations")

        val all = subject(
            models = listOf(model("openai", "whisper-1", ModelCapability.Transcription)),
            providers = mapOf("openai" to p),
        ).current()

        assertTrue(all.isEmpty())
    }
}
