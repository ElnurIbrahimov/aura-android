package com.aura.providers

import com.aura.agent.Conversation
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Request-body regression tests for tool-history serialization — the
 * "second round" of a tool-using conversation. Pre-fix, every provider
 * serialized only `role` + `content`, so the assistant `tool_calls` echo
 * and the result linkage (`tool_call_id` / `tool_result` / a
 * `functionResponse`) were dropped and strict APIs rejected the request.
 *
 * Each test builds the message list through the REAL
 * [Conversation.toMessages] so the whole seam is covered, then asserts on
 * the JSON body the provider actually put on the wire.
 */
class ProviderToolHistorySerializationTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** A conversation whose first turn completed one weather tool call. */
    private fun toolHistoryMessages(): List<ProviderMessage> = Conversation()
        .addUser("what's the weather in Baku?")
        .addAssistant("Let me check.")
        .addToolCall("call_1", "weather", """{"city":"Baku"}""")
        .setToolResult("call_1", "Sunny, 34C")
        .toMessages(includeSystemPrompt = false)

    private fun keys(prefix: String): ProviderKeys = mockk {
        coEvery { keyForAwaiting(prefix) } returns "test-key"
        every { isConfigured(prefix) } returns true
    }

    private fun takeRequestBody(): JsonObject {
        val recorded = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
        assertNotNull(recorded, "provider never sent a request")
        return Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
    }

    // ── OpenAI-compatible ──

    @Test
    fun `openai compat echoes tool_calls and tool_call_id`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"),
        )
        val provider = OpenAiCompatProvider(
            prefix = "test",
            displayName = "Test",
            baseUrl = server.url("/").toString().removeSuffix("/"),
            providerKeys = keys("test"),
            httpClient = OkHttpClient(),
        )
        withTimeout(10_000L) {
            provider.chat("test-model", toolHistoryMessages(), ChatOptions(), emptyList()).toList()
        }

        val messages = takeRequestBody()["messages"]!!.jsonArray.map { it.jsonObject }
        val assistant = messages.first { it["role"]!!.jsonPrimitive.content == "assistant" }
        val toolCalls = assistant["tool_calls"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, toolCalls.size)
        assertEquals("call_1", toolCalls[0]["id"]!!.jsonPrimitive.content)
        assertEquals("function", toolCalls[0]["type"]!!.jsonPrimitive.content)
        val fn = toolCalls[0]["function"]!!.jsonObject
        assertEquals("weather", fn["name"]!!.jsonPrimitive.content)
        assertEquals("""{"city":"Baku"}""", fn["arguments"]!!.jsonPrimitive.content)

        val tool = messages.first { it["role"]!!.jsonPrimitive.content == "tool" }
        assertEquals("call_1", tool["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("Sunny, 34C", tool["content"]!!.jsonPrimitive.content)
    }

    // ── Anthropic ──

    @Test
    fun `anthropic sends tool_use and tool_result blocks with no tool role`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"message_stop\"}\n\n"),
        )
        val provider = AnthropicProvider(
            providerKeys = keys("anthropic"),
            httpClient = OkHttpClient(),
            baseUrl = server.url("/").toString().removeSuffix("/"),
        )
        withTimeout(10_000L) {
            provider.chat("claude-test", toolHistoryMessages(), ChatOptions(), emptyList()).toList()
        }

        val messages = takeRequestBody()["messages"]!!.jsonArray.map { it.jsonObject }
        val roles = messages.map { it["role"]!!.jsonPrimitive.content }
        assertTrue("tool" !in roles, "Anthropic accepts only user/assistant roles, got $roles")
        for (i in 1 until roles.size) {
            assertTrue(roles[i] != roles[i - 1], "roles must alternate, got $roles")
        }

        val assistant = messages.first { it["role"]!!.jsonPrimitive.content == "assistant" }
        val blocks = assistant["content"]!!.jsonArray.map { it.jsonObject }
        val toolUse = blocks.first { it["type"]!!.jsonPrimitive.content == "tool_use" }
        assertEquals("call_1", toolUse["id"]!!.jsonPrimitive.content)
        assertEquals("weather", toolUse["name"]!!.jsonPrimitive.content)
        assertEquals("Baku", toolUse["input"]!!.jsonObject["city"]!!.jsonPrimitive.content)

        val resultMsg = messages.last { it["role"]!!.jsonPrimitive.content == "user" }
        val resultBlock = resultMsg["content"]!!.jsonArray.map { it.jsonObject }
            .first { it["type"]!!.jsonPrimitive.content == "tool_result" }
        assertEquals("call_1", resultBlock["tool_use_id"]!!.jsonPrimitive.content)
        assertEquals("Sunny, 34C", resultBlock["content"]!!.jsonPrimitive.content)
    }

    // ── Gemini ──

    @Test
    fun `gemini sends functionCall and functionResponse parts`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}]}\n\n"),
        )
        val provider = GeminiProvider(
            providerKeys = keys("gemini"),
            httpClient = OkHttpClient(),
            baseUrl = server.url("/").toString().removeSuffix("/"),
        )
        withTimeout(10_000L) {
            provider.chat("gemini-test", toolHistoryMessages(), ChatOptions(), emptyList()).toList()
        }

        val contents = takeRequestBody()["contents"]!!.jsonArray.map { it.jsonObject }
        val model = contents.first { it["role"]!!.jsonPrimitive.content == "model" }
        val fnCall = model["parts"]!!.jsonArray.map { it.jsonObject }
            .firstNotNullOf { it["functionCall"]?.jsonObject }
        assertEquals("weather", fnCall["name"]!!.jsonPrimitive.content)
        assertEquals("Baku", fnCall["args"]!!.jsonObject["city"]!!.jsonPrimitive.content)

        val fnResponse = contents.flatMap { it["parts"]!!.jsonArray }
            .mapNotNull { it.jsonObject["functionResponse"]?.jsonObject }
            .first()
        assertEquals("weather", fnResponse["name"]!!.jsonPrimitive.content)
        assertEquals(
            "Sunny, 34C",
            fnResponse["response"]!!.jsonObject["result"]!!.jsonPrimitive.content,
        )
    }

    // ── ChatGPT subscription (Responses API) ──

    @Test
    fun `chatgpt sends function_call and function_call_output items`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"response.completed\"}\n\n"),
        )
        val provider = ChatGptSubscriptionProvider(
            providerKeys = keys("chatgpt"),
            httpClient = OkHttpClient(),
            tokenStore = chatGptTokenStore("token"),
            oauthFlow = chatGptOAuthFlow(),
            baseUrl = server.url("/").toString().removeSuffix("/"),
        )
        withTimeout(10_000L) {
            provider.chat("gpt-test", toolHistoryMessages(), ChatOptions(), emptyList()).toList()
        }

        val input = takeRequestBody()["input"]!!.jsonArray.map { it.jsonObject }
        assertTrue(
            input.none { it["role"]?.jsonPrimitive?.content == "tool" },
            "Responses API has no tool role",
        )
        val fnCall = input.first { it["type"]?.jsonPrimitive?.content == "function_call" }
        assertEquals("call_1", fnCall["call_id"]!!.jsonPrimitive.content)
        assertEquals("weather", fnCall["name"]!!.jsonPrimitive.content)
        val fnOutput = input.first { it["type"]?.jsonPrimitive?.content == "function_call_output" }
        assertEquals("call_1", fnOutput["call_id"]!!.jsonPrimitive.content)
        assertEquals("Sunny, 34C", fnOutput["output"]!!.jsonPrimitive.content)
    }
}
