package com.aura.providers

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import com.aura.testing.networkTestTimeout
import org.junit.Rule
import org.junit.rules.Timeout

/**
 * Wire-format regression tests for TOOL SCHEMAS (as opposed to
 * ProviderToolHistorySerializationTest, which covers tool *history*).
 *
 * Pre-fix, every tool schema went out missing its `"type": "object"`
 * header: [ToolParameters.type] declares `"object"` as a Kotlin default,
 * and the default `Json` instance has `encodeDefaults = false`, so
 * kotlinx silently omitted the field. Schema-validating providers then
 * rejected the whole request:
 *
 *   HTTP 400 {"error":{"message":"Invalid schema for function
 *   'image_generate': schema must be a JSON Schema of 'type: \"object\"',
 *   got 'type: null'."}}
 *
 * Because tools ride along on EVERY chat request, this broke chat
 * outright on any strict provider (DeepSeek, api.openai.com).
 */
class ToolSchemaSerializationTest {

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

    private fun enqueueOkStream() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"),
        )
    }

    private fun takeRequestBody(): JsonObject {
        val recorded = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
        assertNotNull(recorded, "provider never sent a request")
        return Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
    }

    /** Mirrors the real `image_generate` tool — the one DeepSeek named. */
    private fun imageGenerateDefinition() = ToolDefinition(
        name = "image_generate",
        description = "Generate an image from a text prompt.",
        parameters = ToolParameters(
            properties = mapOf(
                "prompt" to ToolProperty(type = "string", description = "Text description"),
            ),
            required = listOf("prompt"),
        ),
    )

    /** A tool that takes no arguments — `properties` stays at its empty default. */
    private fun noArgDefinition() = ToolDefinition(
        name = "battery_state",
        description = "Read the battery level.",
        parameters = ToolParameters(),
    )

    private fun sentFunctionParameters(tools: List<ToolDefinition>): List<Pair<String, JsonObject>> {
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
                    ChatOptions(),
                    tools,
                ).toList()
            }
        }
        return takeRequestBody()["tools"]!!.jsonArray.map { entry ->
            val fn = entry.jsonObject["function"]!!.jsonObject
            fn["name"]!!.jsonPrimitive.content to fn["parameters"]!!.jsonObject
        }
    }

    @Test
    fun `tool schema carries an explicit type object`() {
        enqueueOkStream()
        val (name, schema) = sentFunctionParameters(listOf(imageGenerateDefinition())).single()

        assertEquals("image_generate", name)
        assertEquals(
            "object",
            schema["type"]?.jsonPrimitive?.content,
            "tool schema must declare type=object — strict providers 400 on 'type: null'",
        )
    }

    @Test
    fun `tool schema always carries a properties object even when empty`() {
        enqueueOkStream()
        val (_, schema) = sentFunctionParameters(listOf(noArgDefinition())).single()

        assertEquals("object", schema["type"]?.jsonPrimitive?.content)
        assertNotNull(
            schema["properties"],
            "a no-arg tool must still send properties:{} — an absent properties is not a valid JSON Schema object",
        )
        assertTrue(schema["properties"]!!.jsonObject.isEmpty())
    }

    @Test
    fun `property descriptions and required list survive serialization`() {
        enqueueOkStream()
        val (_, schema) = sentFunctionParameters(listOf(imageGenerateDefinition())).single()

        val prompt = schema["properties"]!!.jsonObject["prompt"]!!.jsonObject
        assertEquals("string", prompt["type"]!!.jsonPrimitive.content)
        assertEquals("Text description", prompt["description"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("prompt"),
            schema["required"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }
}
