package com.aura.providers

import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/v1/models` is not a list of chat models, and the shared OpenAI-compatible
 * path treated it as one.
 *
 * Agnes AI returns `agnes-image-2.1-flash` in its catalog. Selecting it in the
 * chat picker produced:
 *
 *     HTTP 400 {"code":"invalid_request","message":"Model agnes-image-2.1-flash
 *     is an image model. Use /v1/images/generations."}
 *
 * `f69f353a` fixed the same class of defect for ChatGPT and Gemini, but did it
 * per provider; every OpenAI-compatible provider shared this unfiltered path.
 *
 * The risk in filtering is over-filtering: dropping a real chat model is worse
 * than admitting one that errors, because a missing model is invisible while a
 * broken one announces itself. Both directions are pinned below.
 */
class OpenAiCompatChatCapabilityTest {

    private val provider = OpenAiCompatProvider(
        prefix = "test",
        displayName = "Test",
        baseUrl = "https://example.test/v1",
        providerKeys = mockk(relaxed = true),
        httpClient = mockk(relaxed = true),
    )

    private fun entry(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun canChat(id: String, extra: String = ""): Boolean {
        val body = if (extra.isBlank()) """{"id":"$id"}""" else """{"id":"$id",$extra}"""
        return provider.canChat(entry(body), id)
    }

    // ── the reported failure ───────────────────────────────────────

    @Test
    fun `the Agnes image model is not offered for chat`() {
        assertFalse(canChat("agnes-image-2.1-flash"))
    }

    @Test
    fun `Agnes chat models are still offered`() {
        assertTrue(canChat("agnes-2.1-flash"))
        assertTrue(canChat("agnes-pro"))
    }

    // ── a declared type wins over the id ───────────────────────────

    @Test
    fun `a declared image type excludes the model whatever its name`() {
        assertFalse(canChat("mystery-model-9", """"type":"image""""))
        assertFalse(canChat("mystery-model-9", """"modality":"text-to-image""""))
    }

    @Test
    fun `a declared chat type keeps a model whose name looks like an image one`() {
        // The declaration is authoritative. Without this, a provider that
        // labels its entries properly could still lose a real model to the
        // name fallback.
        assertTrue(canChat("image-reasoner-v2", """"type":"chat""""))
    }

    @Test
    fun `OpenAI's generic object model is not treated as a declaration`() {
        // Every OpenAI entry carries `"object":"model"`, which says nothing
        // about capability. Treating it as informative would disable the id
        // fallback for the single most common catalog shape.
        assertFalse(canChat("dall-e-3", """"object":"model""""))
        assertTrue(canChat("gpt-4o", """"object":"model""""))
    }

    // ── the id fallback, and its deliberate narrowness ─────────────

    @Test
    fun `known non-chat families are excluded by id`() {
        assertFalse(canChat("dall-e-3"))
        assertFalse(canChat("gpt-image-1"))
        assertFalse(canChat("text-embedding-3-large"))
        assertFalse(canChat("whisper-1"))
        assertFalse(canChat("tts-1-hd"))
        assertFalse(canChat("omni-moderation-latest"))
        assertFalse(canChat("veo-3"))
        assertFalse(canChat("sora-2"))
    }

    @Test
    fun `real chat models survive`() {
        listOf(
            "gpt-4o", "gpt-4o-mini", "o3-mini", "claude-opus-4-5",
            "deepseek-reasoner", "llama-3.3-70b-versatile", "grok-4",
            "mistral-large-latest", "qwen3-235b", "gemini-2.5-pro",
        ).forEach { assertTrue("$it must remain selectable", canChat(it)) }
    }

    @Test
    fun `matching is by whole segment, never substring`() {
        // The narrowness that keeps the filter safe. "image" as a substring
        // appears in plausible chat model names; as a segment it does not.
        assertTrue(canChat("imagen-reasoner"))
        assertTrue(canChat("visionary-7b"))
        assertTrue(canChat("attschat-embedded"))
        assertTrue(canChat("speechless-13b"))
    }

    @Test
    fun `an unrecognised model is kept`() {
        // The bias that matters: a model that errors when used is a smaller
        // failure than a real chat model silently missing from the picker.
        assertTrue(canChat("something-entirely-new-v1"))
        assertTrue(canChat("zzz"))
    }

    // ── verified against the live Agnes AI catalog, 2026-08-08 ─────
    //
    // GET https://apihub.agnes-ai.com/v1/models returns exactly these five
    // entries. Every one carries `"object":"model"` and
    // `"supported_endpoint_types":["openai"]` — chat and image alike — and no
    // `type`, `modality` or `model_type` at all. So the catalog offers NO
    // signal, and the id fallback is not a nicety here: it is the only thing
    // standing between the user and an image model in the chat picker.

    @Test
    fun `the real Agnes catalog splits into chat and non-chat correctly`() {
        val agnesEntry = { id: String ->
            """"object":"model","created":1626777600,"owned_by":"custom","supported_endpoint_types":["openai"]"""
                .let { extra -> canChat(id, extra) }
        }

        assertTrue("agnes-2.0-flash is a chat model", agnesEntry("agnes-2.0-flash"))
        assertTrue("agnes-2.5-flash is a chat model", agnesEntry("agnes-2.5-flash"))
        assertFalse("agnes-image-2.0-flash cannot chat", agnesEntry("agnes-image-2.0-flash"))
        assertFalse("agnes-image-2.1-flash cannot chat", agnesEntry("agnes-image-2.1-flash"))
        assertFalse("agnes-video-v2.0 cannot chat", agnesEntry("agnes-video-v2.0"))
    }

    @Test
    fun `supported_endpoint_types is not mistaken for a capability declaration`() {
        // It reads like one and is not: Agnes reports ["openai"] for its image
        // models too. Treating it as informative would keep every entry and
        // reinstate the exact 400 this filter exists to prevent.
        assertFalse(canChat("agnes-image-2.1-flash", """"supported_endpoint_types":["openai"]"""))
    }
}
