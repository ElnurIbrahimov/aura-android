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
}
