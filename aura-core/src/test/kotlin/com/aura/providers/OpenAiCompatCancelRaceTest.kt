package com.aura.providers

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 * P0 regression: hitting [Provider.cancel] must actually abort the SSE
 * connection, even if the call races with the creation of the EventSource.
 */
class OpenAiCompatCancelRaceTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: OpenAiCompatProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val keys = mockk<ProviderKeys> {
            coEvery { keyForAwaiting("test") } returns "key"
            every { isConfigured("test") } returns true
        }
        provider = OpenAiCompatProvider(
            prefix = "test",
            displayName = "Test",
            baseUrl = server.url("/").toString().removeSuffix("/"),
            providerKeys = keys,
            httpClient = OkHttpClient.Builder().build(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `cancel reaches the source even when called before onEvent fires`() = runBlocking {
        // Use a response that never sends data, so onEvent never fires and
        // activeEventSource stays as the holder. cancel() must still abort it.
        // Only comments, so onEvent never fires with data and
        // activeEventSource stays as the EventSourceHolder — the race this
        // test is named for.
        //
        // The body is deliberately far larger than the old one. It used to be a
        // single 16-byte line, which the server finished sending almost
        // immediately, so the collector completed on its own whatever cancel()
        // did — and the assertion was `assertTrue(true)`, so nothing noticed.
        // At 64 bytes per 500ms this needs about ten minutes to drain
        // naturally, well past both STREAM_READ_TIMEOUT_MS and the deadline
        // below, so finishing at all is only possible if cancel() reached the
        // source.
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(": keep-alive\n\n".repeat(5_000))
                .throttleBody(64, 500L, java.util.concurrent.TimeUnit.MILLISECONDS)
        )

        val job = launch {
            provider.chat(
                model = "test-model",
                messages = listOf(ProviderMessage(role = ProviderMessage.Role.user, content = "hi")),
                options = ChatOptions(),
                tools = emptyList(),
            ).collect { }
        }

        // Cancel immediately. Before the fix this would leave the source
        // running because activeEventSource was still null during creation.
        delay(50)
        provider.cancel()

        // Bounded, not an open-ended join(). A join() that hangs fails the test
        // by timing out the whole build, which reports as an infrastructure
        // problem rather than as this defect; and with the old one-line body it
        // returned promptly whether or not cancel() did anything at all.
        val finishedWithin = kotlinx.coroutines.withTimeoutOrNull(3_000) { job.join() } != null
        if (!finishedWithin) job.cancel()

        assertTrue(
            finishedWithin,
            "cancel() did not abort the stream: the collector was still running three seconds later, " +
                "against a response that needs about ten minutes to drain. The remote is still " +
                "generating billable tokens for an answer nobody is reading.",
        )
    }
}
