package com.aura.agent

import com.aura.providers.ProviderMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for sequential tool steps in [Conversation.toMessages].
 *
 * The agentic loop only opens a new [Turn] when a model step produces assistant
 * text. A step that emits tool calls and nothing else — the normal shape for a
 * tool-calling model with extended thinking on — appends its calls to the turn
 * a previous step already owns. Before [ToolTurn.step], `toMessages` emitted the
 * whole turn as one assistant message, so a chain like
 *
 *     step 1: web_search("...")        -> results
 *     step 2: read_url(top result)     -> page text
 *
 * replayed as "the model requested web_search and read_url simultaneously". The
 * second call could only have been chosen *after* seeing the first result, so
 * the wire history described a decision the model never made — on every re-send
 * and every history replay. It is structurally valid for every provider, so
 * nothing rejected it and nothing caught it.
 *
 * [ConversationToMessagesTest] covers the single-step and parallel-batch cases;
 * this file covers the sequential case, which had no coverage at all.
 */
class ConversationSequentialToolStepsTest {

    /** Two tool calls issued by two different steps, on one turn. */
    private fun sequential(): Conversation = Conversation()
        .addUser("find the top Kotlin coroutines guide and summarise it")
        .addToolCall("call_search", "web_search", """{"q":"kotlin coroutines guide"}""", step = 1)
        .setToolResult("call_search", "1. kotlinlang.org/coroutines-guide")
        .addToolCall("call_read", "read_url", """{"url":"kotlinlang.org/coroutines-guide"}""", step = 2)
        .setToolResult("call_read", "Coroutines are light-weight threads...")
        .addAssistant("The guide explains structured concurrency.")

    @Test
    fun `sequential tool steps produce one assistant message each`() {
        val messages = sequential().toMessages(includeSystemPrompt = false)
        val toolCallMessages = messages.filter { it.role == ProviderMessage.Role.assistant && it.toolCalls != null }

        assertEquals(
            2,
            toolCallMessages.size,
            "two steps must produce two assistant tool-call messages, not one merged batch",
        )
        assertEquals(listOf("call_search"), toolCallMessages[0].toolCalls?.map { it.id })
        assertEquals(listOf("call_read"), toolCallMessages[1].toolCalls?.map { it.id })
    }

    @Test
    fun `each tool result immediately follows the step that requested it`() {
        val messages = sequential().toMessages(includeSystemPrompt = false)
        val order = messages.map { it.role to (it.toolCallId ?: it.toolCalls?.firstOrNull()?.id) }

        val searchCall = order.indexOfFirst { it == ProviderMessage.Role.assistant to "call_search" }
        val searchResult = order.indexOfFirst { it == ProviderMessage.Role.tool to "call_search" }
        val readCall = order.indexOfFirst { it == ProviderMessage.Role.assistant to "call_read" }
        val readResult = order.indexOfFirst { it == ProviderMessage.Role.tool to "call_read" }

        assertTrue(searchCall >= 0 && searchResult >= 0 && readCall >= 0 && readResult >= 0, "all four present: $order")
        assertTrue(
            searchCall < searchResult && searchResult < readCall && readCall < readResult,
            "the second call must come after the first result, got: $order",
        )
    }

    @Test
    fun `the turn's assistant text attaches to the first step only`() {
        val messages = sequential().toMessages(includeSystemPrompt = false)
        val assistants = messages.filter { it.role == ProviderMessage.Role.assistant }

        // The final text lives on its own turn (addAssistant opens one once the
        // turn already carries tool calls), so the tool-call messages carry no
        // text and the text message carries no calls. What must never happen is
        // the same text being repeated onto every step's message.
        assertEquals(
            1,
            assistants.count { it.content == "The guide explains structured concurrency." },
            "assistant text must not be duplicated across step messages: ${assistants.map { it.content }}",
        )
    }

    @Test
    fun `calls sharing a step stay in one parallel batch`() {
        val messages = Conversation()
            .addUser("compare the weather in Baku and Tbilisi")
            .addToolCall("call_a", "weather", """{"city":"Baku"}""", step = 1)
            .addToolCall("call_b", "weather", """{"city":"Tbilisi"}""", step = 1)
            .setToolResult("call_a", "34C")
            .setToolResult("call_b", "28C")
            .toMessages(includeSystemPrompt = false)

        val toolCallMessages = messages.filter { it.role == ProviderMessage.Role.assistant && it.toolCalls != null }
        assertEquals(1, toolCallMessages.size, "a genuine parallel batch must stay in one message")
        assertEquals(listOf("call_a", "call_b"), toolCallMessages.single().toolCalls?.map { it.id })
    }

    @Test
    fun `untagged tool calls replay as a single batch`() {
        // Conversations persisted before ToolTurn.step existed decode it as 0.
        // They must serialize exactly as they did before the field was added,
        // or every saved history would shift shape on the next re-send.
        val messages = Conversation()
            .addUser("hello")
            .addAssistant("Checking.")
            .addToolCall("call_1", "weather", "{}")
            .setToolResult("call_1", "Sunny")
            .addToolCall("call_2", "timer", "{}")
            .setToolResult("call_2", "Set")
            .toMessages(includeSystemPrompt = false)

        val toolCallMessages = messages.filter { it.role == ProviderMessage.Role.assistant && it.toolCalls != null }
        assertEquals(1, toolCallMessages.size)
        assertEquals(listOf("call_1", "call_2"), toolCallMessages.single().toolCalls?.map { it.id })
        assertEquals("Checking.", toolCallMessages.single().content)
    }

    @Test
    fun `roles still alternate for strict providers`() {
        // Anthropic requires strict user/assistant alternation once tool_result
        // blocks are folded into user messages. ProviderToolHistorySerialization-
        // Test pins this on the wire; assert the shape here so a grouping change
        // fails in the cheap test first.
        val messages = sequential().toMessages(includeSystemPrompt = false)
        val roles = messages.map {
            // tool results are carried as user-role blocks by Anthropic
            if (it.role == ProviderMessage.Role.tool) ProviderMessage.Role.user else it.role
        }
        for (i in 1 until roles.size) {
            assertTrue(roles[i] != roles[i - 1], "roles must alternate, got $roles")
        }
    }
}
