package com.aura.providers

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Stream parsing, against the events this API actually emits.
 *
 * These tests used to feed the provider Chat-Completions-shaped payloads —
 * `delta.tool_calls[]` with an `index`, `delta.text` — invented rather than
 * observed. They passed for as long as they existed while no real reply ever
 * rendered, because the backend sends nothing of the sort. Every payload below
 * was copied off the live stream.
 */
class ChatGptSubscriptionParallelToolCallTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: ChatGptSubscriptionProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = ChatGptSubscriptionProvider(
            providerKeys = mockk { coEvery { keyForAwaiting("chatgpt") } returns "token" },
            httpClient = OkHttpClient.Builder().build(),
            tokenStore = chatGptTokenStore("token"),
            oauthFlow = chatGptOAuthFlow(),
            baseUrl = server.url("/").toString().removeSuffix("/"),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun stream(vararg events: String, contentType: String? = "text/event-stream") {
        val response = MockResponse().setBody(events.joinToString("") { "data: $it\n\n" })
        if (contentType != null) response.setHeader("Content-Type", contentType)
        server.enqueue(response)
    }

    private fun collect(): List<ProviderChunk> = runBlocking {
        provider.chat(
            model = "gpt-5.6-sol",
            messages = listOf(ProviderMessage(role = ProviderMessage.Role.user, content = "go")),
            options = ChatOptions(),
            tools = emptyList(),
        ).toList()
    }

    @Test
    fun `text deltas are bare strings, not objects`() {
        // `delta` is the string itself. Reading it as `delta.text` — which is
        // what this did — yields null for every chunk, so the reply rendered
        // as nothing at all while the request itself was a clean 200.
        stream(
            """{"type":"response.output_text.delta","delta":"P"}""",
            """{"type":"response.output_text.delta","delta":"ONG"}""",
            """{"type":"response.completed"}""",
        )

        val text = collect().mapNotNull { it.text }

        assertEquals(listOf("P", "ONG"), text)
    }

    @Test
    fun `a missing Content-Type header does not turn a good stream into an error`() {
        // The real backend sends no Content-Type. OkHttp's EventSource
        // requires one and otherwise calls onFailure with the 200 response
        // attached, which dumped the whole raw stream into the chat as an
        // error message.
        stream(
            """{"type":"response.output_text.delta","delta":"PONG"}""",
            """{"type":"response.completed"}""",
            contentType = null,
        )

        val chunks = collect()

        assertTrue(chunks.none { it.error != null }, "errors: ${chunks.mapNotNull { it.error }}")
        assertEquals("PONG", chunks.mapNotNull { it.text }.joinToString(""))
    }

    @Test
    fun `a completed tool call arrives whole in output_item done`() {
        stream(
            """{"type":"response.output_item.added","item":{"type":"function_call","status":"in_progress","arguments":"","call_id":"call_A","name":"get_weather"}}""",
            """{"type":"response.function_call_arguments.delta","delta":"{\"city\""}""",
            """{"type":"response.function_call_arguments.delta","delta":":\"Baku\"}"}""",
            """{"type":"response.output_item.done","item":{"type":"function_call","status":"completed","arguments":"{\"city\":\"Baku\"}","call_id":"call_A","name":"get_weather"}}""",
            """{"type":"response.completed"}""",
        )

        val calls = collect().mapNotNull { it.toolCall }

        // Exactly one — the argument deltas must not also produce a call, or
        // the agent loop would run the tool twice.
        assertEquals(1, calls.size, "got $calls")
        assertEquals("call_A", calls[0].id)
        assertEquals("get_weather", calls[0].name)
        assertEquals("""{"city":"Baku"}""", calls[0].arguments)
    }

    @Test
    fun `parallel tool calls each emit their own call`() {
        stream(
            """{"type":"response.output_item.done","item":{"type":"function_call","arguments":"{\"q\":1}","call_id":"call_A","name":"search"}}""",
            """{"type":"response.output_item.done","item":{"type":"function_call","arguments":"{\"x\":2}","call_id":"call_B","name":"calc"}}""",
            """{"type":"response.completed"}""",
        )

        val calls = collect().mapNotNull { it.toolCall }

        assertEquals(setOf("call_A", "call_B"), calls.map { it.id }.toSet())
        assertEquals("search", calls.first { it.id == "call_A" }.name)
        assertEquals("calc", calls.first { it.id == "call_B" }.name)
    }

    @Test
    fun `a response containing a tool call does not report itself finished`() {
        stream(
            """{"type":"response.output_text.delta","delta":"I'll check the docs."}""",
            """{"type":"response.output_item.done","item":{"type":"function_call","arguments":"{}","call_id":"call_A","name":"web_search"}}""",
            """{"type":"response.completed"}""",
        )

        val finish = collect().mapNotNull { it.finishReason }

        // MemoryAugmentedAgenticLoop ends the turn on "stop". Reporting stop
        // here is why the model announced "I'll check the docs" and then
        // nothing happened — and why the orphaned call bricked the chat.
        assertEquals(listOf(FinishReason.tool_calls), finish)
    }

    @Test
    fun `a plain response still reports stop`() {
        stream(
            """{"type":"response.output_text.delta","delta":"done"}""",
            """{"type":"response.completed"}""",
        )

        assertEquals(listOf(FinishReason.stop), collect().mapNotNull { it.finishReason })
    }

    @Test
    fun `message output items are not mistaken for tool calls`() {
        stream(
            """{"type":"response.output_item.done","item":{"type":"message","role":"assistant","content":[]}}""",
            """{"type":"response.completed"}""",
        )

        assertTrue(collect().none { it.toolCall != null })
    }

    @Test
    fun `a failed response surfaces instead of looking like a short reply`() {
        stream(
            """{"type":"response.output_text.delta","delta":"partial"}""",
            """{"type":"response.failed","response":{"error":{"message":"model overloaded"}}}""",
        )

        val error = collect().mapNotNull { it.error }.firstOrNull()

        assertTrue(error != null, "a failed response must not read as a completed one")
        assertTrue("overloaded" in error.message, "should carry the server's reason, got ${error.message}")
    }
}
