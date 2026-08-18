package com.aura.providers

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.aura.testing.networkTestTimeout
import org.junit.Rule
import org.junit.rules.Timeout

/**
 * Token-usage reporting, including the prompt-cache figures.
 *
 * Twelve of seventeen prefixes reported no usage at all before this: the
 * OpenAI-compatible parser had no usage path, and Anthropic read neither of the
 * two events that carry it. `ProviderRegistry` therefore billed them by
 * `content.length / 4`, and there was no way to tell a cache hit from a miss —
 * which is the number that decides whether prompt caching is worth keeping.
 */
class UsageReportingTest {

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

    // ---- the regression this fix exists for ------------------------------

    @Test
    fun `usage on a chunk with an empty choices array is not dropped`() {
        // THE bug. OpenAI sends usage on a final event whose `choices` is an
        // empty array. The parser returned early on that guard, so the one
        // event carrying usage was the one event it discarded.
        val chunks = OpenAiSseParser().parseEvent(
            """{"choices":[],"usage":{"prompt_tokens":1200,"completion_tokens":50,"total_tokens":1250,
               "prompt_tokens_details":{"cached_tokens":1024}}}""".trimIndent().replace("\n", ""),
        )

        val usage = chunks.firstNotNullOfOrNull { it.usage }
        assertNotNull(usage, "usage was dropped on the empty-choices event")
        assertEquals(1200, usage.promptTokens)
        assertEquals(50, usage.completionTokens)
        assertEquals(1024, usage.cachedPromptTokens)
    }

    @Test
    fun `usage rides alongside a normal delta without displacing it`() {
        val chunks = OpenAiSseParser().parseEvent(
            """{"choices":[{"delta":{"content":"hi"},"finish_reason":"stop"}],""" +
                """"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}""",
        )
        assertEquals("hi", chunks.firstNotNullOfOrNull { it.text })
        assertEquals(FinishReason.stop, chunks.firstNotNullOfOrNull { it.finishReason })
        assertEquals(10, chunks.firstNotNullOfOrNull { it.usage }?.promptTokens)
    }

    @Test
    fun `an all-zero usage object produces no chunk`() {
        val chunks = OpenAiSseParser().parseEvent(
            """{"choices":[],"usage":{"prompt_tokens":0,"completion_tokens":0,"total_tokens":0}}""",
        )
        assertTrue(chunks.isEmpty(), "a zero usage object carries no information")
    }

    @Test
    fun `missing cached_tokens defaults to zero rather than failing`() {
        val chunks = OpenAiSseParser().parseEvent(
            """{"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":5,"total_tokens":105}}""",
        )
        assertEquals(0, chunks.firstNotNullOfOrNull { it.usage }?.cachedPromptTokens)
    }

    @Test
    fun `an event with no usage and no choices still yields nothing`() {
        assertTrue(OpenAiSseParser().parseEvent("""{"id":"x","object":"chunk"}""").isEmpty())
    }

    // ---- stream_options --------------------------------------------------

