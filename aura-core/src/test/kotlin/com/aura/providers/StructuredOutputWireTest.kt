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
 * Wire-format tests for [ChatOptions.responseSchema] and [ChatOptions.responseFormat].
 *
 * Both fields predate this: `responseFormat` shipped for a long time with
 * exactly one caller setting it and no provider reading it, so the JSON came
 * back — or didn't — purely on the strength of the prompt.
 *
 * The absent case is asserted as carefully as the present one. `response_format`
 * is not universally supported across the twelve prefixes riding
 * [OpenAiCompatProvider], and a strict endpoint 400s on keys it does not know,
 * so a plain-text request must stay byte-identical to what shipped before. Same
 * idiom as `AnthropicThinkingBudgetContractTest`'s omission assertions.
 */
class StructuredOutputWireTest {

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

    private fun keys(prefix: String): ProviderKeys = mockk {
        coEvery { keyForAwaiting(prefix) } returns "test-key"
        every { isConfigured(prefix) } returns true
    }

    private fun takeRequestBody(): JsonObject {
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recorded, "provider never sent a request")
        return Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
    }

    private val personSchema = ResponseSchema(
        name = "extract_person",
        schema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("name", buildJsonObject { put("type", "string") })
            })
        },
    )

    private fun sampleTool() = ToolDefinition(
        name = "web_search",
        description = "Search the web.",
        parameters = ToolParameters(
            properties = mapOf("q" to ToolProperty(type = "string", description = "query")),
        ),
    )

    // ---- OpenAI-compatible (covers 12 of 17 prefixes) ---------------------

    private fun openAiBody(options: ChatOptions, tools: List<ToolDefinition> = emptyList()): JsonObject {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"),
        )
        val provider = OpenAiCompatProvider(
            prefix = "test",
            displayName = "Test",
            baseUrl = server.url("/").toString().removeSuffix("/"),
            providerKeys = keys("test"),
            httpClient = OkHttpClient(),
        )
        runBlocking {
            withTimeout(10_000L) {
                provider.chat(
                    "test-model",
                    listOf(ProviderMessage(ProviderMessage.Role.user, "hey")),
                    options,
                    tools,
                ).toList()
            }
        }
        return takeRequestBody()
    }

    @Test
    fun `openai omits response_format for plain text`() {
        assertFalse(
            "response_format" in openAiBody(ChatOptions()).keys,
            "a text request must stay byte-identical to what shipped before",
        )
    }

    @Test
    fun `openai sends json_object for ResponseFormat JSON`() {
        val fmt = openAiBody(ChatOptions(responseFormat = ResponseFormat.JSON))["response_format"]!!.jsonObject
        assertEquals("json_object", fmt["type"]!!.jsonPrimitive.content)
        assertFalse("json_schema" in fmt.keys)
    }

    @Test
    fun `openai sends json_schema with name, strict and schema nested`() {
        val fmt = openAiBody(ChatOptions(responseSchema = personSchema))["response_format"]!!.jsonObject
        assertEquals("json_schema", fmt["type"]!!.jsonPrimitive.content)

        // Chat Completions nests these under a `json_schema` key — unlike the
        // Responses API, which flattens them as siblings of `type`.
        val js = fmt["json_schema"]!!.jsonObject
        assertEquals("extract_person", js["name"]!!.jsonPrimitive.content)
        assertEquals(true, js["strict"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("object", js["schema"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `openai schema wins over responseFormat when both are set`() {
        val fmt = openAiBody(
            ChatOptions(responseFormat = ResponseFormat.JSON, responseSchema = personSchema),
        )["response_format"]!!.jsonObject
        assertEquals("json_schema", fmt["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `openai keeps the schema alongside real tools`() {
        // Unlike Anthropic and Gemini, OpenAI has no conflict between
        // response_format and tools — so no gate here.
        val body = openAiBody(ChatOptions(responseSchema = personSchema), listOf(sampleTool()))
        assertTrue("response_format" in body.keys)
        assertEquals(1, body["tools"]!!.jsonArray.size)
    }

    // ---- Anthropic: forced tool_choice -----------------------------------

    private fun anthropicBody(options: ChatOptions, tools: List<ToolDefinition> = emptyList()): JsonObject {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"message_stop\"}\n\n"),
        )
        val provider = AnthropicProvider(
            providerKeys = keys("anthropic"),
            httpClient = OkHttpClient(),
            baseUrl = server.url("/").toString().removeSuffix("/"),
        )
        runBlocking {
            withTimeout(10_000L) {
                provider.chat(
                    "claude-sonnet-4.6",
                    listOf(ProviderMessage(ProviderMessage.Role.user, "hey")),
                    options,
                    tools,
                ).toList()
            }
        }
        return takeRequestBody()
    }

    @Test
    fun `anthropic forces a synthetic tool when a schema is set`() {
        val body = anthropicBody(ChatOptions(responseSchema = personSchema))

        val declared = body["tools"]!!.jsonArray
        assertEquals(1, declared.size)
        assertEquals("extract_person", declared[0].jsonObject["name"]!!.jsonPrimitive.content)

        val choice = body["tool_choice"]!!.jsonObject
        assertEquals("tool", choice["type"]!!.jsonPrimitive.content)
        assertEquals("extract_person", choice["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `anthropic does NOT force a tool when real tools are declared`() {
        // The gate that prevents structured output from destroying tool calling.
        val body = anthropicBody(ChatOptions(responseSchema = personSchema), listOf(sampleTool()))

        assertFalse("tool_choice" in body.keys, "forcing a tool_choice would break real tool calling")
        val declared = body["tools"]!!.jsonArray
        assertEquals(1, declared.size)
        assertEquals("web_search", declared[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `anthropic drops extended thinking when a schema is forced`() {
        // Anthropic rejects forced tool use together with extended thinking.
        // Dropping thinking loses the reasoning trace; keeping it loses the
        // whole answer to a non-retryable 400.
        val body = anthropicBody(
            ChatOptions(responseSchema = personSchema, thinkingBudget = 32_000, maxTokens = 40_000),
        )
        assertFalse("thinking" in body.keys, "thinking must be dropped when tool_choice is forced")
        assertTrue("tool_choice" in body.keys)
    }

    @Test
    fun `anthropic keeps extended thinking when no schema is set`() {
        val body = anthropicBody(ChatOptions(thinkingBudget = 32_000, maxTokens = 40_000))
        assertEquals(32_000, body["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `anthropic sends nothing for bare ResponseFormat JSON`() {
        // Anthropic has no JSON mode. The prompt plus a lenient parse is the
        // whole mechanism, and inventing a key here would 400.
        val body = anthropicBody(ChatOptions(responseFormat = ResponseFormat.JSON))
        assertFalse("tool_choice" in body.keys)
        assertFalse("tools" in body.keys)
        assertFalse("response_format" in body.keys)
    }

    // ---- Gemini ----------------------------------------------------------

    private fun geminiBody(options: ChatOptions, tools: List<ToolDefinition> = emptyList()): JsonObject {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""[{"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}]}]"""),
        )
        val provider = GeminiProvider(
            providerKeys = keys("gemini"),
            httpClient = OkHttpClient(),
            baseUrl = server.url("/").toString().removeSuffix("/"),
        )
        runBlocking {
            withTimeout(10_000L) {
                provider.chat(
                    "gemini-2.5-flash",
                    listOf(ProviderMessage(ProviderMessage.Role.user, "hey")),
                    options,
                    tools,
                ).toList()
            }
        }
        return takeRequestBody()
    }

    @Test
    fun `gemini sends responseMimeType and a sanitized responseSchema`() {
        val hostile = ResponseSchema(
            name = "extract",
            schema = buildJsonObject {
                put("\$schema", "https://json-schema.org/draft/2020-12/schema")
                put("type", "object")
                put("additionalProperties", false)
                put("properties", buildJsonObject {
                    put("name", buildJsonObject { put("type", "string") })
                })
            },
        )
        val cfg = geminiBody(ChatOptions(responseSchema = hostile))["generationConfig"]!!.jsonObject

        assertEquals("application/json", cfg["responseMimeType"]!!.jsonPrimitive.content)
        val schema = cfg["responseSchema"]!!.jsonObject
        assertFalse("\$schema" in schema.keys, "Gemini 400s on \$schema")
        assertFalse("additionalProperties" in schema.keys, "Gemini 400s on additionalProperties")
        assertEquals("object", schema["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `gemini omits responseSchema when tools are declared`() {
        // Gemini rejects responseSchema alongside functionDeclarations.
        val cfg = geminiBody(
            ChatOptions(responseSchema = personSchema),
            listOf(sampleTool()),
        )["generationConfig"]!!.jsonObject

        assertFalse("responseSchema" in cfg.keys)
        assertFalse("responseMimeType" in cfg.keys)
    }

    @Test
    fun `gemini sends only the mime type for bare ResponseFormat JSON`() {
        val cfg = geminiBody(ChatOptions(responseFormat = ResponseFormat.JSON))["generationConfig"]!!.jsonObject
        assertEquals("application/json", cfg["responseMimeType"]!!.jsonPrimitive.content)
        assertFalse("responseSchema" in cfg.keys)
    }

    @Test
    fun `gemini omits both for plain text`() {
        val cfg = geminiBody(ChatOptions())["generationConfig"]!!.jsonObject
        assertFalse("responseMimeType" in cfg.keys)
        assertFalse("responseSchema" in cfg.keys)
    }
}
