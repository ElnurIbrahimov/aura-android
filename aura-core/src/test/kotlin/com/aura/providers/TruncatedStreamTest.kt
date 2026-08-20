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
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A socket that dies in the middle of an answer.
 *
 * This is the most likely real failure for a streaming LLM client — mobile radio handover,
 * a proxy timing out a long generation, a laptop lid closing — and before this the whole
 * provider suite had two `SocketPolicy` uses, both `NO_RESPONSE`, which is a *connect*
 * failure. `DISCONNECT_DURING_RESPONSE_BODY` appeared nowhere.
 *
 * What matters is not that it fails. It is that the caller cannot mistake half an answer
 * for a whole one: a truncated reply that arrives clean is persisted as the model's final
 * word, fed back as history on the next turn, and offered no retry.
 *
 * The three families report it two different ways, which writing these is how I found out.
 * Anthropic lets the IOException out of the flow. Gemini and the OpenAI-compatible family
 * catch it and emit `ProviderChunk(error = …, retryable = true)` instead, which the loop
 * turns into `AgentEvent.Error` at `MemoryAugmentedAgenticLoop:1237`. Both are detectable
 * and neither is wrong, so these assert the property rather than the mechanism — what none
 * of them may do is report a clean finish.
 *
 * Each asserts it *after* deltas have already been delivered, because a provider that
 * buffered internally would pass the existing connect-failure tests and still swallow this.
 */
class TruncatedStreamTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    /** A response whose body stops mid-event, with the connection dropped underneath it. */
    private fun enqueueTruncated(body: String) {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body)
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
    }

    private fun keys(prefix: String) = mockk<ProviderKeys> {
        coEvery { keyForAwaiting(prefix) } returns "test-key"
        every { isConfigured(prefix) } returns true
        every { keyFor(prefix) } returns "test-key"
    }

    private fun baseUrl() = server.url("/").toString().removeSuffix("/")

    @Test
    fun `Anthropic surfaces a stream cut mid-answer`() = runBlocking<Unit> {
        // Two complete deltas, then the third event is cut off mid-JSON.
        enqueueTruncated(
            "data: {\"type\":\"content_block_delta\",\"index\":0," +
                "\"delta\":{\"type\":\"text_delta\",\"text\":\"The answer is\"}}\n\n" +
                "data: {\"type\":\"content_block_delta\",\"index\":0," +
                "\"delta\":{\"type\":\"text_delta\",\"text\":\" forty\"}}\n\n" +
                "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text",
        )
        val provider = AnthropicProvider(
            providerKeys = keys("anthropic"),
            httpClient = OkHttpClient(),
            baseUrl = baseUrl(),
        )

        assertTruncationIsVisible {
            provider.chat("claude-test", listOf(ProviderMessage(ProviderMessage.Role.user, "hi")))
        }
    }

    @Test
    fun `Gemini surfaces a stream cut mid-answer`() = runBlocking<Unit> {
        enqueueTruncated(
            "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"The answer is\"}]}}]}\n\n" +
                "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text",
        )
        val provider = GeminiProvider(
            providerKeys = keys("gemini"),
            httpClient = OkHttpClient(),
            baseUrl = baseUrl(),
        )

        assertTruncationIsVisible {
            provider.chat("gemini-test", listOf(ProviderMessage(ProviderMessage.Role.user, "hi")))
        }
    }

    @Test
    fun `an OpenAI-compatible provider surfaces a stream cut mid-answer`() = runBlocking<Unit> {
        enqueueTruncated(
            "data: {\"choices\":[{\"delta\":{\"content\":\"The answer is\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"cont",
        )
        val provider = GroqProvider(
            providerKeys = keys("groq"),
            httpClient = OkHttpClient(),
            baseUrl = baseUrl(),
        )

        assertTruncationIsVisible {
            provider.chat("groq-test", listOf(ProviderMessage(ProviderMessage.Role.user, "hi")))
        }
    }

    /**
     * Fail unless the severed stream is visible to the caller.
     *
     * Visible means one of two things: the flow threw, or a chunk carrying an error
     * reached the collector. What fails the test is the third case — the flow completing
     * with no error, which is the shape that makes a truncated answer look finished.
     *
     * The exception type is deliberately not pinned. The families wrap their transports
     * differently, and a reasonable change to error mapping should not break a test that
     * is not about error mapping.
     */
    private suspend fun assertTruncationIsVisible(open: () -> kotlinx.coroutines.flow.Flow<ProviderChunk>) {
        val chunks = mutableListOf<ProviderChunk>()
        val thrown = runCatching {
            withTimeout(15_000L) { open().toList(chunks) }
        }.exceptionOrNull()

        if (thrown is kotlinx.coroutines.TimeoutCancellationException) {
            fail("the stream hung rather than reporting the disconnect")
        }
        val reportedError = chunks.any { it.error != null }
        assertTrue(
            thrown != null || reportedError,
            "the stream completed cleanly after ${chunks.size} chunk(s) — a truncated answer " +
                "reached the caller as a whole one",
        )
        assertTrue(
            chunks.none { it.finishReason == FinishReason.stop && it.error == null } || reportedError,
            "the stream reported a normal stop for an answer that was cut off",
        )
    }
}
