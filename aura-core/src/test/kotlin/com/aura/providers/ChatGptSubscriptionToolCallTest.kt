package com.aura.providers

import io.mockk.coEvery
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
 * Regression test for ChatGPT subscription provider SSE tool-call parsing.
 *
 * The ChatGPT Responses API uses a different SSE format than the standard
 * OpenAI Chat Completions API. Tool calls arrive as `function_call` events
 * or as `tool_calls` in the delta. This test verifies both paths.
 */
class ChatGptSubscriptionToolCallTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: ChatGptSubscriptionProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val keys = mockk<ProviderKeys> {
            coEvery { keyForAwaiting("chatgpt") } returns "test-session-token"
            every { keyFor("chatgpt") } returns "test-session-token"
            every { isConfigured("chatgpt") } returns true
        }
        provider = ChatGptSubscriptionProvider(
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
    fun `function_call event emits ToolCall with name and arguments`() = runBlocking {
        val sseData = listOf(
            """{"type":"response.output_text.delta","delta":{"text":"Let me search for that."}}""",
            """{"type":"response.function_call","delta":{"tool_call":{"id":"call_1","name":"web_search","arguments":"{\"q\":\"test\"}"}}}""",
            """{"type":"response.completed"}""",
        )
        val sseBody = sseData.joinToString("") { "data: $it\n\n" }

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody),
        )

        val chunks = withTimeout(10_000L) {
            provider.chat("gpt-4o", listOf(
                ProviderMessage(role = ProviderMessage.Role.user, content = "search for test"),
            ), ChatOptions(), emptyList()).toList()
        }

        val toolChunks = chunks.filter { it.toolCall != null }
        assertTrue(toolChunks.isNotEmpty(), "Should have at least 1 tool-call chunk")
        val tc = toolChunks[0].toolCall!!
        assertEquals("web_search", tc.name)
        assertTrue(tc.arguments.contains("test"))
    }

    @Test
    fun `listModels queries the backend instead of returning a hardcoded list`() = runBlocking {
        // This previously asserted the opposite — that listModels returned a
        // baked-in list with no network call, and specifically that it
        // contained gpt-4o, o3 and gpt-4.1. The backend offers none of those
        // to a subscription; the live catalog is gpt-5.6-sol, gpt-5.6-terra,
        // gpt-5.6-luna, gpt-5.5, gpt-5.4 and gpt-5.4-mini. Asserting the
        // stale ids is part of what kept the bug invisible.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"models":[{"slug":"gpt-5.6-sol","context_window":272000,"visibility":"list"}]}""",
            ),
        )

        val models = provider.listModels()

        assertEquals(listOf("gpt-5.6-sol"), models)
        assertTrue(
            models.none { it in setOf("gpt-4o", "o3", "gpt-4.1") },
            "stale hardcoded ids must not reappear",
        )
    }

    @Test
    fun `text delta events emit Text chunks`() = runBlocking {
        val sseData = listOf(
            """{"delta":{"text":"Hello "}}""",
            """{"delta":{"text":"world!"}}""",
            """{"type":"response.completed"}""",
        )
        val sseBody = sseData.joinToString("") { "data: $it\n\n" }

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody),
        )

        val chunks = withTimeout(10_000L) {
            provider.chat("gpt-4o", listOf(
                ProviderMessage(role = ProviderMessage.Role.user, content = "hi"),
            ), ChatOptions(), emptyList()).toList()
        }

        val textChunks = chunks.filter { it.text != null }
        assertTrue(textChunks.size >= 2, "Should have at least 2 text chunks")
        assertEquals("Hello ", textChunks[0].text)
        assertEquals("world!", textChunks[1].text)
    }
}