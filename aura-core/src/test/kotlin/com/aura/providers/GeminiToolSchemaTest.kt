package com.aura.providers

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.aura.testing.networkTestTimeout
import org.junit.Rule
import org.junit.rules.Timeout

/**
 * Wire-format tests for Gemini's `functionDeclarations[].parameters`.
 *
 * Gemini used to render tool schemas with its own inline builder that emitted
 * only `type`, `description` and `required`, so `enum` was dropped from every
 * tool schema before it reached the wire. Nothing was visibly broken, because
 * the only tool carrying an enum is `tavily_search` and `filterSearchTools`
 * removes it from every request — but the next enum on a live tool would have
 * vanished the same way, silently, and a model with no enum invents values.
 *
 * These tests pin the two halves of the fix: the shared renderer's output now
 * survives to the wire, and [sanitizeForGemini]'s adaptations (unsupported
 * keywords stripped, unknown types coerced) survive with it.
 */
class GeminiToolSchemaTest {

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

    private fun provider() = GeminiProvider(
        providerKeys = mockk {
            coEvery { keyForAwaiting("gemini") } returns "test-key"
            every { isConfigured("gemini") } returns true
        },
        httpClient = OkHttpClient(),
        baseUrl = server.url("/").toString().removeSuffix("/"),
    )

    /** Gemini streams NDJSON, not SSE. One finished candidate is enough. */
    private fun enqueueOkStream() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """[{"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}]}]""",
                ),
        )
    }

    private fun takeRequestBody(): JsonObject {
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recorded, "provider never sent a request")
        return Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
    }

    /** Drive one chat turn and return `name -> parameters` for each declared tool. */
    private fun sentFunctionParameters(tools: List<ToolDefinition>): Map<String, JsonObject?> {
        enqueueOkStream()
        runBlocking {
            withTimeout(10_000L) {
                provider().chat(
                    "gemini-2.5-flash",
                    listOf(ProviderMessage(ProviderMessage.Role.user, "hey")),
                    ChatOptions(),
                    tools,
                ).toList()
            }
        }
        val declarations = takeRequestBody()["tools"]!!
            .jsonObject["functionDeclarations"]!!.jsonArray
        return declarations.associate { entry ->
            val fn = entry.jsonObject
            fn["name"]!!.jsonPrimitive.content to fn["parameters"]?.jsonObject
        }
    }

    /** Mirrors `tavily_search` — the one real tool that declares an enum. */
    private fun enumToolDefinition() = ToolDefinition(
        name = "search_with_depth",
        description = "Search the web.",
        parameters = ToolParameters(
            properties = mapOf(
                "query" to ToolProperty(type = "string", description = "The query"),
                "search_depth" to ToolProperty(
                    type = "string",
                    description = "Search depth",
                    enum = listOf("basic", "advanced"),
                ),
                "max_results" to ToolProperty(type = "integer", description = "How many"),
            ),
            required = listOf("query"),
        ),
    )

    // ---- the regression this fix exists for -------------------------------

    @Test
    fun `enum survives to the wire`() {
        val params = sentFunctionParameters(listOf(enumToolDefinition()))["search_with_depth"]
        assertNotNull(params, "tool with properties must declare parameters")

        val depth = params["properties"]!!.jsonObject["search_depth"]!!.jsonObject
        val enum = depth["enum"]?.jsonArray
        assertNotNull(enum, "enum was dropped from the Gemini tool schema")
        assertEquals(listOf("basic", "advanced"), enum.map { it.jsonPrimitive.content })
    }

    @Test
    fun `default survives to the wire`() {
        val withDefault = ToolDefinition(
            name = "paged",
            description = "Paged reader.",
            parameters = ToolParameters(
                properties = mapOf(
                    "page" to ToolProperty(
                        type = "integer",
                        description = "Page number",
                        defaultValue = kotlinx.serialization.json.JsonPrimitive(1),
                    ),
                ),
            ),
        )
        val params = sentFunctionParameters(listOf(withDefault))["paged"]
        val page = params!!["properties"]!!.jsonObject["page"]!!.jsonObject
        assertEquals(1, page["default"]?.jsonPrimitive?.content?.toInt())
    }

    // ---- shape the old renderer got right, and must keep getting right ----

    @Test
    fun `schema keeps type object, descriptions and required`() {
        val params = sentFunctionParameters(listOf(enumToolDefinition()))["search_with_depth"]!!
        assertEquals("object", params["type"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("query"),
            params["required"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        val props = params["properties"]!!.jsonObject
        assertEquals("integer", props["max_results"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "The query",
            props["query"]!!.jsonObject["description"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `a no-argument tool omits parameters entirely`() {
        // toJsonSchema always emits `properties`, even empty; Gemini rejects an
        // empty properties object, so the isNotEmpty guard must survive the swap.
        val noArgs = ToolDefinition(
            name = "battery_state",
            description = "Read the battery level.",
            parameters = ToolParameters(),
        )
        val sent = sentFunctionParameters(listOf(noArgs))
        assertTrue("battery_state" in sent, "tool was not declared at all")
        assertEquals(null, sent["battery_state"], "no-arg tool must omit `parameters`")
    }

    // ---- sanitizer behaviour, tested directly ----------------------------

    @Test
    fun `sanitizer strips keywords Gemini rejects`() {
        val hostile = buildJsonObject {
            put("\$schema", "https://json-schema.org/draft/2020-12/schema")
            put("type", "object")
            put("additionalProperties", false)
            put("properties", buildJsonObject {
                put("nested", buildJsonObject {
                    put("type", "string")
                    put("const", "fixed")
                    put("\$ref", "#/\$defs/thing")
                })
            })
        }
        val clean = sanitizeForGemini(hostile)

        assertFalse("\$schema" in clean.keys)
        assertFalse("additionalProperties" in clean.keys)
        val nested = clean["properties"]!!.jsonObject["nested"]!!.jsonObject
        assertFalse("const" in nested.keys)
        assertFalse("\$ref" in nested.keys)
        assertEquals("string", nested["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sanitizer coerces unknown types to string`() {
        // The safety net inherited from the old renderer: no ToolProperty uses an
        // exotic type today, so this is the only thing asserting it still works.
        val exotic = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("weird", buildJsonObject { put("type", "geo-point") })
                put("fine", buildJsonObject { put("type", "boolean") })
            })
        }
        val props = sanitizeForGemini(exotic)["properties"]!!.jsonObject
        assertEquals("string", props["weird"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("boolean", props["fine"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sanitizer recurses into array items`() {
        val arraySchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("tags", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject {
                        put("type", "string")
                        put("additionalProperties", false)
                    })
                })
            })
        }
        val items = sanitizeForGemini(arraySchema)["properties"]!!
            .jsonObject["tags"]!!.jsonObject["items"]!!.jsonObject
        assertFalse("additionalProperties" in items.keys)
        assertEquals("string", items["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a property named like a keyword is not stripped`() {
        // `properties` keys are user-chosen parameter names. A tool with a
        // parameter called `const` must keep it; only schema *keywords* go.
        val schema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("const", buildJsonObject { put("type", "string") })
                put("\$ref", buildJsonObject { put("type", "string") })
            })
        }
        val props = sanitizeForGemini(schema)["properties"]!!.jsonObject
        assertTrue("const" in props.keys, "parameter named `const` was stripped as a keyword")
        assertTrue("\$ref" in props.keys, "parameter named `\$ref` was stripped as a keyword")
    }
}
