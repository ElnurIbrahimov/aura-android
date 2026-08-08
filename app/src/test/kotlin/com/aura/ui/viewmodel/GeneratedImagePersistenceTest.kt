package com.aura.ui.viewmodel

import com.aura.agent.Conversation
import com.aura.agent.ToolTurn
import com.aura.agent.Turn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Generated images used to disappear the instant a run finished.
 *
 * `AgentEvent.ToolResult` attached them to the UI's copy of the conversation;
 * `AgentEvent.Result` then replaced that copy wholesale with the loop's, which
 * had never been told about them. So the image rendered while streaming, and
 * vanished on completion. Nothing was persisted either, so reopening the
 * conversation from History never showed it — the URL sat unused in the tool
 * result the whole time.
 *
 * Deriving the images from `toolTurns` fixes all three cases at once, and is
 * why this is a pure function over one conversation rather than a merge of two.
 */
class GeneratedImagePersistenceTest {

    private fun turnWithImageTool(url: String) = Turn(
        user = "generate a sword image",
        assistant = "Here you go.",
        toolTurns = listOf(
            ToolTurn(
                id = "t1",
                name = "image_generate",
                args = "{}",
                result = "Image generated via Agnes AI · agnes-image-2.0-flash.\nURL: $url\n[IMAGE:$url]",
            ),
        ),
    )

    @Test
    fun `an image url in a tool result becomes a generated image`() {
        val url = "https://platform-outputs.agnes-ai.space/images/t2i/b8d74297.png"
        val conv = Conversation(turns = listOf(turnWithImageTool(url)))

        val restored = conv.withImagesFromToolResults()

        assertEquals(listOf(url), restored.turns.single().generatedImages)
    }

    @Test
    fun `a conversation saved before the fix recovers its images on load`() {
        // Exactly the shape found on the device: the URL present in the tool
        // result, generatedImages empty.
        val url = "https://platform-outputs.agnes-ai.space/images/t2i/cb812623.png"
        val stored = Conversation(turns = listOf(turnWithImageTool(url).copy(generatedImages = emptyList())))

        val restored = stored.withImagesFromToolResults()

        assertEquals(listOf(url), restored.turns.single().generatedImages)
    }

    @Test
    fun `images already attached are kept and not duplicated`() {
        val url = "https://example.com/a.png"
        val conv = Conversation(turns = listOf(turnWithImageTool(url).copy(generatedImages = listOf(url))))

        val restored = conv.withImagesFromToolResults()

        assertEquals(listOf(url), restored.turns.single().generatedImages)
    }

    @Test
    fun `several images across several turns each land on their own turn`() {
        val a = "https://example.com/a.png"
        val b = "https://example.com/b.png"
        val conv = Conversation(turns = listOf(turnWithImageTool(a), turnWithImageTool(b)))

        val restored = conv.withImagesFromToolResults()

        assertEquals(listOf(a), restored.turns[0].generatedImages)
        assertEquals(listOf(b), restored.turns[1].generatedImages)
    }

    @Test
    fun `turns without image tools are untouched`() {
        val conv = Conversation(
            turns = listOf(
                Turn(user = "hi", assistant = "hello"),
                Turn(
                    user = "search",
                    assistant = "found it",
                    toolTurns = listOf(ToolTurn("t", "web_search", "{}", "some results, no images")),
                ),
            ),
        )

        val restored = conv.withImagesFromToolResults()

        assertTrue(restored.turns.all { it.generatedImages.isEmpty() })
        assertEquals(conv, restored)
    }

    @Test
    fun `a tool result carrying two markers yields both`() {
        val conv = Conversation(
            turns = listOf(
                Turn(
                    user = "two please",
                    toolTurns = listOf(
                        ToolTurn("t", "image_generate", "{}", "[IMAGE:https://x/1.png] and [IMAGE:https://x/2.png]"),
                    ),
                ),
            ),
        )

        val restored = conv.withImagesFromToolResults()

        assertEquals(listOf("https://x/1.png", "https://x/2.png"), restored.turns.single().generatedImages)
    }
}
