package com.aura.providers

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Wire-level guard for Anthropic's own invariant: `max_tokens` must exceed
 * `budget_tokens`, and `budget_tokens` must be at least 1024.
 *
 * [com.aura.agent.TokenBudgetPolicy] already guarantees both for anything routed
 * through `Brain`, and that is where the policy belongs. This exists because
 * four Creative Studio call sites reached this provider with
 * `budget_tokens >= max_tokens` for months — `Brain` skipped its budget block
 * entirely whenever a caller set its own thinking budget — and took a
 * non-retryable 400 every time. A vendor invariant enforced only one layer up is
 * an invariant something can route around, and something did.
 *
 * These call the provider directly, bypassing `Brain`, which is the point.
 */
class AnthropicThinkingBudgetContractTest {

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

    private fun provider(): AnthropicProvider = AnthropicProvider(
        providerKeys = mockk {
            coEvery { keyForAwaiting("anthropic") } returns "test-key"
            every { isConfigured("anthropic") } returns true
        },
        httpClient = OkHttpClient(),
        baseUrl = server.url("/").toString().removeSuffix("/"),
    )

    /** Drives one chat call and returns the JSON body that reached the wire. */
    private fun bodyFor(options: ChatOptions): JsonObject = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"message_stop\"}\n\n"),
        )
        withTimeout(10_000L) {
            provider().chat(
                "claude-test",
                listOf(ProviderMessage(ProviderMessage.Role.user, "hi")),
                options,
                emptyList(),
            ).toList()
        }
        Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
    }

    private fun JsonObject.maxTokens(): Int = this["max_tokens"]!!.jsonPrimitive.int
    private fun JsonObject.budgetTokens(): Int? =
        (this["thinking"] as? JsonObject)?.get("budget_tokens")?.jsonPrimitive?.int

    /**
     * The four shapes that were being rejected. Each is a real call site:
     * ProseCraftTools, TensionAnalyzer, VoiceCalibration.calibrate and
     * CharacterProgressionTracker.
     */
    @Test
    fun `an oversized thinking budget is clamped below max_tokens`() {
        val cases = listOf(
            "ProseCraftTools EXPAND" to ChatOptions(maxTokens = 8_192, thinkingBudget = 8_192),
            "ProseCraftTools other" to ChatOptions(maxTokens = 4_096, thinkingBudget = 8_192),
            "TensionAnalyzer" to ChatOptions(maxTokens = 6_000, thinkingBudget = 16_384),
            "VoiceCalibration" to ChatOptions(maxTokens = 1_500, thinkingBudget = 8_192),
            "CharacterProgression" to ChatOptions(maxTokens = 2_000, thinkingBudget = 4_096),
        )
        for ((name, options) in cases) {
            val body = bodyFor(options)
            val budget = body.budgetTokens()
            if (budget != null) {
                assertTrue(
                    body.maxTokens() > budget,
                    "$name: max_tokens=${body.maxTokens()} must exceed budget_tokens=$budget",
                )
                assertTrue(budget >= 1024, "$name: budget_tokens=$budget is below Anthropic's floor")
            }
        }
    }

    /**
     * The clamp only ever moves the budget down. Raising `max_tokens` to fit
     * would increase spend behind the caller's back, which a provider must
     * never do on its own initiative.
     */
    @Test
    fun `max_tokens is never raised to accommodate thinking`() {
        val body = bodyFor(ChatOptions(maxTokens = 2_000, thinkingBudget = 16_384))
        assertEquals(2_000, body.maxTokens(), "the caller's output budget must be sent verbatim")
    }

    /** Below Anthropic's floor the block is omitted, not sent as a rejected value. */
    @Test
    fun `a sub-floor thinking budget omits the thinking block entirely`() {
        val body = bodyFor(ChatOptions(maxTokens = 1_500, thinkingBudget = 512))
        assertFalse("thinking" in body.keys, "a budget below 1024 must not be sent at all")
    }

    /**
     * A max_tokens so small that no legal budget fits under it must also drop
     * the block rather than send `budget_tokens = maxTokens - 1`.
     */
    @Test
    fun `a max_tokens too small for any legal budget omits thinking`() {
        val body = bodyFor(ChatOptions(maxTokens = 800, thinkingBudget = 8_192))
        assertEquals(800, body.maxTokens())
        assertFalse("thinking" in body.keys, "no budget >= 1024 can fit under max_tokens=800")
    }

    /** Anthropic requires max_tokens, so unlike the OpenAI-compatible providers it cannot be omitted. */
    @Test
    fun `an absent max_tokens falls back to the documented default`() {
        val body = bodyFor(ChatOptions())
        assertEquals(4096, body.maxTokens())
    }

    @Test
    fun `a well-formed pair passes through untouched`() {
        val body = bodyFor(ChatOptions(maxTokens = 24_576, thinkingBudget = 8_192))
        assertEquals(24_576, body.maxTokens())
        assertEquals(8_192, body.budgetTokens())
    }
}
