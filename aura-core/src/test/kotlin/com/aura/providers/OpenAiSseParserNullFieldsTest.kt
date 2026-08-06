package com.aura.providers

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for JSON-null handling in [OpenAiSseParser].
 *
 * kotlinx.serialization models JSON `null` as `JsonNull`, which **is a
 * `JsonPrimitive`** whose `.content` is the literal string `"null"`. So
 * `(delta["content"] as? JsonPrimitive)?.content` returns `"null"` — not
 * `null` — for `{"content": null}`.
 *
 * Every OpenAI-compatible server puts explicit nulls on the wire during a
 * normal stream:
 *
 *   {"choices":[{"delta":{"content":null,"reasoning_content":"Hm"},
 *                "finish_reason":null}]}
 *
 * Pre-fix that produced two failures at once:
 *   1. the literal word "null" was emitted as assistant text, and
 *   2. `finish_reason` read as the non-null string "null", so the very
 *      first chunk mapped to FinishReason.stop and the stream was closed
 *      and cancelled before any real content arrived.
 *
 * The existing MockWebServer suites never caught this: their fixtures only
 * ever send populated fields.
 */
class OpenAiSseParserNullFieldsTest {

    @Test
    fun `explicit null content is not emitted as the text "null"`() {
        val chunks = OpenAiSseParser().parseEvent(
            """{"choices":[{"delta":{"content":null},"finish_reason":null}]}""",
        )
        assertTrue(
            chunks.none { it.text != null },
            "a null content delta must emit no text chunk, got ${chunks.map { it.text }}",
        )
    }

    @Test
    fun `explicit null finish_reason does not terminate the stream`() {
        val chunks = OpenAiSseParser().parseEvent(
            """{"choices":[{"delta":{"content":"Hel"},"finish_reason":null}]}""",
        )
        assertNull(
            chunks.firstOrNull { it.finishReason != null }?.finishReason,
            "finish_reason:null must not be read as a finish signal — it ends the stream on chunk 1",
        )
        assertEquals("Hel", chunks.single { it.text != null }.text)
    }

    @Test
    fun `null content alongside reasoning_content emits only the reasoning`() {
        val chunks = OpenAiSseParser().parseEvent(
            """{"choices":[{"delta":{"content":null,"reasoning_content":"thinking..."},"finish_reason":null}]}""",
        )
        assertTrue(chunks.none { it.text != null })
        assertEquals("thinking...", chunks.single { it.thinking != null }.thinking)
    }

    @Test
    fun `explicit null reasoning_content is not emitted as thinking`() {
        val chunks = OpenAiSseParser().parseEvent(
            """{"choices":[{"delta":{"content":"hi","reasoning_content":null},"finish_reason":null}]}""",
        )
        assertTrue(
            chunks.none { it.thinking != null },
            "a null reasoning_content must emit no thinking chunk",
        )
    }

    @Test
    fun `tool call argument deltas with null id and name resolve by index`() {
        val parser = OpenAiSseParser()
        parser.parseEvent(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"weather","arguments":""}}]},"finish_reason":null}]}""",
        )
        // Continuation delta: OpenAI-compatible servers send explicit nulls
        // for id and name here, not absent keys.
        val chunks = parser.parseEvent(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":null,"function":{"name":null,"arguments":"{\"city\":"}}]},"finish_reason":null}]}""",
        )
        val call = chunks.single { it.toolCall != null }.toolCall!!
        assertEquals("call_1", call.id, "null id must resolve from the index map, not become \"null\"")
        assertEquals("", call.name, "a null name must be empty, not the string \"null\"")
        assertEquals("""{"city":""", call.arguments)
    }

    @Test
    fun `a real finish_reason still terminates the stream`() {
        val chunks = OpenAiSseParser().parseEvent(
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
        )
        assertEquals(FinishReason.stop, chunks.single { it.finishReason != null }.finishReason)
    }

    @Test
    fun `tool_calls finish reason survives`() {
        val chunks = OpenAiSseParser().parseEvent(
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
        )
        assertEquals(FinishReason.tool_calls, chunks.single { it.finishReason != null }.finishReason)
    }
}
