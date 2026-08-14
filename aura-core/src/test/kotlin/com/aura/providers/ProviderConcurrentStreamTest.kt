package com.aura.providers

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression test for the shared-stream-handle bug: providers are Hilt
 * singletons, and the pre-fix `finally` cancelled through the shared
 * `activeEventSource` field — so the first of two concurrent streams to
 * finish killed the other one mid-response (foreground chat vs WriteGate /
 * daemon / MoA references). Post-fix each stream cancels only its own
 * source and clears the shared field behind an identity check.
 */
class ProviderConcurrentStreamTest {

    /**
     * No test here may hang the worker forever.
     *
     * These drive MockWebServer with real coroutines from `runBlocking`, which
     * has no timeout of its own — unlike `runTest`, which carries one. On a
     * two-core CI runner a starved coroutine or a response that never arrives
     * blocks the test worker thread with nothing above it to intervene, and the
     * whole task goes silent. That is what killed `build-test` on 2026-08-13:
     * 261 classes ran, then forty minutes of nothing.
     *
     * A JUnit rule rather than converting to `runTest`, deliberately: `runTest`
     * substitutes virtual time, and these tests depend on real elapsed time
     * against a real socket. The rule interrupts and names the test instead.
     */
    @get:Rule
    val globalTimeout: Timeout = Timeout.seconds(60)

    private lateinit var server: MockWebServer
    private lateinit var provider: OpenAiCompatProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val keys = mockk<ProviderKeys> {
            coEvery { keyForAwaiting("test") } returns "test-key"
            every { isConfigured("test") } returns true
        }
        provider = OpenAiCompatProvider(
            prefix = "test",
            displayName = "Test",
            baseUrl = server.url("/").toString().removeSuffix("/"),
            providerKeys = keys,
            httpClient = OkHttpClient(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun sse(vararg events: String): String =
        events.joinToString("") { "data: $it\n\n" }

    @Test
    fun `two concurrent streams on one provider instance both complete`() = runBlocking {
        // Stream A: fast — finishes while B is still mid-body.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    sse(
                        """{"choices":[{"delta":{"content":"AAA"}}]}""",
                        """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
                        "[DONE]",
                    ),
                ),
        )
        // Stream B: throttled so A's finally block runs while B is open.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    sse(
                        """{"choices":[{"delta":{"content":"BBB"}}]}""",
                        """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
                        "[DONE]",
                    ),
                )
                .throttleBody(48, 150, TimeUnit.MILLISECONDS),
        )

        val messages = listOf(ProviderMessage(role = ProviderMessage.Role.user, content = "hi"))
        val results = withTimeout(15_000L) {
            val a = async { provider.chat("test-model", messages, ChatOptions(), emptyList()).toList() }
            delay(50) // A's request reaches the server first
            val b = async { provider.chat("test-model", messages, ChatOptions(), emptyList()).toList() }
            a.await() to b.await()
        }

        val (chunksA, chunksB) = results
        assertTrue(chunksA.none { it.error != null }, "stream A errored: ${chunksA.mapNotNull { it.error }}")
        assertTrue(chunksB.none { it.error != null }, "stream B errored: ${chunksB.mapNotNull { it.error }}")
        assertEquals("AAA", chunksA.mapNotNull { it.text }.joinToString(""))
        assertEquals("BBB", chunksB.mapNotNull { it.text }.joinToString(""))
        assertTrue(chunksB.any { it.finishReason != null }, "stream B never finished — cancelled by A's finally?")
    }
}
