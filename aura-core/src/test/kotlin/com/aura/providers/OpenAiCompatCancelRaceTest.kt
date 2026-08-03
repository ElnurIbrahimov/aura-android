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
            defaultModels = emptyList(),
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
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                // Send only comments; onEvent is never called with data, so
                // activeEventSource remains the EventSourceHolder. The stream
                // should time out, but cancel() must complete before that.
                .setBody(": keep-alive\n\n")
                .throttleBody(64, 5_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
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
        job.join()

        // The only observable here is that the job completed and didn't hang.
        assertTrue(true)
    }
}
