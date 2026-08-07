package com.aura.providers

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Catalog behaviour for the ChatGPT subscription provider.
 *
 * The SSE tests that lived here described a format the backend does not use —
 * "tool calls arrive as `function_call` events or as `tool_calls` in the
 * delta" was written from the Chat Completions API, not from this one. They
 * have moved to [ChatGptSubscriptionParallelToolCallTest] and now use payloads
 * captured off the live stream.
 */
class ChatGptSubscriptionToolCallTest {

    private lateinit var server: MockWebServer
    private lateinit var provider: ChatGptSubscriptionProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val keys = mockk<ProviderKeys> {
            coEvery { keyForAwaiting("chatgpt") } returns "test-session-token"
            every { keyFor("chatgpt") } returns "test-session-token"
            every { isConfigured("chatgpt") } returns true
        }
        provider = ChatGptSubscriptionProvider(
            providerKeys = keys,
            httpClient = OkHttpClient(),
            tokenStore = chatGptTokenStore("test-session-token"),
            oauthFlow = chatGptOAuthFlow(),
            baseUrl = server.url("/").toString().removeSuffix("/"),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `listModels queries the backend instead of returning a hardcoded list`() = runBlocking {
        // This previously asserted the opposite — that listModels returned a
        // baked-in list with no network call, and specifically that it
        // contained gpt-4o, o3 and gpt-4.1. The backend offers none of those
        // to a subscription; the live catalog is gpt-5.6-sol, gpt-5.6-terra,
        // gpt-5.6-luna, gpt-5.5, gpt-5.4 and gpt-5.4-mini. Asserting the
        // stale ids is part of what kept the bug invisible.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"models":[{"slug":"gpt-5.6-sol","context_window":272000,"visibility":"list"}]}""",
            ),
        )

        val models = provider.listModels()

        assertEquals(listOf("gpt-5.6-sol"), models)
        assertTrue(
            models.none { it in setOf("gpt-4o", "o3", "gpt-4.1") },
            "stale hardcoded ids must not reappear",
        )
    }
}
