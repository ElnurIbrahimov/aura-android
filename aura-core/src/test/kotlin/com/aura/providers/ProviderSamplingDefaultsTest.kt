package com.aura.providers

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
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

/**
 * Wire-level regression tests for the ChatOptions null-means-unset
 * change: unset (null) temperature/topP must serialize as the historical
 * defaults (0.7 / 1.0) so every existing caller stays byte-identical on
 * the wire, while explicit values pass through verbatim.
 */
class ProviderSamplingDefaultsTest {

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

    private fun keys(prefix: String): ProviderKeys = mockk {
        coEvery { keyForAwaiting(prefix) } returns "test-key"
        every { isConfigured(prefix) } returns true
    }

    private fun openAiProvider(): OpenAiCompatProvider = OpenAiCompatProvider(
        prefix = "test",
        displayName = "Test",
        baseUrl = server.url("/").toString().removeSuffix("/"),
        providerKeys = keys("test"),
        httpClient = OkHttpClient(),
    )

    private fun enqueueSse() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"),
        )
    }

    private fun takeRequestBody(): JsonObject {
        val recorded = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
        assertNotNull(recorded, "provider never sent a request")
        return Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
    }

    private val messages = listOf(ProviderMessage(ProviderMessage.Role.user, "hi"))

    @Test
    fun `unset sampling serializes as the historical defaults`() = runBlocking {
        enqueueSse()
        withTimeout(10_000L) {
            openAiProvider().chat("test-model", messages, ChatOptions(), emptyList()).toList()
        }
        val body = takeRequestBody()
        assertEquals(ChatOptions.DEFAULT_TEMPERATURE, body["temperature"]!!.jsonPrimitive.double)
        assertEquals(ChatOptions.DEFAULT_TOP_P, body["top_p"]!!.jsonPrimitive.double)
    }

    @Test
    fun `explicit sampling passes through verbatim`() = runBlocking {
        enqueueSse()
        withTimeout(10_000L) {
            openAiProvider()
                .chat("test-model", messages, ChatOptions(temperature = 0.1, topP = 0.85), emptyList())
                .toList()
        }
        val body = takeRequestBody()
        assertEquals(0.1, body["temperature"]!!.jsonPrimitive.double)
        assertEquals(0.85, body["top_p"]!!.jsonPrimitive.double)
    }

    @Test
    fun `anthropic unset temperature serializes as default`() = runBlocking {
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
            provider.chat("claude-test", messages, ChatOptions(), emptyList()).toList()
        }
        val body = takeRequestBody()
        assertEquals(ChatOptions.DEFAULT_TEMPERATURE, body["temperature"]!!.jsonPrimitive.double)
    }

    @Test
    fun `anthropic thinking mode still forces temperature 1`() = runBlocking {
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
            provider
                .chat("claude-test", messages, ChatOptions(temperature = 0.3, thinkingBudget = 32_000), emptyList())
                .toList()
        }
        val body = takeRequestBody()
        // Anthropic requires temperature=1 when extended thinking is on —
        // the override must survive the nullable-temperature change.
        assertEquals(1.0, body["temperature"]!!.jsonPrimitive.double)
    }
}
