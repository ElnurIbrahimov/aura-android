package com.aura.providers

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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * PROVIDERS_AUDIT B1: auth errors must not trigger failover.
 *
 * The agentic loop fails over to the next configured provider when a chunk
 * arrives with `retryable = true` (MemoryAugmentedAgenticLoop, the
 * `chunk.retryable && triedModels.size < 2` branch). If a provider marked a
 * 401 as retryable, a single mistyped API key would burn a request against
 * every configured provider before surfacing the error.
 *
 * ## Why this test looks the way it does
 *
 * The previous version of this file scanned the provider `.kt` sources as
 * text, asserting that literals like `"429"` and `"!= 401"` appeared. That
 * had three problems:
 *
 * 1. **It verified syntax, not behavior.** A provider could contain the
 *    string `429` in an unrelated comment and pass.
 * 2. **It inverted control.** The 2026-07-26 review records changing
 *    production code from a negative to a positive retryable check purely
 *    to satisfy the string scan — the test dictated implementation style
 *    while checking nothing real.
 * 3. **It could pass vacuously.** Path resolution used hardcoded absolute
 *    paths with a `mapNotNull` fallback; when no path matched, the file
 *    list was empty and every `for` loop over it succeeded trivially.
 *
 * This version drives each provider against a MockWebServer returning the
 * real status code and asserts on the emitted [ProviderError.retryable].
 * It is indifferent to how the classification is written.
 */
class NonRetryableStatusCodesTest {

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

    private fun keys(prefix: String) = mockk<ProviderKeys> {
        every { keyFor(prefix) } returns "test-key"
        every { isConfigured(prefix) } returns true
    }

    private fun baseUrl(): String = server.url("/").toString().removeSuffix("/")

    /** Collect the first error emitted by a provider's chat stream. */
    private fun errorFor(provider: Provider): ProviderError {
        val chunks = runBlocking {
            withTimeout(10_000) {
                provider.chat(
                    model = "test-model",
                    messages = listOf(ProviderMessage(role = ProviderMessage.Role.user, content = "hi")),
                ).toList()
            }
        }
        return assertNotNull(
            chunks.firstNotNullOfOrNull { it.error },
            "provider emitted no error chunk for the mocked HTTP failure",
        )
    }

    private fun openAiCompat() = OpenAiCompatProvider(
        prefix = "test",
        displayName = "Test",
        baseUrl = baseUrl(),
        providerKeys = keys("test"),
        httpClient = OkHttpClient(),
    )

    private fun anthropic() = AnthropicProvider(
        providerKeys = keys("anthropic"),
        httpClient = OkHttpClient(),
        baseUrl = baseUrl(),
    )

    private fun gemini() = GeminiProvider(
        providerKeys = keys("gemini"),
        httpClient = OkHttpClient(),
        baseUrl = baseUrl(),
    )

    // ── Non-retryable: client errors that won't fix themselves ──────────

    @Test
    fun `401 is not retryable for OpenAI-compatible providers`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        assertEquals(false, errorFor(openAiCompat()).retryable)
    }

    @Test
    fun `401 is not retryable for Anthropic`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        assertEquals(false, errorFor(anthropic()).retryable)
    }

    @Test
    fun `401 is not retryable for Gemini`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        assertEquals(false, errorFor(gemini()).retryable)
    }

    @Test
    fun `400 is not retryable for OpenAI-compatible providers`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"bad request"}"""))
        assertEquals(false, errorFor(openAiCompat()).retryable)
    }

    @Test
    fun `403 is not retryable for OpenAI-compatible providers`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"forbidden"}"""))
        assertEquals(false, errorFor(openAiCompat()).retryable)
    }

    @Test
    fun `400 and 403 are not retryable for Anthropic`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"bad request"}"""))
        assertEquals(false, errorFor(anthropic()).retryable)

        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"forbidden"}"""))
        assertEquals(false, errorFor(anthropic()).retryable)
    }

    // ── Retryable: transient failures that failover can recover from ────

    @Test
    fun `429 is retryable for OpenAI-compatible providers`() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"rate limited"}"""))
        assertEquals(true, errorFor(openAiCompat()).retryable)
    }

    @Test
    fun `429 is retryable for Anthropic`() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"rate limited"}"""))
        assertEquals(true, errorFor(anthropic()).retryable)
    }

    @Test
    fun `500 is retryable for OpenAI-compatible providers`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"server error"}"""))
        assertEquals(true, errorFor(openAiCompat()).retryable)
    }

    @Test
    fun `503 is retryable for Anthropic`() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"unavailable"}"""))
        assertEquals(true, errorFor(anthropic()).retryable)
    }

    @Test
    fun `429 and 5xx are retryable for Gemini`() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"rate limited"}"""))
        assertEquals(true, errorFor(gemini()).retryable)

        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"server error"}"""))
        assertEquals(true, errorFor(gemini()).retryable)
    }
}
