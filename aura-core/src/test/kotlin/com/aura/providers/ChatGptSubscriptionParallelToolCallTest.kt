package com.aura.providers

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P0 regression: ChatGPT subscription provider routes parallel tool-call
 * deltas by their `index` field rather than mis-routing everything to the
 * last-started call.
 */
class ChatGptSubscriptionParallelToolCallTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: ChatGptSubscriptionProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val keys = mockk<ProviderKeys> {
            coEvery { keyForAwaiting("chatgpt") } returns "token"
        }
        provider = ChatGptSubscriptionProvider(
            providerKeys = keys,
            httpClient = OkHttpClient.Builder().build(),
            baseUrl = server.url("/").toString().removeSuffix("/"),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `parallel tool call deltas are routed to separate ToolCall ids`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    buildString {
                        append("data: {\"type\":\"response.created\"}\n\n")
                        append("data: {\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_A\",\"function\":{\"name\":\"search\"}},{\"index\":1,\"id\":\"call_B\",\"function\":{\"name\":\"calc\"}}]}}\n\n")
                        append("data: {\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{q\"}},{\"index\":1,\"function\":{\"arguments\":\"{x\"}}]}}\n\n")
                        append("data: {\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\":1}\"}},{\"index\":1,\"function\":{\"arguments\":\":2}\"}}]}}\n\n")
                        append("data: {\"type\":\"response.completed\"}\n\n")
                    }
                )
        )

        val chunks = provider.chat(
            model = "gpt-5",
            messages = listOf(ProviderMessage(role = ProviderMessage.Role.user, content = "do two things")),
            options = ChatOptions(),
            tools = emptyList(),
        ).toList()

        val toolCalls = chunks.mapNotNull { it.toolCall }
        assertEquals(2, toolCalls.size, "two complete tool calls should be emitted")
        val ids = toolCalls.map { it.id }.toSet()
        assertTrue("call_A" in ids, "first tool call id must be call_A")
        assertTrue("call_B" in ids, "second tool call id must be call_B")
        assertEquals("search", toolCalls.first { it.id == "call_A" }.name)
        assertEquals("calc", toolCalls.first { it.id == "call_B" }.name)
    }
}
