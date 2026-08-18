package com.aura.providers

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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.aura.testing.networkTestTimeout
import org.junit.Rule
import org.junit.rules.Timeout

/**
 * Anthropic's extended-thinking replay contract.
 *
 * Thinking is on by default at 32k. With it on, the API requires the assistant
 * turn that issued a `tool_use` to be re-sent WITH the signed thinking block
 * that preceded it, in first position — otherwise step 2 of every tool call
 * answers 400 "Expected `thinking` or `redacted_thinking`, but found
 * `tool_use`". Nothing parsed the signature and nothing carried the block, so
 * every tool-using turn on Claude died one step in.
 *
 * These drive the provider directly against a MockWebServer, which is the only
 * place the shape of the outgoing body can be checked.
 */
class AnthropicThinkingReplayTest {

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

    private fun provider(): AnthropicProvider = AnthropicProvider(
        providerKeys = mockk {
            coEvery { keyForAwaiting("anthropic") } returns "test-key"
            every { isConfigured("anthropic") } returns true
        },
        httpClient = OkHttpClient(),
        baseUrl = server.url("/").toString().removeSuffix("/"),
    )

    private fun enqueueSse(body: String) {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body),
        )
    }

    private fun takeBody(): JsonObject =
        Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    private fun assistantBlocks(): List<JsonObject> =
        takeBody()["messages"]!!.jsonArray.map { it.jsonObject }
            .first { it["role"]!!.jsonPrimitive.content == "assistant" }["content"]!!
            .jsonArray.map { it.jsonObject }

    private val sampleTool = ToolDefinition(
        name = "web_search",
        description = "search the web",
        parameters = ToolParameters(properties = mapOf("q" to ToolProperty(type = "string"))),
    )

    private val thinkingOn = ChatOptions(thinkingBudget = 4_096, maxTokens = 8_192)

    @Test
    fun `signature_delta reaches the caller`() = runBlocking<Unit> {
        enqueueSse(
            "data: {\"type\":\"content_block_delta\",\"index\":0," +
                "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"weighing it up\"}}\n\n" +
                "data: {\"type\":\"content_block_delta\",\"index\":0," +
                "\"delta\":{\"type\":\"signature_delta\",\"signature\":\"EqQBsig\"}}\n\n" +
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\n",
        )
        val chunks = withTimeout(10_000L) {
            provider().chat(
                "claude-test",
                listOf(ProviderMessage(ProviderMessage.Role.user, "hi")),
                thinkingOn,
                emptyList(),
            ).toList()
        }
        assertEquals("weighing it up", chunks.mapNotNull { it.thinking }.joinToString(""))
        assertEquals(
            "EqQBsig",
            chunks.firstNotNullOfOrNull { it.thinkingSignature },
            "without the signature the block cannot be replayed and the next step 400s",
        )
    }

    @Test
    fun `a signed thinking block leads the assistant turn that issued a tool_use`() = runBlocking<Unit> {
        enqueueSse("data: {\"type\":\"message_stop\"}\n\n")
        val history = listOf(
            ProviderMessage(ProviderMessage.Role.user, "search for kotlin"),
            ProviderMessage(
                role = ProviderMessage.Role.assistant,
                content = "",
                toolCalls = listOf(ToolCall("toolu_1", "web_search", """{"q":"kotlin"}""")),
                thinking = "weighing it up",
                thinkingSignature = "EqQBsig",
            ),
            ProviderMessage(
                role = ProviderMessage.Role.tool,
                content = "results",
                name = "web_search",
                toolCallId = "toolu_1",
            ),
        )
        withTimeout(10_000L) {
            provider().chat("claude-test", history, thinkingOn, listOf(sampleTool)).toList()
        }

        val blocks = assistantBlocks()
        assertEquals(
            "thinking",
            blocks.first()["type"]!!.jsonPrimitive.content,
            "the thinking block must come first; anything else is the 400 this fixes",
        )
        assertEquals("weighing it up", blocks.first()["thinking"]!!.jsonPrimitive.content)
        assertEquals("EqQBsig", blocks.first()["signature"]!!.jsonPrimitive.content)
        assertEquals("tool_use", blocks[1]["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an unsigned trace is dropped rather than guessed at`() = runBlocking<Unit> {
        // The shape a conversation takes when the reasoning came from another
        // provider, or from before signatures were captured. Anthropic rejects a
        // thinking block whose signature it did not issue, so sending one would
        // trade a lost trace for a lost turn.
        enqueueSse("data: {\"type\":\"message_stop\"}\n\n")
        withTimeout(10_000L) {
            provider().chat(
                "claude-test",
                listOf(
                    ProviderMessage(ProviderMessage.Role.user, "hi"),
                    ProviderMessage(
                        role = ProviderMessage.Role.assistant,
                        content = "done",
                        thinking = "reasoning from somewhere else",
                    ),
                ),
                thinkingOn,
                emptyList(),
            ).toList()
        }
        assertTrue(assistantBlocks().none { it["type"]!!.jsonPrimitive.content == "thinking" })
    }

    @Test
    fun `no thinking block is sent when thinking is off for this request`() = runBlocking<Unit> {
        // The non-thinking wire bytes must be exactly what shipped before.
        enqueueSse("data: {\"type\":\"message_stop\"}\n\n")
        withTimeout(10_000L) {
            provider().chat(
                "claude-test",
                listOf(
                    ProviderMessage(ProviderMessage.Role.user, "hi"),
                    ProviderMessage(
                        role = ProviderMessage.Role.assistant,
                        content = "done",
                        thinking = "weighing it up",
                        thinkingSignature = "EqQBsig",
                    ),
                ),
                ChatOptions(),
                emptyList(),
            ).toList()
        }
        assertTrue(assistantBlocks().none { it["type"]!!.jsonPrimitive.content == "thinking" })
    }

    @Test
    fun `an http error carries the response body, not the empty HTTP2 reason phrase`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"type":"error","error":{"message":"thinking block missing"}}"""),
        )
        val chunks = withTimeout(10_000L) {
            provider().chat(
                "claude-test",
                listOf(ProviderMessage(ProviderMessage.Role.user, "hi")),
            ).toList()
        }
        val error = assertNotNull(chunks.firstNotNullOfOrNull { it.error })
        assertTrue(
            "thinking block missing" in error.message,
            "the body is the only thing that says WHY; got '${error.message}'",
        )
        assertFalse(error.retryable)
    }

    @Test
    fun `a 429 surfaces the server's Retry-After`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse().setResponseCode(429)
                .setHeader("Retry-After", "9")
                .setBody("""{"error":{"message":"slow down"}}"""),
        )
        val chunks = withTimeout(10_000L) {
            provider().chat(
                "claude-test",
                listOf(ProviderMessage(ProviderMessage.Role.user, "hi")),
            ).toList()
        }
        val error = assertNotNull(chunks.firstNotNullOfOrNull { it.error })
        assertEquals(true, error.retryable)
        assertEquals(9_000L, error.retryAfterMs)
    }
}
