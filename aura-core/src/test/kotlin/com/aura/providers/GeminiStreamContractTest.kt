package com.aura.providers

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Gemini's streaming contract, which had three independent defects that each
 * produced a silent failure.
 *
 * The endpoint was called without `?alt=sse`, so Google answered with one
 * pretty-printed JSON array while the parser read a JSON object per line inside
 * a `catch { continue }` — nothing parsed, and every reply arrived empty.
 * Fixing that alone was not enough: Gemini reports `finishReason: "STOP"` on
 * the same chunk as a `functionCall`, and the agentic loop stops on "stop", so
 * the fixed stream would have recorded tool calls and executed none. And the
 * call id was built from a part index that reset on every line, so two chunks
 * calling one tool collided on a single id.
 */
class GeminiStreamContractTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: GeminiProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = GeminiProvider(
            providerKeys = mockk {
                coEvery { keyForAwaiting("gemini") } returns "test-key"
                every { isConfigured("gemini") } returns true
            },
            httpClient = OkHttpClient(),
            baseUrl = server.url("/").toString().removeSuffix("/"),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueue(body: String) {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body),
        )
    }

    private fun collect(): List<ProviderChunk> = runBlocking {
        withTimeout(10_000L) {
            provider.chat(
                "gemini-2.5-flash",
                listOf(ProviderMessage(ProviderMessage.Role.user, "hi")),
            ).toList()
        }
    }

    @Test
    fun `the stream is requested as SSE`() {
        enqueue("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}]}\n\n")
        collect()
        val recorded = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS), "no request was sent")
        assertTrue(
            "alt=sse" in recorded.path.orEmpty(),
            "without ?alt=sse Google returns a pretty-printed JSON array this parser cannot read; got ${recorded.path}",
        )
    }

    @Test
    fun `SSE-framed lines are parsed`() {
        enqueue(
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hello\"}]}}]}\n\n" +
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\" world\"}]},\"finishReason\":\"STOP\"}]}\n\n",
        )
        assertEquals("hello world", collect().mapNotNull { it.text }.joinToString(""))
    }

    @Test
    fun `bare newline-delimited objects still parse`() {
        // The prefix is stripped when present rather than required, so fixtures
        // and captures predating ?alt=sse keep working.
        enqueue("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ok\"}]},\"finishReason\":\"STOP\"}]}\n")
        assertEquals("ok", collect().mapNotNull { it.text }.joinToString(""))
    }

    @Test
    fun `a functionCall alongside STOP finishes as tool_calls`() {
        enqueue(
            "data: {\"candidates\":[{\"content\":{\"parts\":[" +
                "{\"functionCall\":{\"name\":\"web_search\",\"args\":{\"q\":\"kotlin\"}}}" +
                "]},\"finishReason\":\"STOP\"}]}\n\n",
        )
        val chunks = collect()
        assertEquals(1, chunks.count { it.toolCall != null })
        assertEquals(
            FinishReason.tool_calls,
            chunks.last { it.finishReason != null }.finishReason,
            "reporting STOP verbatim records the call and never runs it",
        )
    }

    @Test
    fun `MAX_TOKENS still reports length even after a function call`() {
        // Truncated arguments are not a tool call worth dispatching.
        enqueue(
            "data: {\"candidates\":[{\"content\":{\"parts\":[" +
                "{\"functionCall\":{\"name\":\"web_search\",\"args\":{}}}" +
                "]},\"finishReason\":\"MAX_TOKENS\"}]}\n\n",
        )
        assertEquals(FinishReason.length, collect().last { it.finishReason != null }.finishReason)
    }

    @Test
    fun `function calls split across chunks get distinct ids`() {
        enqueue(
            "data: {\"candidates\":[{\"content\":{\"parts\":[" +
                "{\"functionCall\":{\"name\":\"web_search\",\"args\":{\"q\":\"a\"}}}]}}]}\n\n" +
                "data: {\"candidates\":[{\"content\":{\"parts\":[" +
                "{\"functionCall\":{\"name\":\"web_search\",\"args\":{\"q\":\"b\"}}}]},\"finishReason\":\"STOP\"}]}\n\n",
        )
        val ids = collect().mapNotNull { it.toolCall?.id }
        assertEquals(2, ids.size, "both calls must be emitted")
        assertEquals(
            ids.size,
            ids.toSet().size,
            "the part index reset on every line, so two chunks calling one tool collided on one id",
        )
    }

    @Test
    fun `a 429 surfaces the server's Retry-After`() {
        server.enqueue(
            MockResponse().setResponseCode(429)
                .setHeader("Retry-After", "4")
                .setBody("""{"error":{"message":"quota exceeded"}}"""),
        )
        val error = assertNotNull(collect().firstNotNullOfOrNull { it.error })
        assertEquals(true, error.retryable)
        assertEquals(4_000L, error.retryAfterMs)
    }
}
