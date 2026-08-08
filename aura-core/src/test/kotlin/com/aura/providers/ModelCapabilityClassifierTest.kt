package com.aura.providers

import com.aura.capabilities.CapabilityKind
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `canChat` answered "is this a chat model?"; `classify` answers "what is it?".
 *
 * The second question is what lets a configured token's image and video models
 * be routed automatically instead of requiring a hand-written adapter per
 * vendor. `OpenAiCompatChatCapabilityTest` remains the regression net proving
 * the boolean behaviour did not change; this pins the richer answer.
 */
class ModelCapabilityClassifierTest {

    private val provider = OpenAiCompatProvider(
        prefix = "test",
        displayName = "Test",
        baseUrl = "https://example.test/v1",
        providerKeys = mockk(relaxed = true),
        httpClient = mockk(relaxed = true),
    )

    private fun entry(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun classify(id: String, extra: String = ""): ModelCapability {
        val body = if (extra.isBlank()) """{"id":"$id"}""" else """{"id":"$id",$extra}"""
        return provider.classify(entry(body), id)
    }

    // ── the live Agnes catalog, model by model ─────────────────────

    @Test
    fun `the real Agnes catalog classifies into three kinds`() {
        val agnes = """"object":"model","supported_endpoint_types":["openai""""

        assertEquals(ModelCapability.Unknown, classify("agnes-2.0-flash", "$agnes]"))
        assertEquals(ModelCapability.Unknown, classify("agnes-2.5-flash", "$agnes]"))
        assertEquals(ModelCapability.Image, classify("agnes-image-2.0-flash", "$agnes]"))
        assertEquals(ModelCapability.Image, classify("agnes-image-2.1-flash", "$agnes]"))
        assertEquals(ModelCapability.Video, classify("agnes-video-v2.0", "$agnes]"))
    }

    @Test
    fun `the chat models are chat-usable and the others are not`() {
        assertTrue(classify("agnes-2.0-flash").isChatUsable)
        assertFalse(classify("agnes-image-2.1-flash").isChatUsable)
        assertFalse(classify("agnes-video-v2.0").isChatUsable)
    }

    // ── kinds, by id ───────────────────────────────────────────────

    @Test
    fun `known families classify to their kind`() {
        assertEquals(ModelCapability.Image, classify("dall-e-3"))
        assertEquals(ModelCapability.Image, classify("gpt-image-1"))
        assertEquals(ModelCapability.Image, classify("flux-pro"))
        assertEquals(ModelCapability.Video, classify("veo-3"))
        assertEquals(ModelCapability.Video, classify("sora-2"))
        assertEquals(ModelCapability.Embedding, classify("text-embedding-3-large"))
        assertEquals(ModelCapability.Transcription, classify("whisper-1"))
        assertEquals(ModelCapability.Speech, classify("tts-1-hd"))
        assertEquals(ModelCapability.Moderation, classify("omni-moderation-latest"))
    }

    @Test
    fun `an unclassifiable model is Unknown, which counts as chat`() {
        // The bias inherited from canChat: a model that errors when used
        // announces itself; a real chat model missing from the picker does not.
        assertEquals(ModelCapability.Unknown, classify("gpt-4o"))
        assertEquals(ModelCapability.Unknown, classify("claude-opus-4-5"))
        assertEquals(ModelCapability.Unknown, classify("something-new-v1"))
        assertTrue(classify("something-new-v1").isChatUsable)
    }

    // ── kinds, by declaration ──────────────────────────────────────

    @Test
    fun `a declaration beats the id`() {
        assertEquals(ModelCapability.Image, classify("mystery-9", """"type":"image""""))
        assertEquals(ModelCapability.Video, classify("mystery-9", """"modality":"text-to-video""""))
        assertEquals(ModelCapability.Speech, classify("mystery-9", """"type":"tts""""))
        // A declared chat model keeps its declaration even with an image-y id.
        assertEquals(ModelCapability.Chat, classify("image-reasoner-v2", """"type":"chat""""))
    }

    @Test
    fun `OpenAI's generic object model still falls through to the id`() {
        assertEquals(ModelCapability.Image, classify("dall-e-3", """"object":"model""""))
    }

    // ── the bridge to CapabilityKind ───────────────────────────────

    @Test
    fun `model kinds map to the capability backends that can serve them`() {
        assertEquals(CapabilityKind.ImageGeneration, ModelCapability.Image.toCapabilityKind())
        assertEquals(CapabilityKind.VideoGeneration, ModelCapability.Video.toCapabilityKind())
        assertEquals(CapabilityKind.TextToSpeech, ModelCapability.Speech.toCapabilityKind())
        assertEquals(CapabilityKind.Transcription, ModelCapability.Transcription.toCapabilityKind())
    }

    @Test
    fun `kinds with no capability backend map to null`() {
        // Chat is Provider's job, not the capability registry's; the other
        // three have no CapabilityKind at all. Mapping them to something would
        // register backends that cannot be invoked.
        assertNull(ModelCapability.Chat.toCapabilityKind())
        assertNull(ModelCapability.Unknown.toCapabilityKind())
        assertNull(ModelCapability.Embedding.toCapabilityKind())
        assertNull(ModelCapability.Rerank.toCapabilityKind())
        assertNull(ModelCapability.Moderation.toCapabilityKind())
    }

    // ── the derived sets stay in sync ──────────────────────────────

    @Test
    fun `the non-chat marker sets are derived, not duplicated`() {
        // They used to be hand-maintained Sets alongside the classifier. Now
        // they are the maps' keys, so a marker can never be in one and missing
        // from the other.
        assertEquals(
            OpenAiCompatProvider.DECLARATION_CAPABILITY.keys,
            OpenAiCompatProvider.NON_CHAT_DECLARATIONS,
        )
        assertEquals(
            OpenAiCompatProvider.ID_SEGMENT_CAPABILITY.keys,
            OpenAiCompatProvider.NON_CHAT_ID_SEGMENTS,
        )
        assertTrue(
            "every marker must name a non-chat kind",
            (OpenAiCompatProvider.DECLARATION_CAPABILITY.values +
                OpenAiCompatProvider.ID_SEGMENT_CAPABILITY.values).none { it.isChatUsable },
        )
    }
}
