package com.aura.agent

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for AGENTIC_LOOP_AUDIT A5.
 *
 * Pre-fix: `Conversation.addToolCall` on an empty
 * conversation created a `Turn` with no `user` text and
 * no `assistant` text — just `toolTurns`. The downstream
 * provider would see a tool call without any preceding
 * user message, breaking the conversation contract for
 * strict providers (Anthropic rejects tool_use blocks
 * without a preceding user turn).
 *
 * Fix: refuse to add a tool call to an empty
 * conversation. Return `this` (no-op) instead of
 * creating a malformed turn.
 */
class ConversationAddToolCallTest {

    @Test
    fun `addToolCall on empty conversation is a no-op`() {
        val empty = Conversation()
        val result = empty.addToolCall("t1", "search", "{}")
        // Should not crash, should not create a turn
        assertEquals(empty, result,
            "addToolCall on empty conversation must return the same Conversation unchanged")
        assertTrue(result.turns.isEmpty(),
            "No turn should be created when addToolCall is called on an empty conversation")
    }

    @Test
    fun `addToolCall on non-empty conversation appends to last turn`() {
        val conv = Conversation().addUser("hello")
        val result = conv.addToolCall("t1", "search", "{}")
        assertEquals(1, result.turns.size)
        assertEquals("hello", result.turns[0].user)
        assertEquals(1, result.turns[0].toolTurns.size)
        assertEquals("t1", result.turns[0].toolTurns[0].id)
        assertEquals("search", result.turns[0].toolTurns[0].name)
        assertEquals("{}", result.turns[0].toolTurns[0].args)
        // The user's "hello" message is preserved — the
        // turn is anchored by a user message, so the
        // provider can validate the conversation
        // contract.
    }

    @Test
    fun `addToolCall accumulates multiple tool calls on the same turn`() {
        val conv = Conversation().addUser("hello")
            .addToolCall("t1", "search", "{}")
            .addToolCall("t2", "summarize", "{\"text\":\"x\"}")
        assertEquals(1, conv.turns.size)
        assertEquals(2, conv.turns[0].toolTurns.size)
        assertEquals("t1", conv.turns[0].toolTurns[0].id)
        assertEquals("t2", conv.turns[0].toolTurns[1].id)
    }
}
