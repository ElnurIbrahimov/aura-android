package com.aura.providers

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression test for parallel tool-call index→id resolution in
 * [OpenAiCompatProvider.chat].
 *
 * The OpenAI streaming API sends tool-call deltas with an `index` field
 * that identifies which tool call the delta belongs to. The `id` and
 * `name` are only sent on the first delta for each tool call. Without
 * tracking the index→id mapping, subsequent argument deltas (which have
 * empty `id` and empty `name`) are mis-routed by Brain.fromProvider's
 * lastOrNull() fallback — the same bug class as the Anthropic parallel
 * tool-call fix (commit 5c09d6d7).
 *
 * This test feeds a MockWebServer SSE stream that simulates two parallel
 * tool calls and verifies the provider emits chunks with the correct
 * resolved id on every delta.
 */
class OpenAiCompatParallelToolCallTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: OpenAiCompatProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val keys = mockk<ProviderKeys> {
            every { keyFor("test") } returns "test-key"
            every { isConfigured("test") } returns true
        }
        provider = OpenAiCompatProvider(
            prefix = "test",
            displayName = "Test",
            baseUrl = server.url("/").toString().removeSuffix("/"),
            providerKeys = keys,
            httpClient = OkHttpClient(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `parallel tool calls route argument deltas to the correct id via index`() = runBlocking {
        // Simulate the OpenAI SSE stream for two parallel tool calls.
        // Each SSE event is `data: <json>\n\n` (double newline terminates
        // an event). The EventSource parses each event and calls onEvent.
        val sseData = listOf(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_A","type":"function","function":{"name":"search","arguments":""}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call_B","type":"function","function":{"name":"fetch","arguments":""}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"q\":\"test\"}"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":1,"function":{"arguments":"{\"u\":\"http://x\"}"}}]}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
            "[DONE]",
        )
        // Each event MUST be terminated by \n\n (blank line) per the SSE spec.
        val sseBody = sseData.joinToString("") { "data: $it\n\n" }

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody),
        )

        val chunks = withTimeout(10_000L) {
            provider.chat("test-model", listOf(
                ProviderMessage(role = ProviderMessage.Role.user, content = "search and fetch"),
            ), ChatOptions(), emptyList()).toList()
        }

        // Extract tool-call chunks
        val toolChunks = chunks.filter { it.toolCall != null }
        assertTrue(toolChunks.size >= 4, "Expected at least 4 tool-call chunks, got ${toolChunks.size}")

        // Chunk 0: call_A start — id + name
        val chunk0 = toolChunks[0]
        assertEquals("call_A", chunk0.toolCall?.id, "First chunk should have id call_A")
        assertEquals("search", chunk0.toolCall?.name, "First chunk should have name search")

        // Chunk 1: call_B start — id + name
        val chunk1 = toolChunks[1]
        assertEquals("call_B", chunk1.toolCall?.id, "Second chunk should have id call_B")
        assertEquals("fetch", chunk1.toolCall?.name, "Second chunk should have name fetch")

        // Chunk 2: call_A delta — index=0, no id, no name → should resolve to call_A
        val chunk2 = toolChunks[2]
        assertEquals("call_A", chunk2.toolCall?.id, "Third chunk (index=0 delta) should resolve to call_A")
        assertEquals("", chunk2.toolCall?.name, "Delta chunk should have empty name")

        // Chunk 3: call_B delta — index=1, no id, no name → should resolve to call_B
        val chunk3 = toolChunks[3]
        assertEquals("call_B", chunk3.toolCall?.id, "Fourth chunk (index=1 delta) should resolve to call_B")
        assertEquals("", chunk3.toolCall?.name, "Delta chunk should have empty name")

        // Verify finish reason
        val finishChunk = chunks.firstOrNull { it.finishReason != null }
        assertNotNull(finishChunk, "Should have a finish chunk")
        assertEquals(FinishReason.tool_calls, finishChunk.finishReason)
    }

    @Test
    fun `single tool call works without index field`() = runBlocking {
        // Some older or simpler OpenAI-compatible servers might not send
        // the `index` field at all. In that case, the provider should
        // still emit the id and name from the first delta.
        val sseData = listOf(
            """{"choices":[{"delta":{"tool_calls":[{"id":"call_X","type":"function","function":{"name":"search","arguments":""}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"function":{"arguments":"{\"q\":\"hello\"}"}}]}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
            "[DONE]",
        )
        val sseBody = sseData.joinToString("") { "data: $it\n\n" }

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody),
        )

        val chunks = withTimeout(10_000L) {
            provider.chat("test-model", listOf(
                ProviderMessage(role = ProviderMessage.Role.user, content = "search hello"),
            ), ChatOptions(), emptyList()).toList()
        }

        val toolChunks = chunks.filter { it.toolCall != null }
        assertTrue(toolChunks.size >= 1, "Expected at least 1 tool-call chunk")

        // First chunk should have the id and name
        assertEquals("call_X", toolChunks[0].toolCall?.id)
        assertEquals("search", toolChunks[0].toolCall?.name)
    }

    /**
     * P0-AGENTIC-F1 regression: a single SSE event may carry multiple
     * parallel tool calls in its `tool_calls` array (a real pattern in
     * vLLM, Together, and some OpenAI proxies). The previous contract
     * returned a single chunk from parseEvent, which dropped all but the
     * last tool call in the array. With the fix, every tool call in the
     * array is emitted as its own chunk so Brain.fromProvider sees every
     * start.
     */
    @Test
    fun `multiple tool calls in a single SSE event are all emitted`() = runBlocking {
        val sseData = listOf(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_A","type":"function","function":{"name":"search","arguments":""}},{"index":1,"id":"call_B","type":"function","function":{"name":"fetch","arguments":""}}]}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
            "[DONE]",
        )
        val sseBody = sseData.joinToString("") { "data: $it\n\n" }

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody),
        )

        val chunks = withTimeout(10_000L) {
            provider.chat("test-model", listOf(
                ProviderMessage(role = ProviderMessage.Role.user, content = "go"),
            ), ChatOptions(), emptyList()).toList()
        }

        val toolChunks = chunks.filter { it.toolCall != null }
        assertEquals(2, toolChunks.size, "Both tools must be emitted from a single event")

        val ids = toolChunks.mapNotNull { it.toolCall?.id }.toSet()
        assertTrue("call_A" in ids, "call_A missing from emitted chunks")
        assertTrue("call_B" in ids, "call_B missing from emitted chunks")

        val names = toolChunks.mapNotNull { it.toolCall?.name }.toSet()
        assertTrue("search" in names, "search missing from emitted chunks")
        assertTrue("fetch" in names, "fetch missing from emitted chunks")
    }
}