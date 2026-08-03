package com.aura.providers

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
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
 * P0 regression: Gemini provider must emit distinct ids for parallel
 * function-call parts so the agentic loop can route results correctly.
 */
class GeminiParallelToolCallTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: GeminiProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val keys = mockk<ProviderKeys> {
            coEvery { keyForAwaiting("gemini") } returns "key"
            every { isConfigured("gemini") } returns true
        }
        provider = GeminiProvider(
            providerKeys = keys,
            httpClient = OkHttpClient.Builder().build(),
            baseUrl = server.url("/").toString().removeSuffix("/"),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `two function call parts in one response get different ids`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    buildString {
                        appendLine("""{"candidates":[{"content":{"parts":[{"text":"ok"},{"functionCall":{"name":"search","args":{"q":"a"}}},{"functionCall":{"name":"search","args":{"q":"b"}}}]},"finishReason":"STOP"}]}""")
                    }
                )
        )

        val chunks = provider.chat(
            model = "gemini-2.5-flash",
            messages = listOf(ProviderMessage(role = ProviderMessage.Role.user, content = "search twice")),
            options = ChatOptions(),
            tools = emptyList(),
        ).toList()

        val toolCalls = chunks.mapNotNull { it.toolCall }
        assertEquals(2, toolCalls.size, "two parallel function calls should be emitted")
        assertTrue(toolCalls[0].id != toolCalls[1].id, "parallel calls must have distinct ids")
        assertEquals("search", toolCalls[0].name)
        assertEquals("search", toolCalls[1].name)
    }
}
