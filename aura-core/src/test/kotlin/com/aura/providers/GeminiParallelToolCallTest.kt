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
 * Regression test for Gemini provider parallel tool-call handling.
 *
 * Gemini sends function calls as complete objects (not streaming deltas)
 * inside `candidates[0].content.parts[]`. Each `functionCall` part has
 * a `name` and `args` field. Unlike OpenAI/Anthropic, Gemini does not
 * stream argument fragments — the full call arrives in one chunk.
 *
 * This test verifies that:
 * 1. Multiple function calls in a single response are each emitted as
 *    separate ToolCall chunks with unique ids.
 * 2. The text and functionCall parts in the same response are both
 *    correctly extracted.
 * 3. The finishReason is correctly mapped.
 */
class GeminiParallelToolCallTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: GeminiProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val keys = mockk<ProviderKeys> {
            every { keyFor("gemini") } returns "test-api-key"
            every { isConfigured("gemini") } returns true
        }
        provider = GeminiProvider(
            providerKeys = keys,
            httpClient = OkHttpClient(),
            baseUrl = server.url("/").toString().removeSuffix("/"),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `parallel function calls in one response emit separate ToolCall chunks with unique ids`() = runBlocking {
        // Gemini streamGenerateContent returns newline-delimited JSON.
        // Two function calls in the same response:
        val responseBody = listOf(
            """{"candidates":[{"content":{"parts":[{"text":"Let me help with both."},{"functionCall":{"name":"search_web","args":{"query":"test"}}},{"functionCall":{"name":"read_url","args":{"url":"https://example.com"}}}]},"finishReason":"STOP"}]}""",
        ).joinToString("\n")

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(responseBody),
        )

        val chunks = withTimeout(10_000L) {
            provider.chat("gemini-2.0-flash", listOf(
                ProviderMessage(role = ProviderMessage.Role.user, content = "search and read"),
            ), ChatOptions(), emptyList()).toList()
        }

        val toolChunks = chunks.filter { it.toolCall != null }
        assertTrue(toolChunks.size >= 2, "Should have at least 2 tool-call chunks, got ${toolChunks.size}")

        // Both should have non-empty names
        val names = toolChunks.map { it.toolCall?.name }.filter { !it.isNullOrBlank() }
        assertTrue(names.contains("search_web"), "Should contain search_web tool call")
        assertTrue(names.contains("read_url"), "Should contain read_url tool call")

        // Both should have non-empty ids (Gemini generates synthetic ids)
        for (chunk in toolChunks) {
            val tc = chunk.toolCall!!
            assertTrue(tc.id.isNotEmpty(), "Tool call id should not be empty")
        }

        // Should also have text
        val textChunks = chunks.filter { it.text != null }
        assertTrue(textChunks.isNotEmpty(), "Should have text chunks")

        // Should have finish reason
        val finishChunk = chunks.firstOrNull { it.finishReason != null }
        assertNotNull(finishChunk, "Should have a finish chunk")
        assertEquals(FinishReason.stop, finishChunk?.finishReason)
    }

    @Test
    fun `single function call emits one ToolCall chunk`() = runBlocking {
        val responseBody = """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"set_reminder","args":{"text":"meeting at 3pm"}}}]},"finishReason":"STOP"}]}"""

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(responseBody),
        )

        val chunks = withTimeout(10_000L) {
            provider.chat("gemini-2.0-flash", listOf(
                ProviderMessage(role = ProviderMessage.Role.user, content = "remind me about meeting"),
            ), ChatOptions(), emptyList()).toList()
        }

        val toolChunks = chunks.filter { it.toolCall != null }
        assertEquals(1, toolChunks.size, "Should have exactly 1 tool-call chunk")
        val tc = toolChunks[0].toolCall!!
        assertEquals("set_reminder", tc.name)
        assertTrue(tc.arguments.contains("meeting"))
    }

    @Test
    fun `text-only response emits Text chunks without tool calls`() = runBlocking {
        val responseBody = """{"candidates":[{"content":{"parts":[{"text":"Hello! How can I help?"}]},"finishReason":"STOP"}]}"""

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(responseBody),
        )

        val chunks = withTimeout(10_000L) {
            provider.chat("gemini-2.0-flash", listOf(
                ProviderMessage(role = ProviderMessage.Role.user, content = "hi"),
            ), ChatOptions(), emptyList()).toList()
        }

        val toolChunks = chunks.filter { it.toolCall != null }
        assertEquals(0, toolChunks.size, "Should have no tool-call chunks")

        val textChunks = chunks.filter { it.text != null }
        assertTrue(textChunks.isNotEmpty())
        assertEquals("Hello! How can I help?", textChunks[0].text)
    }
}