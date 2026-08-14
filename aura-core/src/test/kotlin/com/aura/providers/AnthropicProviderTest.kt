package com.aura.providers

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MockWebServer tests for [AnthropicProvider.listModels].
 *
 * Every test uses a local MockWebServer so no real network calls are made.
 * The hardcoded fallback catalog has been removed — only live API responses
 * or typed exceptions are acceptable.
 */
class AnthropicProviderTest {

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
    private lateinit var provider: AnthropicProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val keys = mockk<ProviderKeys> {
            coEvery { keyForAwaiting("anthropic") } returns "test-api-key"
            every { isConfigured("anthropic") } returns true
        }
        // Use a client with followRedirects(false) to match the
        // production ProviderModule base client (PROVIDERS_AUDIT C1).
        // Without this the test would use a default OkHttpClient that
        // follows redirects, and the redirect-blocked assertions
        // would never fire.
        val secureClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        provider = AnthropicProvider(
            providerKeys = keys,
            httpClient = secureClient,
            baseUrl = server.url("/").toString().removeSuffix("/"),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── Success ──

    @Test
    fun `listModels returns model IDs from valid Anthropic response`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":[{"id":"claude-sonnet-4-20250514","type":"model"},{"id":"claude-haiku-4-20250514","type":"model"}]}""")
        )
        val models = provider.listModels()
        assertEquals(listOf("claude-sonnet-4-20250514", "claude-haiku-4-20250514"), models)
    }

    // ── 401 ──

    @Test
    fun `listModels throws AuthenticationException on 401`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(401))
        assertFailsWith<ProviderCatalogException.AuthenticationException> {
            provider.listModels()
        }
    }

    // ── 429 ──

    @Test
    fun `listModels throws RateLimitedException on 429`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(429))
        assertFailsWith<ProviderCatalogException.RateLimitedException> {
            provider.listModels()
        }
    }

    // ── Other HTTP errors ──

    @Test
    fun `listModels throws NetworkException on 500`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(500))
        assertFailsWith<ProviderCatalogException.NetworkException> {
            provider.listModels()
        }
    }

    // ── Malformed response ──

    @Test
    fun `listModels throws MalformedResponseException on invalid JSON`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not-json"))
        assertFailsWith<ProviderCatalogException.MalformedResponseException> {
            provider.listModels()
        }
    }

    @Test
    fun `listModels throws MalformedResponseException when data field is missing`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"other":[]}"""))
        val ex = assertFailsWith<ProviderCatalogException.MalformedResponseException> {
            provider.listModels()
        }
        assertTrue(ex.message?.contains("Missing data[]") == true)
    }

    @Test
    fun `listModels throws EmptyCatalogException when data array is empty`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[]}"""))
        assertFailsWith<ProviderCatalogException.EmptyCatalogException> {
            provider.listModels()
        }
    }

    // ── Network error ──

    @Test
    fun `listModels throws NetworkException when server is unreachable`() = runBlocking<Unit> {
        val unavailable = MockWebServer()
        unavailable.start()
        val unavailableBaseUrl = unavailable.url("/").toString().removeSuffix("/")
        unavailable.shutdown()
        val offline = AnthropicProvider(
            providerKeys = mockk {
                coEvery { keyForAwaiting("anthropic") } returns "key"
                every { isConfigured("anthropic") } returns true
            },
            httpClient = OkHttpClient.Builder()
                .connectTimeout(1, java.util.concurrent.TimeUnit.SECONDS)
                .build(),
            baseUrl = unavailableBaseUrl,
        )
        assertFailsWith<ProviderCatalogException.NetworkException> {
            offline.listModels()
        }
    }

    // ── CancellationException is rethrown by listModels (via CancellationException catch in impl) ──

    @Test
    fun `CancellationException is rethrown by listModels`() = runBlocking<Unit> {
        // The CancellationException handler is verified by the OpenAiCompatProvider
        // CancellationException test which uses the same pattern. Anthropic uses
        // the same catch blocks. Here we verify the catch exists by covering the
        // full method via MockWebServer + coroutine cancellation.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("incomplete".repeat(1000))
                .throttleBody(1, 1, java.util.concurrent.TimeUnit.SECONDS)
        )
        val job = launch {
            assertFailsWith<CancellationException> { provider.listModels() }
        }
        // Cancel before the slow response completes
        delay(50)
        job.cancel()
        job.join()
    }

    // ── Legacy contract: prefix, displayName, isConfigured, cancel ──

    @Test
    fun `isConfigured returns true when API key is set`() {
        assertTrue(provider.isConfigured())
    }

    @Test
    fun `isConfigured returns false when API key is not set`() {
        val unconfigured = AnthropicProvider(
            providerKeys = mockk {
                coEvery { keyForAwaiting("anthropic") } returns null
                every { isConfigured("anthropic") } returns false
            },
            httpClient = OkHttpClient(),
        )
        assertFalse(unconfigured.isConfigured())
    }

    @Test
    fun `prefix and displayName are correct`() {
        assertEquals("anthropic", provider.prefix)
        assertEquals("Anthropic", provider.displayName)
    }

    @Test
    fun `cancel does not throw when no active call`() = runBlocking<Unit> {
        provider.cancel()
    }

    @Test
    fun `cancelling chat collection cancels the active HTTP call promptly`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = scope.launch {
            runCatching {
                provider.chat(
                    model = "claude-test",
                    messages = listOf(ProviderMessage(ProviderMessage.Role.user, "hello")),
                ).collect()
            }
        }

        assertTrue(server.takeRequest(2, TimeUnit.SECONDS) != null, "chat request did not start")
        job.cancel()
        val stoppedPromptly = withTimeoutOrNull(1_000L) {
            job.join()
            true
        } ?: false
        if (!stoppedPromptly) {
            provider.cancel()
            withTimeout(2_000L) { job.join() }
        }
        scope.cancel()

        assertTrue(stoppedPromptly, "cancelling the flow did not cancel the Anthropic call")
    }

    // ── SSE streaming (P0 audit fixes for A1 + A2) ──────────────────────

    /**
     * Regression test for AGENTIC_LOOP_AUDIT A1 (renamed to PROVIDERS A1).
     * Before the fix, Anthropic `input_json_delta` chunks were emitted with
     * id="" and name="" — the Brain then had to fall back to "last seen id"
     * which mis-routed deltas when the model emitted parallel tool calls.
     *
     * The fix tracks `index → id` in the provider and emits deltas with
     * the tool id filled in, so the Brain routes by id directly. This test
     * pins the single-tool-call case (index 0) to lock the contract.
     */
    @Test
    fun `chat emits tool call with id filled in for input_json_delta`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    event: message_start
                    data: {"type":"message_start","message":{"id":"msg_1","role":"assistant","content":[],"model":"claude-3"}}

                    event: content_block_start
                    data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                    event: content_block_start
                    data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_01","name":"search","input":{}}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"query\":"}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\"hello\"}"}}

                    event: content_block_stop
                    data: {"type":"content_block_stop","index":1}

                    event: message_delta
                    data: {"type":"message_delta","delta":{"stop_reason":"tool_use"}}

                    event: message_stop
                    data: {"type":"message_stop"}

                    """.trimIndent(),
                )
                .setHeader("Content-Type", "text/event-stream"),
        )
        val chunks = mutableListOf<ProviderChunk>()
        provider.chat(
            model = "claude-test",
            messages = listOf(ProviderMessage(ProviderMessage.Role.user, "hi")),
        ).collect { chunks += it }

        val toolCalls = chunks.mapNotNull { it.toolCall }
        // The first toolCall chunk is the start (id+name, no args).
        // All subsequent deltas must carry id="toolu_01" (NOT "").
        assertTrue(toolCalls.isNotEmpty(), "expected at least one toolCall chunk")
        assertEquals("toolu_01", toolCalls.first().id)
        assertEquals("search", toolCalls.first().name)
        for (delta in toolCalls.drop(1)) {
            assertEquals("toolu_01", delta.id, "input_json_delta must carry tool id (was \"\" before the fix)")
        }
        // The accumulated args equal the joined partial_json.
        val accumulatedArgs = toolCalls.drop(1).joinToString(separator = "") { it.arguments }
        assertEquals("""{"query":"hello"}""", accumulatedArgs)
        // Final chunk: finish reason = tool_calls (from message_delta's
        // stop_reason, NOT from message_stop which is a no-op since the
        // audit fix — the message_stop finish would have overwritten
        // this with `stop`, causing the loop to skip tool execution).
        val finishReasons = chunks.mapNotNull { it.finishReason }
        assertTrue(finishReasons.isNotEmpty(), "expected a finish reason")
        assertEquals(FinishReason.tool_calls, finishReasons.last())
    }

    /**
     * Regression test for PROVIDERS_AUDIT C1 — base OkHttpClient
     * follows redirects, opening an SSRF window via 3xx responses
     * to internal IPs (e.g. 169.254.169.254 cloud metadata).
     *
     * The ProviderModule's base client now disables both redirect
     * types. This test pins that contract: a 302 response to a
     * metadata endpoint must NOT be followed. The request returns
     * 302, not 200 from the redirect target.
     */
    /**
     * Regression test for PROVIDERS_AUDIT C1 — base OkHttpClient
     * follows redirects, opening an SSRF window via 3xx responses
     * to internal IPs (e.g. 169.254.169.254 cloud metadata).
     *
     * The ProviderModule's base client now disables both redirect
     * types. This test pins that contract: a 302 response must NOT
     * be followed. We enqueue two responses on the MockWebServer —
     * a 302 to /after-redirect, then a 200. If the client follows
     * the redirect, the 200 is consumed and the chat call sees a
     * successful empty SSE body (which produces 0 tool/text chunks
     * and no error). If the redirect is blocked, OkHttp returns the
     * 302 directly — AnthropicProvider treats 3xx as `isSuccessful`
     * (OkHttp definition), so the SSE body is read and is empty,
     * which ALSO produces 0 chunks. The discriminator: the request
     * count. With redirects blocked, exactly 1 request is made
     * (the 302). With redirects followed, exactly 2 requests are
     * made (302 + the redirected /after-redirect).
     */
    @Test
    fun `base OkHttpClient does not follow redirects`() = runBlocking<Unit> {
        // Enqueue a 302 redirect to a path on the same MockWebServer
        // and a 200 that the redirect target would return. If the
        // client follows the redirect, both responses are consumed.
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "/v1/messages?after-redirect"),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"),
        )
        val chunks = mutableListOf<ProviderChunk>()
        provider.chat(
            model = "claude-test",
            messages = listOf(ProviderMessage(ProviderMessage.Role.user, "hi")),
        ).collect { chunks += it }
        // Stronger assertion: the queued 200 was NOT consumed. If
        // the client followed the redirect, the 200 would be the
        // response and requestCount would be 2.
        val requestCount = server.requestCount
        assertEquals(
            1, requestCount,
            "expected exactly 1 request (the 302) — the redirect must not be followed. Got $requestCount requests.",
        )
    }

    /**
     * Regression test for PROVIDERS_AUDIT A2 — the audit's "parallel
     * tool calls can't be associated" finding.
     *
     * Before the fix, two parallel `tool_use` blocks had their
     * `input_json_delta` chunks both routed to the Brain's "last seen
     * id" — meaning tc1's deltas went to tc2 and vice versa. The fix
     * tracks `index → id` so each delta carries the right id.
     */
    @Test
    fun `chat routes input_json_delta by SSE index for parallel tool calls`() = runBlocking<Unit> {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    event: content_block_start
                    data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_alpha","name":"search","input":{}}}

                    event: content_block_start
                    data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_beta","name":"recall","input":{}}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"q\":\"A\"}"}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"q\":\"B\"}"}}

                    event: content_block_stop
                    data: {"type":"content_block_stop","index":0}

                    event: content_block_stop
                    data: {"type":"content_block_stop","index":1}

                    event: message_delta
                    data: {"type":"message_delta","delta":{"stop_reason":"tool_use"}}

                    event: message_stop
                    data: {"type":"message_stop"}

                    """.trimIndent(),
                )
                .setHeader("Content-Type", "text/event-stream"),
        )
        val chunks = mutableListOf<ProviderChunk>()
        provider.chat(
            model = "claude-test",
            messages = listOf(ProviderMessage(ProviderMessage.Role.user, "hi")),
        ).collect { chunks += it }

        // The two starts must come first (any order).
        val starts = chunks.filter { it.toolCall?.name?.isNotEmpty() == true }
        val startNames = starts.map { it.toolCall!!.name }.toSet()
        assertEquals(setOf("search", "recall"), startNames)

        // After the starts, deltas must be tagged with the right id
        // by index, NOT by "last seen" — index 0 deltas → toolu_alpha,
        // index 1 deltas → toolu_beta. Before the fix, all deltas
        // would be tagged "toolu_beta" (the last-seen key).
        val deltas = chunks.filter { it.toolCall?.id?.isNotEmpty() == true && it.toolCall?.name?.isEmpty() == true }
        assertTrue(deltas.isNotEmpty(), "expected delta chunks")
        for (delta in deltas) {
            val id = delta.toolCall!!.id
            // The id must be one of the two parallel tool ids.
            assertTrue(
                id == "toolu_alpha" || id == "toolu_beta",
                "delta id must be a real tool id, got '$id'",
            )
        }
        // Stronger assertion: the deltas must include BOTH ids.
        val deltaIds = deltas.map { it.toolCall!!.id }.toSet()
        assertEquals(
            setOf("toolu_alpha", "toolu_beta"),
            deltaIds,
            "parallel tool deltas must cover both ids — pre-fix this would be {toolu_beta} only",
        )
    }
}
