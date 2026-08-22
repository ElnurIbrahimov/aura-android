package com.aura.agent

import com.aura.providers.ProviderMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for tool-history serialization in [Conversation.toMessages].
 *
 * Pre-fix, tool results were emitted as bare `role=tool` messages with no
 * preceding assistant `tool_calls` echo — strict providers (OpenAI,
 * Anthropic) reject that with a 400 on the request AFTER any tool call,
 * which broke multi-step tool use everywhere except lenient endpoints.
 */
class ConversationToMessagesTest {

    private fun withToolTurn(): Conversation = Conversation()
        .addUser("what's the weather in Baku?")
        .addAssistant("Let me check.")
        .addToolCall("call_1", "weather", """{"city":"Baku"}""")
        .setToolResult("call_1", "Sunny, 34C")

    @Test
    fun `assistant message echoes its tool calls`() {
        val messages = withToolTurn().toMessages(includeSystemPrompt = false)
        val assistant = messages.first { it.role == ProviderMessage.Role.assistant }
        assertEquals("Let me check.", assistant.content)
        val calls = assistant.toolCalls
        assertEquals(1, calls?.size)
        assertEquals("call_1", calls?.first()?.id)
        assertEquals("weather", calls?.first()?.name)
        assertEquals("""{"city":"Baku"}""", calls?.first()?.arguments)
    }

    @Test
    fun `tool message carries toolCallId and tool name`() {
        val messages = withToolTurn().toMessages(includeSystemPrompt = false)
        val tool = messages.first { it.role == ProviderMessage.Role.tool }
        assertEquals("call_1", tool.toolCallId)
        assertEquals("weather", tool.name)
        assertEquals("Sunny, 34C", tool.content)
    }

    @Test
    fun `assistant precedes its tool results on the wire`() {
        val messages = withToolTurn().toMessages(includeSystemPrompt = false)
        val assistantIdx = messages.indexOfFirst { it.role == ProviderMessage.Role.assistant }
        val toolIdx = messages.indexOfFirst { it.role == ProviderMessage.Role.tool }
        assertTrue(assistantIdx in 0 until toolIdx, "assistant ($assistantIdx) must precede tool ($toolIdx)")
    }

    @Test
    fun `dangling tool calls with no result are dropped from the wire`() {
        val messages = withToolTurn()
            .addToolCall("call_dangling", "email_send", "{}")
            .toMessages(includeSystemPrompt = false)
        val assistant = messages.first { it.role == ProviderMessage.Role.assistant }
        assertEquals(listOf("call_1"), assistant.toolCalls?.map { it.id })
        assertTrue(messages.none { it.toolCallId == "call_dangling" })
    }

    @Test
    fun `turn with tool calls but no assistant text still emits the assistant echo`() {
        val messages = Conversation()
            .addUser("search please")
            .addToolCall("call_2", "web_search", """{"q":"kotlin"}""")
            .setToolResult("call_2", "results...")
            .toMessages(includeSystemPrompt = false)
        val assistant = messages.first { it.role == ProviderMessage.Role.assistant }
        assertEquals("", assistant.content)
        assertEquals("call_2", assistant.toolCalls?.single()?.id)
    }

    @Test
    fun `turns without tool calls are unchanged`() {
        val messages = Conversation()
            .addUser("hi")
            .addAssistant("hello!")
            .toMessages(includeSystemPrompt = false)
        assertEquals(2, messages.size)
        assertNull(messages[1].toolCalls)
        assertEquals("hello!", messages[1].content)
    }

    // ---------------------------------------------------------------- pinning

    /** Twelve turns, of which turn 1 is pinned. */
    private fun longConversation(pinIndex: Int?): Conversation {
        val turns = (0 until 12).map { i ->
            Turn(
                user = "question $i",
                assistant = "answer $i",
                timestamp = 1_000L + i,
                pinned = i == pinIndex,
            )
        }
        return Conversation(turns = turns)
    }

    @Test
    fun `a pinned turn survives the maxTurns cutoff`() {
        // The whole meaning of Turn.pinned. Before this, the flag was persisted,
        // exported in every backup, and read by nothing — ConversationStore
        // wrote it, ChatViewModel called that, and no screen called either.
        val messages = longConversation(pinIndex = 1)
            .toMessages(maxTurns = 3, includeSystemPrompt = false)

        val texts = messages.mapNotNull { it.content }
        assertTrue("question 1" in texts, "the pinned turn must still reach the model")
        assertTrue("question 0" !in texts, "an unpinned turn behind the cutoff must not")
        assertTrue("question 11" in texts, "the tail is unaffected")
    }

    @Test
    fun `a pinned turn survives the compaction watermark`() {
        // The case that matters more: maxTurns is a window that slides, but
        // summaryThroughTurn is permanent — those turns have been replaced by a
        // sentence and are never coming back on their own.
        val messages = longConversation(pinIndex = 1)
            .copy(contextSummary = "They discussed things.", summaryThroughTurn = 8)
            .toMessages(includeSystemPrompt = false)

        val texts = messages.mapNotNull { it.content }
        assertTrue("question 1" in texts, "a pinned turn is exempt from the summary watermark too")
        assertTrue("question 2" !in texts, "its unpinned neighbours are still summarised")
    }

    @Test
    fun `pinning preserves transcript order`() {
        // Not hoisted into a section of its own. A pinned turn re-inserted after
        // the summary that already describes it reads as the model being told
        // the same thing twice, out of sequence.
        val messages = longConversation(pinIndex = 1)
            .toMessages(maxTurns = 3, includeSystemPrompt = false)

        val texts = messages.mapNotNull { it.content }
        assertTrue(
            texts.indexOf("question 1") < texts.indexOf("question 9"),
            "the pinned turn keeps its original position, not a promoted one",
        )
    }

    @Test
    fun `pinning nothing leaves the cutoffs exactly as they were`() {
        val messages = longConversation(pinIndex = null)
            .toMessages(maxTurns = 3, includeSystemPrompt = false)

        val texts = messages.mapNotNull { it.content }
        assertTrue("question 8" !in texts)
        assertTrue("question 9" in texts)
    }
}
