package com.aura.agent

import com.aura.providers.FinishReason
import com.aura.providers.ProviderChunk
import com.aura.providers.ProviderError
import com.aura.providers.ToolCall
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Locks the BrainChunk.fromProvider routing contract.
 *
 * The previous version of this function threw away the provider-resolved
 * tool id for delta-only chunks (e.g. Anthropic's `input_json_delta`
 * which carries an id resolved by index) and re-derived the id from
 * `nameById.keys.lastOrNull()`. With two parallel `tool_use` blocks,
 * the second tool's delta would overwrite the first tool's argument
 * buffer because `lastOrNull()` is the most-recently-seen id, not the
 * one this delta belongs to.
 *
 * These tests pin the contract: providers that resolve the id
 * (Anthropic) must have their id honored verbatim.
 */
class BrainFromProviderTest {

    @Test
    fun `text chunk maps to Text`() {
        val chunk = BrainChunk.fromProvider(ProviderChunk(text = "hello"))
        val text = assertIs<BrainChunk.Text>(chunk)
        assertEquals("hello", text.text)
    }

    @Test
    fun `empty text maps to Text empty rather than dropped`() {
        val chunk = BrainChunk.fromProvider(ProviderChunk(text = ""))
        val text = assertIs<BrainChunk.Text>(chunk)
        assertEquals("", text.text)
    }

    @Test
    fun `finishReason maps to Finished with name`() {
        val chunk = BrainChunk.fromProvider(ProviderChunk(finishReason = FinishReason.stop))
        val fin = assertIs<BrainChunk.Finished>(chunk)
        assertEquals("stop", fin.reason)
    }

    @Test
    fun `error maps to Error preserving retryable flag`() {
        val err = ProviderError("http_429", "rate limited", retryable = true)
        val chunk = BrainChunk.fromProvider(ProviderChunk(error = err))
        val e = assertIs<BrainChunk.Error>(chunk)
        assertEquals("http_429", e.code)
        assertEquals("rate limited", e.message)
        assertEquals(true, e.retryable)
        assertEquals(err, e.error)
    }

    @Test
    fun `first ToolCallStart emits once and is registered in nameById`() {
        val nameById = mutableMapOf<String, String>()
        val chunk = BrainChunk.fromProvider(
            ProviderChunk(toolCall = ToolCall("tc1", "remember", "")),
            nameById,
        )
        val start = assertIs<BrainChunk.ToolCallStart>(chunk)
        assertEquals("tc1", start.id)
        assertEquals("remember", start.name)
        assertEquals("remember", nameById["tc1"])
    }

    @Test
    fun `subsequent ToolCall with same id and empty args emits Delta to the same id`() {
        val nameById = mutableMapOf<String, String>()
        // First call registers the id.
        BrainChunk.fromProvider(
            ProviderChunk(toolCall = ToolCall("tc1", "remember", "")),
            nameById,
        )
        // Second call with same id, empty name, empty args should emit Delta.
        val chunk = BrainChunk.fromProvider(
            ProviderChunk(toolCall = ToolCall("tc1", "", "")),
            nameById,
        )
        val delta = assertIs<BrainChunk.ToolCallDelta>(chunk)
        assertEquals("tc1", delta.id)
    }

    @Test
    fun `Anthropic delta with resolved id routes to that id even when nameById has other entries`() {
        // Simulate the Anthropic parallel tool-call scenario:
        //   content_block_start for tool A: id="A", name="search"
        //   content_block_start for tool B: id="B", name="fetch"
        //   content_block_delta for tool A: id="A", name="", partial="{\"q\":\"x\"}"
        //   content_block_delta for tool B: id="B", name="", partial="{\"u\":\"y\"}"
        val nameById = mutableMapOf<String, String>()

        // Step 1: start tool A
        val aStart = BrainChunk.fromProvider(
            ProviderChunk(toolCall = ToolCall("A", "search", "")),
            nameById,
        )
        assertIs<BrainChunk.ToolCallStart>(aStart)
        assertEquals(1, nameById.size)
        assertEquals("search", nameById["A"])

        // Step 2: start tool B
        val bStart = BrainChunk.fromProvider(
            ProviderChunk(toolCall = ToolCall("B", "fetch", "")),
            nameById,
        )
        assertIs<BrainChunk.ToolCallStart>(bStart)
        assertEquals(2, nameById.size)
        assertEquals("search", nameById["A"])
        assertEquals("fetch", nameById["B"])

        // Step 3: delta for tool A — Anthropic resolves id by index and
        // emits with id="A", name="". The pre-fix code threw away the
        // resolved id and re-derived from nameById.keys.lastOrNull() ==
        // "B", so A's delta was mis-routed to B.
        val aDelta = BrainChunk.fromProvider(
            ProviderChunk(toolCall = ToolCall("A", "", "{\"q\":\"x\"}")),
            nameById,
        )
        val aDeltaChunk = assertIs<BrainChunk.ToolCallDelta>(aDelta)
        assertEquals("A", aDeltaChunk.id, "Anthropic delta id must be honored verbatim")
        assertEquals("{\"q\":\"x\"}", aDeltaChunk.argumentsDelta)

        // Step 4: delta for tool B — same shape, must route to B.
        val bDelta = BrainChunk.fromProvider(
            ProviderChunk(toolCall = ToolCall("B", "", "{\"u\":\"y\"}")),
            nameById,
        )
        val bDeltaChunk = assertIs<BrainChunk.ToolCallDelta>(bDelta)
        assertEquals("B", bDeltaChunk.id, "Anthropic delta id must be honored verbatim")
        assertEquals("{\"u\":\"y\"}", bDeltaChunk.argumentsDelta)
    }

