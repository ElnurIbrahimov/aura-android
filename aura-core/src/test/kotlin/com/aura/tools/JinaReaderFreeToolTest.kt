package com.aura.tools

import com.aura.agent.ToolCall
import com.aura.agent.ToolContext
import com.aura.agent.ToolResult
import com.aura.providers.ProviderKeys
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [JinaReaderFreeTool] (`read_url`).
 *
 * Regression context: the tool SSRF-validates the USER's url but sends
 * the actual request to the r.jina.ai proxy. The old implementation used
 * a client DNS-pinned to the USER's host, so resolving the proxy host
 * threw "unexpected redirect host" and every single call failed. These
 * tests drive the tool against a MockWebServer standing in for
 * r.jina.ai and prove the request reaches the proxy (not the user's
 * host) and succeeds.
 */
class JinaReaderFreeToolTest {

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

    private fun tool(providerKeys: ProviderKeys = keys(null)): JinaReaderFreeTool =
        JinaReaderFreeTool(OkHttpClient(), providerKeys).also {
            it.readerBaseUrl = server.url("/").toString()
        }

    private fun keys(jinaKey: String?): ProviderKeys = mockk {
        every { keyFor("jina") } returns jinaKey
    }

    private fun call(vararg pairs: Pair<String, Any?>): ToolCall =
        ToolCall(id = "tc1", name = "read_url", arguments = mapOf(*pairs))

    private fun ctx() = ToolContext(conversationId = "conv-1")

    @Test
    fun `request goes to the reader proxy host and succeeds`() = runTest {
        // Public-IP user URL: passes the SSRF guard without a DNS lookup.
        // Pre-fix, the DNS-pinned client refused to resolve the proxy's
        // host and this returned ToolResult.Error("unexpected redirect
        // host"). Now the plain client talks to the proxy directly.
        server.enqueue(MockResponse().setResponseCode(200).setBody("# Article\n\nclean markdown"))

        val result = tool().tool.execute(call("url" to "http://93.184.216.34/article"), ctx())

        assertTrue("expected Ok, got $result") { result is ToolResult.Ok }
        assertEquals("# Article\n\nclean markdown", (result as ToolResult.Ok).output)

        // The HTTP request must hit the PROXY (the MockWebServer), with
        // the user's URL in the path — never the user's host directly.
        val recorded = server.takeRequest()
        assertEquals(server.hostName, recorded.requestUrl!!.host)
        assertTrue("proxy path should embed the user url, got ${recorded.path}") {
            recorded.path!!.contains("93.184.216.34/article")
        }
    }

    @Test
    fun `jina api key is attached when configured`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("content"))

        val result = tool(keys("jina-key-123")).tool.execute(
            call("url" to "http://93.184.216.34/page"),
            ctx(),
        )

        assertTrue("expected Ok, got $result") { result is ToolResult.Ok }
        assertEquals("Bearer jina-key-123", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `SSRF guard still blocks private user URLs before any request`() = runTest {
        val result = tool().tool.execute(call("url" to "http://192.168.1.10/internal"), ctx())

        assertTrue("expected Error, got $result") { result is ToolResult.Error }
        assertEquals("ssrf_guard", (result as ToolResult.Error).code)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `loopback user URL is blocked`() = runTest {
        val result = tool().tool.execute(call("url" to "http://127.0.0.1/secrets"), ctx())

        assertTrue("expected Error, got $result") { result is ToolResult.Error }
        assertEquals("ssrf_guard", (result as ToolResult.Error).code)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `proxy HTTP error is surfaced as http_error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("rate limited"))

        val result = tool().tool.execute(call("url" to "http://93.184.216.34/article"), ctx())

        assertTrue("expected Error, got $result") { result is ToolResult.Error }
        assertEquals("http_error", (result as ToolResult.Error).code)
    }
}
