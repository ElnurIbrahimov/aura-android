package com.aura.capabilities

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Declared backends beat discovered ones.
 *
 * A hand-written vendor adapter exists precisely because the generic
 * OpenAI-shaped path was not good enough for that vendor — Kling mints a JWT and
 * polls a task id, WorldLabs polls a bespoke operation id, ElevenLabs puts the
 * voice in the URL path behind an `xi-api-key` header, Stability posts multipart
 * with the model in the path. A discovered backend silently displacing one of
 * those would replace working video generation with a request the vendor
 * rejects, and the user's only symptom would be that it stopped working.
 *
 * So this is the guarantee that keeps existing setups intact while new tokens
 * gain capabilities they never had.
 */
class CapabilityRegistryPrecedenceTest {

    private fun declared(prefix: String, kind: CapabilityKind, configured: Boolean = true): CapabilityProvider =
        mockk<CapabilityProvider>().also {
            every { it.prefix } returns prefix
            every { it.displayName } returns prefix
            every { it.kind } returns kind
            every { it.isConfigured() } returns configured
        }

    private fun discovery(vararg backends: CapabilityProvider): DiscoveredCapabilityProviders =
        mockk<DiscoveredCapabilityProviders>().also {
            every { it.current() } returns backends.toList()
        }

    @Test
    fun `a declared backend wins over a discovered one for the same kind`() {
        val kling = declared("kling", CapabilityKind.VideoGeneration)
        val agnesVideo = declared("agnes/agnes-video-v2.0", CapabilityKind.VideoGeneration)

        val registry = CapabilityRegistry(
            providers = mapOf("kling" to kling),
            providerKeys = mockk(relaxed = true),
            discovered = discovery(agnesVideo),
        )

        assertEquals("kling", registry.forKind(CapabilityKind.VideoGeneration)?.prefix)
    }

    @Test
    fun `a discovered backend is used when nothing is declared for that kind`() {
        // The whole point: connect a token, get the capability, no code change.
        val agnesImage = declared("agnes/agnes-image-2.1-flash", CapabilityKind.ImageGeneration)

        val registry = CapabilityRegistry(
            providers = emptyMap(),
            providerKeys = mockk(relaxed = true),
            discovered = discovery(agnesImage),
        )

        assertEquals("agnes/agnes-image-2.1-flash", registry.forKind(CapabilityKind.ImageGeneration)?.prefix)
    }

    @Test
    fun `an unconfigured declared backend does not block a discovered one`() {
        // Kling bound but keyless must not shadow a working Agnes video model —
        // that would be the static map winning by existing rather than by working.
        val klingNoKey = declared("kling", CapabilityKind.VideoGeneration, configured = false)
        val agnesVideo = declared("agnes/agnes-video-v2.0", CapabilityKind.VideoGeneration)

        val registry = CapabilityRegistry(
            providers = mapOf("kling" to klingNoKey),
            providerKeys = mockk(relaxed = true),
            discovered = discovery(agnesVideo),
        )

        assertEquals("agnes/agnes-video-v2.0", registry.forKind(CapabilityKind.VideoGeneration)?.prefix)
    }

    @Test
    fun `configuredForKind lists declared before discovered`() {
        val stability = declared("stability", CapabilityKind.ImageGeneration)
        val agnesImage = declared("agnes/agnes-image-2.1-flash", CapabilityKind.ImageGeneration)

        val registry = CapabilityRegistry(
            providers = mapOf("stability" to stability),
            providerKeys = mockk(relaxed = true),
            discovered = discovery(agnesImage),
        )

        assertEquals(
            listOf("stability", "agnes/agnes-image-2.1-flash"),
            registry.configuredForKind(CapabilityKind.ImageGeneration).map { it.prefix },
        )
    }

    @Test
    fun `byPrefix finds discovered backends too`() {
        val agnesImage = declared("agnes/agnes-image-2.1-flash", CapabilityKind.ImageGeneration)

        val registry = CapabilityRegistry(
            providers = emptyMap(),
            providerKeys = mockk(relaxed = true),
            discovered = discovery(agnesImage),
        )

        assertEquals(agnesImage, registry.byPrefix("agnes/agnes-image-2.1-flash"))
        assertNull(registry.byPrefix("nope"))
    }

    @Test
    fun `a null discovery source degrades to declared-only`() {
        // The default, and what every existing construction site relies on.
        val exa = declared("exa", CapabilityKind.WebSearch)

        val registry = CapabilityRegistry(
            providers = mapOf("exa" to exa),
            providerKeys = mockk(relaxed = true),
        )

        assertEquals("exa", registry.forKind(CapabilityKind.WebSearch)?.prefix)
        assertTrue(registry.all().size == 1)
    }
}