    @Test
    fun `last-resort fallback to nameById for legacy providers with no id on delta`() {
        // Some legacy /v1/chat/completions providers emit a delta with
        // empty id and empty name. The fallback routes to the most-recent
        // id we saw in this stream. This is intentionally a last-resort
        // path — prefer id-tagged deltas when available.
        val nameById = mutableMapOf<String, String>()
        BrainChunk.fromProvider(
            ProviderChunk(toolCall = ToolCall("tc1", "remember", "")),
            nameById,
        )
        val chunk = BrainChunk.fromProvider(
            ProviderChunk(toolCall = ToolCall("", "", "{\"x\":1}")),
            nameById,
        )
        val delta = assertIs<BrainChunk.ToolCallDelta>(chunk)
        assertEquals("tc1", delta.id, "fallback should use most-recently-seen id")
        assertEquals("{\"x\":1}", delta.argumentsDelta)
    }

    @Test
    fun `delta with empty id and empty name and empty nameById maps to Text empty`() {
        // Without a known id and without any nameById context, we
        // can't route the delta. Emit an empty Text rather than
        // dropping the chunk silently — the loop uses Text to drive
        // streaming output, so an empty Text is a no-op rather than
        // a crash.
        val chunk = BrainChunk.fromProvider(
            ProviderChunk(toolCall = ToolCall("", "", "{\"x\":1}")),
            mutableMapOf(),
        )
        val text = assertIs<BrainChunk.Text>(chunk)
        assertEquals("", text.text)
    }

    @Test
    fun `ToolCallEnd emits full arguments verbatim on same id`() {
        val nameById = mutableMapOf<String, String>()
        BrainChunk.fromProvider(
            ProviderChunk(toolCall = ToolCall("tc1", "remember", "")),
            nameById,
        )
        val chunk = BrainChunk.fromProvider(
            ProviderChunk(toolCall = ToolCall("tc1", "remember", "{\"fact\":\"dark mode\"}")),
            nameById,
        )
        val end = assertIs<BrainChunk.ToolCallEnd>(chunk)
        assertEquals("tc1", end.id)
        assertEquals("remember", end.name)
        assertEquals("{\"fact\":\"dark mode\"}", end.arguments)
    }

    @Test
    fun `nameById is mutated correctly across the full stream`() {
        // Full provider stream: start A, start B, delta A, delta B, end A, end B, finish.
        val nameById = mutableMapOf<String, String>()
        val events = listOf(
            ProviderChunk(toolCall = ToolCall("A", "search", "")),
            ProviderChunk(toolCall = ToolCall("B", "fetch", "")),
            ProviderChunk(toolCall = ToolCall("A", "", "{\"q\":\"a\"}")),
            ProviderChunk(toolCall = ToolCall("B", "", "{\"u\":\"b\"}")),
            ProviderChunk(toolCall = ToolCall("A", "search", "{\"q\":\"a\"}")),
            ProviderChunk(toolCall = ToolCall("B", "fetch", "{\"u\":\"b\"}")),
            ProviderChunk(finishReason = FinishReason.tool_calls),
        )
        val routed = events.map { BrainChunk.fromProvider(it, nameById) }
        assertEquals(7, routed.size)
        assertTrue(routed[0] is BrainChunk.ToolCallStart && (routed[0] as BrainChunk.ToolCallStart).id == "A")
        assertTrue(routed[1] is BrainChunk.ToolCallStart && (routed[1] as BrainChunk.ToolCallStart).id == "B")
        assertTrue(routed[2] is BrainChunk.ToolCallDelta && (routed[2] as BrainChunk.ToolCallDelta).id == "A")
        assertTrue(routed[3] is BrainChunk.ToolCallDelta && (routed[3] as BrainChunk.ToolCallDelta).id == "B")
        assertTrue(routed[4] is BrainChunk.ToolCallEnd && (routed[4] as BrainChunk.ToolCallEnd).id == "A")
        assertTrue(routed[5] is BrainChunk.ToolCallEnd && (routed[5] as BrainChunk.ToolCallEnd).id == "B")
        assertTrue(routed[6] is BrainChunk.Finished)
        assertEquals(2, nameById.size)
    }
}
