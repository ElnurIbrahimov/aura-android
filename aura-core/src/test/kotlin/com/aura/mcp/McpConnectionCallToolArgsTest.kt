package com.aura.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Regression tests for the callTool argument serialization fix.
 *
 * The previous implementation used a `when` chain that only handled
 * String/Number/Boolean/Map — Lists, Arrays, and nulls were silently
 * dropped, so MCP tools expecting list arguments received `{}`.
 *
 * These tests drive a real McpConnection against a MockWebServer and
 * assert the JSON-RPC request body that actually leaves the device.
 */
class McpConnectionCallToolArgsTest {

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

    private fun connection(): McpConnection {
        val config = McpServerConfig(
            id = "test_server",
            name = "Test Server",
            url = server.url("/mcp").toString(),
        )
        return McpConnection(config, OkHttpClient())
    }

    private fun mockToolResponse() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "jsonrpc": "2.0",
                      "id": "1",
                      "result": {
                        "content": [{"type": "text", "text": "ok"}],
                        "isError": false
                      }
                    }
                    """.trimIndent()
                )
        )
    }

    @Test
    fun `list arguments are serialized as JSON arrays`() = runBlocking {
        mockToolResponse()
        val conn = connection()

        conn.callTool(
            toolName = "search",
            arguments = mapOf("tags" to listOf("a", "b", "c")),
            timeoutMs = 5_000L,
        )

        val request = server.takeRequest()
        val body = request.body.readUtf8()
        val args = Json.parseToJsonElement(body).jsonObject["params"]!!
            .jsonObject["arguments"]!!.jsonObject

        assertTrue("tags should be a JSON array", args["tags"] is kotlinx.serialization.json.JsonArray)
        val tags = args["tags"]!!.jsonArray
        assertEquals(3, tags.size)
        assertEquals("a", tags[0].jsonPrimitive.content)
        assertEquals("b", tags[1].jsonPrimitive.content)
        assertEquals("c", tags[2].jsonPrimitive.content)
    }

    @Test
    fun `null arguments are serialized as JSON null not dropped`() = runBlocking {
        mockToolResponse()
        val conn = connection()

        conn.callTool(
            toolName = "insert",
            arguments = mapOf("name" to "x", "description" to null),
            timeoutMs = 5_000L,
        )

        val request = server.takeRequest()
        val body = request.body.readUtf8()
        val args = Json.parseToJsonElement(body).jsonObject["params"]!!
            .jsonObject["arguments"]!!.jsonObject

        assertNotNull("null value must be present as key", args["description"])
        assertEquals(
            kotlinx.serialization.json.JsonNull,
            args["description"],
        )
    }

    @Test
    fun `nested maps and lists serialize recursively`() = runBlocking {
        mockToolResponse()
        val conn = connection()

        conn.callTool(
            toolName = "complex",
            arguments = mapOf(
                "filters" to mapOf(
                    "status" to "active",
                    "ids" to listOf(1, 2, 3),
                ),
                "limit" to 10,
            ),
            timeoutMs = 5_000L,
        )

        val request = server.takeRequest()
        val body = request.body.readUtf8()
        val args = Json.parseToJsonElement(body).jsonObject["params"]!!
            .jsonObject["arguments"]!!.jsonObject

        val filters = args["filters"]!!.jsonObject
        assertEquals("active", filters["status"]!!.jsonPrimitive.content)
        val ids = filters["ids"]!!.jsonArray
        assertEquals(3, ids.size)
        assertEquals(1, ids[0].jsonPrimitive.content.toInt())
        assertEquals(3, ids[2].jsonPrimitive.content.toInt())
        assertEquals(10, args["limit"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `integer and boolean arguments are preserved`() = runBlocking {
        mockToolResponse()
        val conn = connection()

        conn.callTool(
            toolName = "update",
            arguments = mapOf(
                "count" to 42,
                "enabled" to true,
                "ratio" to 1.5,
            ),
            timeoutMs = 5_000L,
        )

        val request = server.takeRequest()
        val body = request.body.readUtf8()
        val args = Json.parseToJsonElement(body).jsonObject["params"]!!
            .jsonObject["arguments"]!!.jsonObject

        assertEquals("42", args["count"]!!.jsonPrimitive.content)
        assertEquals("true", args["enabled"]!!.jsonPrimitive.content)
        assertEquals("1.5", args["ratio"]!!.jsonPrimitive.content)
    }

    @Test
    fun `empty arguments map produces empty object`() = runBlocking {
        mockToolResponse()
        val conn = connection()

        conn.callTool(
            toolName = "ping",
            arguments = emptyMap(),
            timeoutMs = 5_000L,
        )

        val request = server.takeRequest()
        val body = request.body.readUtf8()
        val args = Json.parseToJsonElement(body).jsonObject["params"]!!
            .jsonObject["arguments"]!!.jsonObject

        assertEquals(0, args.size)
    }
}