    @Test
    fun `openai-compatible requests ask for usage`() {
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
                    listOf(ProviderMessage(ProviderMessage.Role.user, "hey")),
                    ChatOptions(),
                ).toList()
            }
        }
        val body = takeRequestBody()
        assertEquals(
            true,
            body["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.content.toBoolean(),
        )
    }

    @Test
    fun `the custom endpoint does NOT ask for usage`() {
        // A user-supplied URL may be llama.cpp, LM Studio, or a corporate
        // gateway. A 400 on an unrecognised key there breaks their chat, and
        // usage reporting is not worth that trade.
        val src = java.io.File(
            "aura-core/src/main/kotlin/com/aura/providers/CustomOpenAiCompatProvider.kt",
        ).let { if (it.exists()) it else java.io.File("../$it") }
        assertTrue(src.exists(), "could not locate CustomOpenAiCompatProvider source")
        assertTrue(
            "stream_options" !in src.readText(),
            "CustomOpenAiCompatProvider must not send stream_options",
        )
    }

    // ---- Anthropic: usage split across two events ------------------------

    @Test
    fun `anthropic reports usage and both cache figures`() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":40," +
                        "\"cache_read_input_tokens\":1024,\"cache_creation_input_tokens\":16}}}\n\n" +
                        "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}," +
                        "\"usage\":{\"output_tokens\":25}}\n\n" +
                        "data: {\"type\":\"message_stop\"}\n\n",
                ),
        )
        val provider = AnthropicProvider(
            providerKeys = keys("anthropic"),
            httpClient = OkHttpClient(),
            baseUrl = server.url("/").toString().removeSuffix("/"),
        )
        val chunks = runBlocking {
            withTimeout(10_000L) {
                provider.chat(
                    "claude-sonnet-4.6",
                    listOf(ProviderMessage(ProviderMessage.Role.user, "hey")),
                    ChatOptions(),
                ).toList()
            }
        }

        val usage = chunks.firstNotNullOfOrNull { it.usage }
        assertNotNull(usage, "Anthropic reported no usage")
        // Anthropic reports cache reads SEPARATELY from input_tokens; they are
        // folded in so promptTokens means the same thing as on OpenAI and
        // cachedPromptTokens stays a subset of it.
        assertEquals(40 + 1024 + 16, usage.promptTokens)
        assertEquals(1024, usage.cachedPromptTokens)
        assertEquals(16, usage.cacheWritePromptTokens)
        assertEquals(25, usage.completionTokens)
    }

    @Test
    fun `anthropic usage arrives before the finish chunk`() {
        // The loop stops collecting once it sees a finish reason, so usage
        // emitted after it would be dropped on every single turn.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":10}}}\n\n" +
                        "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}," +
                        "\"usage\":{\"output_tokens\":3}}\n\n",
                ),
        )
        val provider = AnthropicProvider(
            providerKeys = keys("anthropic"),
            httpClient = OkHttpClient(),
            baseUrl = server.url("/").toString().removeSuffix("/"),
        )
        val chunks = runBlocking {
            withTimeout(10_000L) {
                provider.chat(
                    "claude-sonnet-4.6",
                    listOf(ProviderMessage(ProviderMessage.Role.user, "hey")),
                    ChatOptions(),
                ).toList()
            }
        }
        val usageAt = chunks.indexOfFirst { it.usage != null }
        val finishAt = chunks.indexOfFirst { it.finishReason != null }
        assertTrue(usageAt >= 0, "no usage chunk emitted")
        assertTrue(finishAt >= 0, "no finish chunk emitted")
        assertTrue(usageAt < finishAt, "usage must be emitted before the finish chunk")
    }

    // ---- Gemini ----------------------------------------------------------

    @Test
    fun `gemini reports cachedContentTokenCount`() {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                // NDJSON: one JSON OBJECT per line. GeminiProvider does
                // `parseToJsonElement(line).jsonObject` and skips anything that
                // is not an object, so an array wrapper yields nothing at all.
                .setBody(
                    """{"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}],""" +
                        """"usageMetadata":{"promptTokenCount":900,"candidatesTokenCount":12,""" +
                        """"totalTokenCount":912,"cachedContentTokenCount":768}}""" + "\n",
                ),
        )
        val provider = GeminiProvider(
            providerKeys = keys("gemini"),
            httpClient = OkHttpClient(),
            baseUrl = server.url("/").toString().removeSuffix("/"),
        )
        val chunks = runBlocking {
            withTimeout(10_000L) {
                provider.chat(
                    "gemini-2.5-flash",
                    listOf(ProviderMessage(ProviderMessage.Role.user, "hey")),
                    ChatOptions(),
                ).toList()
            }
        }
        val usage = chunks.firstNotNullOfOrNull { it.usage }
        assertNotNull(usage)
        assertEquals(900, usage.promptTokens)
        assertEquals(768, usage.cachedPromptTokens)
    }

    private fun takeRequestBody(): JsonObject {
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recorded, "provider never sent a request")
        return Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
    }
}
