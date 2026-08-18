package com.aura.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.aura.testing.networkTestTimeout
import org.junit.Rule
import org.junit.rules.Timeout

/**
 * MCP Streamable HTTP protocol-compliance tests for [McpConnection]:
 *
 *  - the `Mcp-Session-Id` assigned at initialize is echoed on every
 *    subsequent request
 *  - `notifications/initialized` is sent after a successful initialize
 *  - a response framed as `text/event-stream` is parsed instead of being
 *    treated as garbage
 *  - a JSON-RPC `error` response to initialize must NOT mark the
 *    connection CONNECTED
 *  - a response whose `id` doesn't match the request's id is rejected
 *
 * All five were broken/missing: real Streamable HTTP servers assign
 * session ids and frame responses as SSE, so the client connected but
 * every follow-up call failed.
 */
class McpConnectionProtocolTest {

    /** See [networkTestTimeout] — uniform, not judged per class. */
    @get:Rule
    val globalTimeout: Timeout = networkTestTimeout()

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

    private fun connection(): McpConnection = McpConnection(
        McpServerConfig(id = "srv", name = "Test", url = server.url("/mcp").toString()),
        OkHttpClient(),
    )

    private fun requestId(request: RecordedRequest): String? = runCatching {
        Json.parseToJsonElement(request.body.clone().readUtf8())
            .jsonObject["id"]?.jsonPrimitive?.content
    }.getOrNull()

    private fun requestMethod(request: RecordedRequest): String? = runCatching {
        Json.parseToJsonElement(request.body.clone().readUtf8())
            .jsonObject["method"]?.jsonPrimitive?.content
    }.getOrNull()

    /** Dispatcher speaking well-formed JSON-RPC, echoing ids, assigning [sessionId]. */
    private fun jsonRpcDispatcher(sessionId: String? = null, sseFramed: Boolean = false) =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.clone().readUtf8()
                val parsed = Json.parseToJsonElement(body).jsonObject
                val method = parsed["method"]?.jsonPrimitive?.content
                val id = parsed["id"]?.jsonPrimitive?.content
                if (id == null) {
                    // Notification — 202 Accepted, no body.
                    return MockResponse().setResponseCode(202)
                }
                val resultJson = when (method) {
                    "initialize" -> """{"protocolVersion":"2025-03-26","capabilities":{},"serverInfo":{"name":"mock","version":"1.0"}}"""
                    "tools/list" -> """{"tools":[{"name":"echo","description":"echoes","inputSchema":{}}]}"""
                    "tools/call" -> """{"content":[{"type":"text","text":"tool output"}],"isError":false}"""
                    else -> "{}"
                }
                val message = """{"jsonrpc":"2.0","id":"$id","result":$resultJson}"""
                val response = MockResponse()
                sessionId?.let { response.setHeader("Mcp-Session-Id", it) }
                return if (sseFramed) {
                    response
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody("event: message\ndata: $message\n\n")
                } else {
                    response
                        .setHeader("Content-Type", "application/json")
                        .setBody(message)
                }
            }
        }

    @Test
    fun `session id from initialize is echoed on subsequent requests`() = runBlocking {
        server.dispatcher = jsonRpcDispatcher(sessionId = "session-abc-123")
        val conn = connection()

        val health = conn.initialize()
        assertEquals(McpConnectionState.CONNECTED, health.state)
        assertEquals("session-abc-123", conn.currentSessionId())

        conn.listTools()

        // Request order: initialize, notifications/initialized, tools/list.
        val initRequest = server.takeRequest()
        assertNull("initialize is sent before any session id exists", initRequest.getHeader("Mcp-Session-Id"))
        val notification = server.takeRequest()
        assertEquals("session-abc-123", notification.getHeader("Mcp-Session-Id"))
        val toolsRequest = server.takeRequest()
        assertEquals("session-abc-123", toolsRequest.getHeader("Mcp-Session-Id"))
        assertEquals("tools/list", requestMethod(toolsRequest))
    }

    @Test
    fun `notifications initialized is sent after a successful initialize`() = runBlocking {
        server.dispatcher = jsonRpcDispatcher()
        val conn = connection()

        conn.initialize()

        server.takeRequest() // initialize
        val notification = server.takeRequest()
        assertEquals("notifications/initialized", requestMethod(notification))
        assertNull("notifications carry no id", requestId(notification))
    }

    @Test
    fun `SSE-framed responses are parsed`() = runBlocking {
        server.dispatcher = jsonRpcDispatcher(sseFramed = true)
        val conn = connection()

        val health = conn.initialize()
        assertEquals(
            "an SSE-framed initialize response must still connect",
            McpConnectionState.CONNECTED,
            health.state,
        )

        val result = conn.callTool("echo", mapOf("text" to "hi"), timeoutMs = 5_000L)
        assertTrue("expected Success, got $result", result is McpToolResult.Success)
        assertEquals("tool output", (result as McpToolResult.Success).output)
    }

    @Test
    fun `initialize with a JSON-RPC error response does NOT mark CONNECTED`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val id = Json.parseToJsonElement(request.body.clone().readUtf8())
                    .jsonObject["id"]?.jsonPrimitive?.content ?: "0"
                return MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"jsonrpc":"2.0","id":"$id","error":{"code":-32602,"message":"Unsupported protocol version"}}""")
            }
        }
        val conn = connection()

        val health = conn.initialize()

        assertNotEquals(McpConnectionState.CONNECTED, health.state)
        assertEquals(McpConnectionState.ERROR, health.state)
        assertTrue(
            "error message should surface, got '${health.lastError}'",
            health.lastError.contains("Unsupported protocol version"),
        )
    }

    @Test
    fun `response with a mismatched JSON-RPC id is rejected`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"jsonrpc":"2.0","id":"some-other-request","result":{"content":[{"type":"text","text":"stale"}]}}""")
        }
        val conn = connection()

        val result = conn.callTool("echo", emptyMap(), timeoutMs = 5_000L)

        assertTrue(
            "a mismatched id must be treated as no-response, got $result",
            result is McpToolResult.Failure,
        )
        assertEquals("no_response", (result as McpToolResult.Failure).code)
    }

    @Test
    fun `initialize with a mismatched id does not connect`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"jsonrpc":"2.0","id":"bogus","result":{"protocolVersion":"2025-03-26"}}""")
        }
        val conn = connection()

        val health = conn.initialize()

        assertNotEquals(McpConnectionState.CONNECTED, health.state)
    }
}
