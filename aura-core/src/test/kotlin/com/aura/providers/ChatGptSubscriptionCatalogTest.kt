package com.aura.providers

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The ChatGPT Subscription catalog is fetched live.
 *
 * It used to be a hardcoded list — `gpt-5`, `gpt-5-mini`, `gpt-5-nano`,
 * `gpt-4.1`, `gpt-4.1-mini`, `gpt-4o`, `gpt-4o-mini`, `o3`, `o4-mini` —
 * justified by a comment claiming the backend exposes no models endpoint.
 * It does; it just requires a `client_version` query parameter and returns
 * 400 without one. By the time that was checked, not one of those nine ids
 * was still offered, so the picker listed nine models the backend would
 * refuse.
 *
 * These tests pin the live path so nobody re-freezes the list.
 */
class ChatGptSubscriptionCatalogTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun keys(): ProviderKeys = mockk {
        coEvery { keyForAwaiting("chatgpt") } returns "sess-token"
        every { keyFor("chatgpt") } returns "sess-token"
    }

    private fun provider() = ChatGptSubscriptionProvider(
        providerKeys = keys(),
        httpClient = OkHttpClient(),
        baseUrl = server.url("/").toString().removeSuffix("/"),
    )

    /** Shape of the real response, trimmed to the fields the provider reads. */
    private val catalogBody = """
        {"models":[
          {"slug":"gpt-5.6-sol","display_name":"GPT-5.6-Sol","context_window":272000,"visibility":"list"},
          {"slug":"gpt-5.5","display_name":"GPT-5.5","context_window":272000,"visibility":"list"},
          {"slug":"gpt-5.6-sol-wm","display_name":"GPT-5.6-Sol-WM","context_window":272000,"visibility":"hide"},
          {"slug":"codex-auto-review","display_name":"Codex Auto Review","context_window":272000,"visibility":"hide"}
        ]}
    """.trimIndent()

    @Test
    fun `models come from the backend, not a baked-in list`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(catalogBody))

        val models = provider().listModels()

        assertEquals(listOf("gpt-5.6-sol", "gpt-5.5"), models)
        // The old hardcoded ids must not appear from anywhere.
        assertTrue(models.none { it in setOf("gpt-5", "gpt-4o", "o3", "o4-mini") })
    }

    @Test
    fun `the request carries client_version — without it the backend 400s`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(catalogBody))

        provider().listModels()

        val recorded = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!
        assertTrue(
            recorded.path!!.contains("client_version="),
            "catalog request must send client_version, got ${recorded.path}",
        )
        assertEquals("Bearer sess-token", recorded.getHeader("Authorization"))
    }

    @Test
    fun `models hidden by the backend are not offered`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(catalogBody))

        val models = provider().listModels()

        assertTrue("gpt-5.6-sol-wm" !in models, "watermark variant is visibility=hide")
        assertTrue("codex-auto-review" !in models, "auto-review model is visibility=hide")
    }

    @Test
    fun `real context windows are reported, not guessed`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(catalogBody))

        val infos = provider().listModelsWithContext()

        assertEquals(272_000, infos.first { it.name == "gpt-5.6-sol" }.contextWindow)
    }

    @Test
    fun `an HTTP failure surfaces rather than falling back to a stale list`() {
        server.enqueue(MockResponse().setResponseCode(401))

        // A hardcoded fallback here is exactly how the stale list survived
        // unnoticed: the catalog looked healthy while the token was dead.
        assertFailsWith<ProviderCatalogException> {
            runBlocking { provider().listModels() }
        }
    }

    @Test
    fun `an empty catalog is an error, not an empty picker`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"models":[]}"""))

        assertFailsWith<ProviderCatalogException.EmptyCatalogException> {
            runBlocking { provider().listModels() }
        }
    }

    @Test
    fun `no token means no catalog request at all`() = runBlocking {
        val noKey = mockk<ProviderKeys> {
            coEvery { keyForAwaiting("chatgpt") } returns null
            every { keyFor("chatgpt") } returns null
        }
        val models = ChatGptSubscriptionProvider(
            providerKeys = noKey,
            httpClient = OkHttpClient(),
            baseUrl = server.url("/").toString().removeSuffix("/"),
        ).listModels()

        assertTrue(models.isEmpty())
        assertEquals(0, server.requestCount)
    }
}
