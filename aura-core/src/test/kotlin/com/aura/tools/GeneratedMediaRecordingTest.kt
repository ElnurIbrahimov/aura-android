package com.aura.tools

import com.aura.agent.ToolResult
import com.aura.media.GeneratedMediaEntity
import com.aura.media.GeneratedMediaStore
import com.aura.providers.ProviderKeys
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Whether an image Aura generated still exists tomorrow, and whether anything knows it
 * was ever made.
 *
 * `persistImageBytes` wrote to `cacheDir/generated_images/` and recorded nothing. Android
 * reclaims `cacheDir` under storage pressure and Settings → Clear cache empties it outright,
 * so every generated image was on borrowed time — and because no row existed, nothing could
 * list them, nothing could notice they had gone, and there was no Library to put them in.
 *
 * The KDoc on that method shows the trade was considered: a cache file can be evicted "but
 * so can a provider-hosted URL expire". True, and beside the point — a URL expiring is
 * someone else's decision, an eviction is ours, and `BackupManager`'s restore marker already
 * lives in `filesDir` for exactly this reason.
 *
 * These tests cover the record. The directory change itself is one line and needs a device
 * to observe; the migration test covers the table.
 */
class GeneratedMediaRecordingTest {

    private fun store(captured: MutableList<GeneratedMediaEntity>): GeneratedMediaStore {
        val dao = mockk<com.aura.media.GeneratedMediaDao>(relaxed = true)
        val row = slot<GeneratedMediaEntity>()
        coEvery { dao.upsert(capture(row)) } answers { captured += row.captured }
        return GeneratedMediaStore(dao)
    }

    private fun toolWith(store: GeneratedMediaStore, url: String) = ImageGenTool(
        mockk(relaxed = true),
        mockk<ProviderKeys> { every { keyFor(any()) } returns null },
        mockk<com.aura.data.UserPreferences>(relaxed = true).also {
            every { it.imageModel } returns kotlinx.coroutines.flow.flowOf("")
        },
        capabilityRouter = routerReturning(url),
        generatedMedia = store,
    ).tool

    @Test
    fun `generating an image records that it exists`() = runTest {
        val captured = mutableListOf<GeneratedMediaEntity>()

        val result = toolWith(store(captured), "https://example.com/cat.png").execute(
            call("prompt" to "A cat wearing a hat"),
            ctx(),
        )

        assertTrue(result is ToolResult.Ok)
        assertEquals(1, captured.size, "the image was generated but nothing recorded it")
        assertEquals("A cat wearing a hat", captured.single().prompt)
        assertEquals("image", captured.single().kind)
        assertTrue(captured.single().storageUri.isNotBlank())
    }

    @Test
    fun `the row points at exactly what the user was handed`() = runTest {
        // The guard that matters: a row must never describe an image that was not produced.
        //
        // This was originally written as "a failed generation records nothing", by giving
        // the tool a provider that throws. It does not fail — it falls back to Pollinations,
        // whose URL is built by string concatenation with no network call, and `parseSize`
        // defaults rather than throwing on bad input. `generateImage` therefore has no
        // reachable failure path and the tool's `ToolResult.Error` branch is very nearly
        // dead code.
        //
        // So the property is proved the other way round: whatever URI reaches the user in
        // the [IMAGE:...] marker is the same URI in the row, which is what makes a row for
        // a non-existent image impossible.
        val captured = mutableListOf<GeneratedMediaEntity>()

        val result = toolWith(store(captured), "https://example.com/hat.png").execute(
            call("prompt" to "A cat"),
            ctx(),
        )

        val shown = (result as ToolResult.Ok).output.substringAfter("[IMAGE:").substringBefore("]")
        assertEquals(shown, captured.single().storageUri)
    }

    @Test
    fun `a recording failure does not fail the generation`() = runTest {
        // The image is the product; the row is bookkeeping. A database that cannot be
        // written must not turn a successful generation into an error the user sees.
        val dao = mockk<com.aura.media.GeneratedMediaDao>(relaxed = true)
        coEvery { dao.upsert(any()) } throws IllegalStateException("disk full")

        val result = toolWith(GeneratedMediaStore(dao), "https://example.com/cat.png").execute(
            call("prompt" to "A cat"),
            ctx(),
        )

        assertTrue(result is ToolResult.Ok, "a failed record took the image down with it: $result")
    }

    // Local copies: the equivalents in ImageGenToolTest are private to that class.

    private fun routerReturning(url: String): com.aura.capabilities.CapabilityRouter =
        mockk<com.aura.capabilities.CapabilityRouter>(relaxed = true).also { router ->
            val backend = mockk<com.aura.capabilities.ImageProvider>(relaxed = true)
            every { backend.displayName } returns "Test Images"
            coEvery { backend.generate(any()) } returns com.aura.capabilities.ImageResult(url = url)
            every {
                router.resolvePreferred(com.aura.capabilities.CapabilityKind.ImageGeneration, any())
            } returns backend
        }

    /** A backend that fails, so generateImage throws rather than returning a URI. */
    private fun throwingRouter(): com.aura.capabilities.CapabilityRouter =
        mockk<com.aura.capabilities.CapabilityRouter>(relaxed = true).also { router ->
            val backend = mockk<com.aura.capabilities.ImageProvider>(relaxed = true)
            every { backend.displayName } returns "Broken Images"
            coEvery { backend.generate(any()) } throws IllegalStateException("provider is down")
            every {
                router.resolvePreferred(com.aura.capabilities.CapabilityKind.ImageGeneration, any())
            } returns backend
        }

    private fun call(vararg pairs: Pair<String, Any?>): com.aura.agent.ToolCall =
        com.aura.agent.ToolCall(id = "tc1", name = "image_gen", arguments = mapOf(*pairs))

    private fun ctx() = com.aura.agent.ToolContext(conversationId = "conv-1")
}
