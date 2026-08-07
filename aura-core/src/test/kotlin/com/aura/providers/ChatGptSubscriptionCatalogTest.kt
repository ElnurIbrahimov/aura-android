package com.aura.providers

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
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

    private fun provider(token: String? = "sess-token") = ChatGptSubscriptionProvider(
        providerKeys = keys(),
        httpClient = OkHttpClient(),
        tokenStore = chatGptTokenStore(token),
        oauthFlow = chatGptOAuthFlow(),
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

    /**
     * Send a chat and hand back the request body the server saw.
     *
     * Every assertion below was established by probing the live endpoint one
     * field at a time with a real subscription token — not read off a doc.
     * Each rejected field produced a 400 that killed the message while the
     * model picker looked perfectly healthy, so they are worth pinning.
     */
    private fun chatBody(options: ChatOptions = ChatOptions(), tools: List<ToolDefinition> = emptyList()): String {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"response.completed\"}\n\n"),
        )
        runBlocking {
            provider().chat(
                "gpt-5.6-sol",
                listOf(ProviderMessage(role = ProviderMessage.Role.user, content = "hi")),
                options,
                tools,
            ).toList()
        }
        return server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!.body.readUtf8()
    }

    @Test
    fun `sampling parameters the backend rejects are not sent`() {
        val body = chatBody(ChatOptions(temperature = 0.7, topP = 0.9, maxTokens = 256))

        // Live endpoint: 400 "Unsupported parameter: <name>" for each.
        // Sampling is the server's decision on a subscription.
        assertTrue("temperature" !in body, "temperature is rejected: $body")
        assertTrue("top_p" !in body, "top_p is rejected: $body")
        assertTrue("max_tokens" !in body, "max_tokens is rejected: $body")
    }

    @Test
    fun `reasoning effort is nested, not top-level`() {
        val body = chatBody(ChatOptions(thinkingBudget = 24_000))

        // Top-level reasoning_effort → 400. reasoning:{effort} → 200.
        assertTrue("\"reasoning\":{\"effort\":\"high\"}" in body, "got $body")
        assertTrue("\"reasoning_effort\"" !in body, "top-level form is rejected: $body")
    }

    @Test
    fun `tools use the Responses shape, with the name at the top level`() {
        val body = chatBody(
            tools = listOf(
                ToolDefinition(
                    name = "web_search",
                    description = "search the web",
                    parameters = ToolParameters(
                        properties = mapOf("q" to ToolProperty(type = "string", description = "query")),
                        required = listOf("q"),
                    ),
                ),
            ),
        )

        // Chat-Completions nesting → 400 "Missing required parameter:
        // 'tools[0].name'". Aura always declares tools, so this failed
        // every single message.
        assertTrue("\"name\":\"web_search\"" in body, "got $body")
        assertTrue("\"function\":{" !in body, "must not nest under 'function': $body")
        // The omission that caused the original DeepSeek 400.
        assertTrue("\"type\":\"object\"" in body, "parameters schema needs its type: $body")
    }

    /** Send with a given history and hand back the request body. */
    private fun bodyForHistory(messages: List<ProviderMessage>): String {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"response.completed\"}\n\n"),
        )
        runBlocking { provider().chat("gpt-5.6-sol", messages, ChatOptions(), emptyList()).toList() }
        return server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!.body.readUtf8()
    }

    @Test
    fun `a tool call with no result is dropped from the history`() {
        // Live endpoint: 400 "No tool output found for function call <id>".
        // A half-pair happens for ordinary reasons — stop pressed mid-tool,
        // app killed — and replaying it verbatim rejects every later message
        // in that conversation, permanently.
        val body = bodyForHistory(
            listOf(
                ProviderMessage(role = ProviderMessage.Role.user, content = "search please"),
                ProviderMessage(
                    role = ProviderMessage.Role.assistant,
                    content = "I'll check the docs.",
                    toolCalls = listOf(ToolCall(id = "call_orphan", name = "web_search", arguments = "{}")),
                ),
                ProviderMessage(role = ProviderMessage.Role.user, content = "and?"),
            ),
        )

        assertTrue("call_orphan" !in body, "unanswered call must not be replayed: $body")
        // The assistant's words are still worth keeping.
        assertTrue("I'll check the docs." in body, "assistant text should survive: $body")
    }

    @Test
    fun `a tool result with no matching call is dropped from the history`() {
        // The mirror case: 400 "No tool call found for function call output".
        val body = bodyForHistory(
            listOf(
                ProviderMessage(role = ProviderMessage.Role.user, content = "hi"),
                ProviderMessage(role = ProviderMessage.Role.tool, content = "stale", toolCallId = "call_gone"),
            ),
        )

        assertTrue("call_gone" !in body, "unmatched output must not be replayed: $body")
    }

    @Test
    fun `a complete tool exchange is replayed intact`() {
        val body = bodyForHistory(
            listOf(
                ProviderMessage(role = ProviderMessage.Role.user, content = "search please"),
                ProviderMessage(
                    role = ProviderMessage.Role.assistant,
                    content = "",
                    toolCalls = listOf(ToolCall(id = "call_ok", name = "web_search", arguments = """{"q":"x"}""")),
                ),
                ProviderMessage(role = ProviderMessage.Role.tool, content = "found it", toolCallId = "call_ok"),
            ),
        )

        assertTrue("\"type\":\"function_call\"" in body, "got $body")
        assertTrue("\"type\":\"function_call_output\"" in body, "got $body")
        assertTrue("found it" in body, "got $body")
    }

    @Test
    fun `every chat request opts out of storage`() = runBlocking {
        // The subscription backend answers 400 "store must be set to false"
        // when the field is absent, because the API default is true and it
        // will not persist responses for this client. Omitting it made every
        // message fail while the model list looked perfectly healthy.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"response.completed\"}\n\n"),
        )

        provider().chat(
            "gpt-5.6-sol",
            listOf(ProviderMessage(role = ProviderMessage.Role.user, content = "hi")),
            ChatOptions(),
            emptyList(),
        ).toList()

        val body = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!.body.readUtf8()
        assertTrue("\"store\":false" in body, "chat body must send store=false, got $body")
    }

    @Test
    fun `no token means no catalog request at all`() = runBlocking {
        val signedOut = ChatGptSubscriptionProvider(
            providerKeys = mockk {
                coEvery { keyForAwaiting("chatgpt") } returns null
                every { keyFor("chatgpt") } returns null
            },
            httpClient = OkHttpClient(),
            tokenStore = chatGptTokenStore(null),
            oauthFlow = chatGptOAuthFlow(),
            baseUrl = server.url("/").toString().removeSuffix("/"),
        )

        val models = signedOut.listModels()

        assertTrue(models.isEmpty())
        assertEquals(0, server.requestCount)
    }
}
