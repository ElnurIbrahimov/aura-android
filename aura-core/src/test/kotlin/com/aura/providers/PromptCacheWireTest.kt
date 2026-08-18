package com.aura.providers

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.aura.testing.networkTestTimeout
import org.junit.Rule
import org.junit.rules.Timeout

/**
 * Prompt-cache markers on the wire.
 *
 * The `stableSystemPrefix = 0` cases matter as much as the others: caching ships
 * behind a switch, and turning it off has to restore byte-for-byte the request
 * shape that existed before any of this. A test that only checks the marker is
 * present cannot tell you that.
 */
class PromptCacheWireTest {

    /** See [networkTestTimeout] — uniform, not judged per class. */
    @get:Rule
    val globalTimeout: Timeout = networkTestTimeout()

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

    private fun takeRequestBody(): JsonObject {
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recorded, "provider never sent a request")
        return Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
    }

    private val twoSystemMessages = listOf(
        ProviderMessage(ProviderMessage.Role.system, "STABLE identity block"),
        ProviderMessage(ProviderMessage.Role.system, "VOLATILE retrieved context"),
        ProviderMessage(ProviderMessage.Role.user, "hey"),
    )

    private fun tool(name: String) = ToolDefinition(
        name = name,
        description = "Tool $name",
        parameters = ToolParameters(
            properties = mapOf("q" to ToolProperty(type = "string", description = "q")),
        ),
    )

    // ---- Anthropic: explicit breakpoints ---------------------------------

    private fun anthropicBody(
        stablePrefix: Int,
        tools: List<ToolDefinition> = emptyList(),
    ): JsonObject {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"message_stop\"}\n\n"),
        )
        val provider = AnthropicProvider(
            providerKeys = keys("anthropic"),
            httpClient = OkHttpClient(),
            baseUrl = server.url("/").toString().removeSuffix("/"),
        )
        runBlocking {
            withTimeout(10_000L) {
                provider.chat(
                    "claude-sonnet-4.6",
                    twoSystemMessages,
                    ChatOptions(stableSystemPrefix = stablePrefix),
                    tools,
                ).toList()
            }
        }
        return takeRequestBody()
    }

    @Test
    fun `anthropic sends a plain system string when caching is off`() {
        val system = anthropicBody(stablePrefix = 0)["system"]!!
        assertTrue(system is JsonPrimitive, "system must stay a plain string with caching off")
        assertEquals("STABLE identity block\n\nVOLATILE retrieved context", system.jsonPrimitive.content)
    }

    @Test
    fun `anthropic marks the first system block when caching is on`() {
        val system = anthropicBody(stablePrefix = 1)
        val blocks = system["system"]!!.jsonArray
        assertEquals(2, blocks.size, "each system message must survive as its own block")

        val first = blocks[0].jsonObject
        assertEquals("text", first["type"]!!.jsonPrimitive.content)
        assertEquals("STABLE identity block", first["text"]!!.jsonPrimitive.content)
        assertEquals(
            "ephemeral",
            first["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )

        // The volatile block must NOT carry one — a breakpoint there would
        // cache content that changes every step, paying the 1.25x write premium
        // for something never read back.
        assertFalse("cache_control" in blocks[1].jsonObject.keys)
    }

    @Test
    fun `anthropic marks the last tool when caching is on`() {
        // The tools array precedes `system` in the request, so without a
        // breakpoint here the largest fixed part of the prompt is re-billed in
        // full on every step.
        val tools = anthropicBody(stablePrefix = 1, tools = listOf(tool("a"), tool("b"), tool("c")))["tools"]!!.jsonArray

        assertEquals(3, tools.size)
        assertFalse("cache_control" in tools[0].jsonObject.keys)
        assertFalse("cache_control" in tools[1].jsonObject.keys)
        assertEquals(
            "ephemeral",
            tools[2].jsonObject["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `anthropic marks no tool when caching is off`() {
        val tools = anthropicBody(stablePrefix = 0, tools = listOf(tool("a"), tool("b")))["tools"]!!.jsonArray
        tools.forEach { assertFalse("cache_control" in it.jsonObject.keys) }
    }

    @Test
    fun `anthropic falls back to a plain string when the prefix exceeds the blocks`() {
        // Defensive: a caller asking to cache more system messages than exist
        // must not produce an out-of-range marker or a malformed body.
        val system = anthropicBody(stablePrefix = 5)["system"]!!
        assertTrue(system is JsonPrimitive, "an out-of-range prefix must degrade to the plain form")
    }

    // ---- Gemini: implicit caching, structure only ------------------------

    private fun geminiBody(stablePrefix: Int): JsonObject {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}]}""" + "\n",
                ),
        )
        val provider = GeminiProvider(
            providerKeys = keys("gemini"),
            httpClient = OkHttpClient(),
            baseUrl = server.url("/").toString().removeSuffix("/"),
        )
        runBlocking {
            withTimeout(10_000L) {
                provider.chat(
                    "gemini-2.5-flash",
                    twoSystemMessages,
                    ChatOptions(stableSystemPrefix = stablePrefix),
                ).toList()
            }
        }
        return takeRequestBody()
    }

    @Test
    fun `gemini joins system messages into one part when caching is off`() {
        val parts = geminiBody(stablePrefix = 0)["system_instruction"]!!.jsonObject["parts"]!!.jsonArray
        assertEquals(1, parts.size)
        assertEquals(
            "STABLE identity block\n\nVOLATILE retrieved context",
            parts[0].jsonObject["text"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `gemini keeps one part per system message when caching is on`() {
        val parts = geminiBody(stablePrefix = 1)["system_instruction"]!!.jsonObject["parts"]!!.jsonArray
        assertEquals(2, parts.size)
        assertEquals("STABLE identity block", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("VOLATILE retrieved context", parts[1].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `gemini never sends an explicit cachedContent handle`() {
        // Explicit caching creates server-side BILLABLE resources with TTLs the
        // client must delete. On a phone those leak on process death with no
        // cleanup path and no local record of what to remove.
        val body = geminiBody(stablePrefix = 1)
        assertFalse("cachedContent" in body.keys)
        assertFalse("cached_content" in body.keys)
    }

    // ---- OpenAI-compatible: automatic, nothing on the wire ---------------

    @Test
    fun `openai sends no cache marker and keeps system messages separate`() {
        // OpenAI caches a stable prefix automatically. System messages are
        // already separate array entries, so the split needs no wire change —
        // and inventing a key here would 400 on a strict endpoint.
        server.enqueue(
            MockResponse().setResponseCode(200)
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
        runBlocking {
            withTimeout(10_000L) {
                provider.chat(
                    "m",
                    twoSystemMessages,
                    ChatOptions(stableSystemPrefix = 1),
                ).toList()
            }
        }
        val body = takeRequestBody()
        assertFalse("cache_control" in body.toString(), "no cache key belongs on an OpenAI body")
        assertFalse("stableSystemPrefix" in body.toString(), "the option must never leak onto the wire")

        val systemMessages = body["messages"]!!.jsonArray
            .map { it.jsonObject }
            .filter { it["role"]!!.jsonPrimitive.content == "system" }
        assertEquals(2, systemMessages.size, "system messages must stay separate entries")
        assertEquals("STABLE identity block", systemMessages[0]["content"]!!.jsonPrimitive.content)
    }
}
