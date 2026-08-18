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
import kotlin.test.assertTrue
import com.aura.testing.networkTestTimeout
import org.junit.Rule
import org.junit.rules.Timeout

/**
 * Gemini's catalog is not a list of chat models.
 *
 * Of the 58 entries a real key returns, 16 cannot hold a conversation —
 * embedders, Imagen, Veo, live audio. Taking the list wholesale put
 * "Imagen 4.0 Ultra Generate" in the chat model picker, and a new
 * conversation opened on it: every message went to an image endpoint and
 * nothing ever came back.
 */
class GeminiChatCapableCatalogTest {

    /** See [networkTestTimeout] — uniform, not judged per class. */
    @get:Rule
    val globalTimeout: Timeout = networkTestTimeout()

    private lateinit var server: MockWebServer
    private lateinit var provider: GeminiProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        provider = GeminiProvider(
            providerKeys = mockk {
                coEvery { keyForAwaiting("gemini") } returns "key"
                every { keyFor("gemini") } returns "key"
                every { isConfigured("gemini") } returns true
            },
            httpClient = OkHttpClient(),
            baseUrl = server.url("/").toString().removeSuffix("/"),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    /** Trimmed to the fields the provider reads, names taken from the live catalog. */
    private val catalog = """
        {"models":[
          {"name":"models/gemini-2.5-pro","inputTokenLimit":1048576,
           "supportedGenerationMethods":["generateContent","countTokens"]},
          {"name":"models/gemini-2.5-flash","inputTokenLimit":1048576,
           "supportedGenerationMethods":["generateContent"]},
          {"name":"models/imagen-4.0-ultra-generate-001",
           "supportedGenerationMethods":["predict"]},
          {"name":"models/veo-3.1-generate-preview",
           "supportedGenerationMethods":["predictLongRunning"]},
          {"name":"models/gemini-embedding-001",
           "supportedGenerationMethods":["embedContent"]},
          {"name":"models/gemini-3.1-flash-live-preview",
           "supportedGenerationMethods":["bidiGenerateContent"]}
        ]}
    """.trimIndent()

    @Test
    fun `only models that can generate content are offered`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(catalog))

        val models = provider.listModels()

        assertEquals(listOf("gemini-2.5-pro", "gemini-2.5-flash"), models)
    }

    @Test
    fun `image, video, embedding and live models are excluded`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(catalog))

        val models = provider.listModels()

        // Imagen was the one that actually reached the picker and became the
        // default model for a new conversation.
        assertTrue("imagen-4.0-ultra-generate-001" !in models)
        assertTrue("veo-3.1-generate-preview" !in models)
        assertTrue("gemini-embedding-001" !in models)
        assertTrue("gemini-3.1-flash-live-preview" !in models, "live models take bidiGenerateContent, not generateContent")
    }

    @Test
    fun `the context-window catalog applies the same filter`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(catalog))

        val infos = provider.listModelsWithContext()

        assertEquals(listOf("gemini-2.5-pro", "gemini-2.5-flash"), infos.map { it.name })
        assertEquals(1_048_576, infos.first().contextWindow)
    }

    @Test
    fun `an entry that declares no methods is kept rather than guessed away`() = runBlocking {
        // Older catalog responses omit the field. A model that errors on use
        // is recoverable; one silently missing from the picker is not.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"models":[{"name":"models/gemini-experimental"}]}"""),
        )

        assertEquals(listOf("gemini-experimental"), provider.listModels())
    }
}
