package com.aura.realtime

import android.util.Base64
import com.aura.providers.ProviderKeys
import com.aura.providers.ProviderMessage
import com.aura.providers.ToolDefinition
import com.aura.providers.ToolParameters
import com.aura.providers.ToolProperty
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The realtime wire protocol, end to end, with no device.
 *
 * MockWebServer supports WebSocket upgrades, which makes this the single
 * biggest testability asset in the voice work: the entire protocol layer —
 * session config, audio framing, tool calls, truncation on barge-in — is
 * coverable in CI. The audio hardware is not, and is deliberately kept behind
 * its own interfaces so it does not have to be.
 *
 * Robolectric because `android.util.Base64` is used on both sides of the wire.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class OpenAiRealtimeWireTest {

    private lateinit var server: MockWebServer
    private val received = LinkedBlockingQueue<String>()
    private var serverSocket: WebSocket? = null

    @Before
    fun setUp() {
        server = MockWebServer()
        // Several upgrades queued: a few tests open more than one session, and
        // a MockWebServer with nothing left to serve fails the second connect
        // in a way that looks like a protocol bug rather than a fixture one.
        repeat(UPGRADES) {
            server.enqueue(
                MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                        serverSocket = webSocket
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        received += text
                    }
                }),
            )
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private companion object {
        const val UPGRADES = 6
    }

    private fun keys(): ProviderKeys = mockk {
        coEvery { keyForAwaiting("openai") } returns "test-key"
        every { isConfigured("openai") } returns true
    }

    private fun provider() = OpenAiRealtimeProvider(
        providerKeys = keys(),
        httpClient = OkHttpClient(),
        baseUrl = "ws://${server.hostName}:${server.port}/v1/realtime",
    )

    private fun connect(config: RealtimeConfig = RealtimeConfig(model = "gpt-realtime", instructions = "You are Aura.")) =
        runBlocking { provider().connect(config) }

    private fun nextClientMessage(): JsonObject {
        val text = received.poll(5, TimeUnit.SECONDS)
        assertNotNull(text, "client sent nothing")
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun messagesOfType(type: String, within: Int = 8): JsonObject? {
        repeat(within) {
            val text = received.poll(3, TimeUnit.SECONDS) ?: return null
            val obj = Json.parseToJsonElement(text).jsonObject
            if (obj["type"]?.jsonPrimitive?.content == type) return obj
        }
        return null
    }

    // ---- session setup ---------------------------------------------------

    @Test
    fun `the session is configured on open`() {
        connect()
        val update = nextClientMessage()
        assertEquals("session.update", update["type"]!!.jsonPrimitive.content)

        val session = update["session"]!!.jsonObject
        assertTrue("You are Aura." in session["instructions"]!!.jsonPrimitive.content)
        assertEquals("pcm16", session["input_audio_format"]!!.jsonPrimitive.content)
        assertEquals("pcm16", session["output_audio_format"]!!.jsonPrimitive.content)
        // Server-side VAD: client VAD on Android is a research project, and the
        // server's is tuned and costs nothing.
        assertEquals("server_vad", session["turn_detection"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `seed context is folded into the instructions`() {
        // A realtime session does not run the agentic loop, so there is no
        // per-turn recall. One seeded pass is the whole memory story.
        connect(
            RealtimeConfig(
                model = "gpt-realtime",
                instructions = "You are Aura.",
                seedContext = "The user prefers concise answers.",
            ),
        )
        val instructions = nextClientMessage()["session"]!!.jsonObject["instructions"]!!.jsonPrimitive.content
        assertTrue("You are Aura." in instructions)
        assertTrue("prefers concise answers" in instructions)
    }

    @Test
    fun `tools use the flat Responses-API shape`() {
        connect(
            RealtimeConfig(
                model = "gpt-realtime",
                instructions = "x",
                tools = listOf(
                    ToolDefinition(
                        name = "get_current_time",
                        description = "Local time.",
                        parameters = ToolParameters(
                            properties = mapOf("tz" to ToolProperty(type = "string", description = "zone")),
                        ),
                    ),
                ),
            ),
        )
        val tool = nextClientMessage()["session"]!!.jsonObject["tools"]!!.jsonArray.first().jsonObject
        // name and parameters are SIBLINGS of type, not nested under a
        // "function" object as in Chat Completions.
        assertEquals("function", tool["type"]!!.jsonPrimitive.content)
        assertEquals("get_current_time", tool["name"]!!.jsonPrimitive.content)
        assertTrue("parameters" in tool.keys)
        assertTrue("function" !in tool.keys, "the Chat Completions nesting would 400 here")
    }

    @Test
    fun `no tools key is sent when there are none`() {
        connect()
        assertTrue("tools" !in nextClientMessage()["session"]!!.jsonObject.keys)
    }

    // ---- outbound --------------------------------------------------------

    @Test
    fun `audio is appended as base64 pcm16`() {
        val session = connect()
        nextClientMessage() // session.update
        runBlocking { session.sendAudio(byteArrayOf(1, 2, 3, 4)) }

        val append = messagesOfType("input_audio_buffer.append")
        assertNotNull(append, "audio was never sent")
        val decoded = Base64.decode(append["audio"]!!.jsonPrimitive.content, Base64.DEFAULT)
        assertEquals(listOf<Byte>(1, 2, 3, 4), decoded.toList())
    }

    @Test
    fun `barge-in truncates to what was actually heard`() {
        // The load-bearing detail. The server has generated further than the
        // speaker has played; without truncating to the real playback position
        // the model believes it said sentences the user never received, and
        // every later turn reasons about a conversation that did not happen.
        val session = connect()
        nextClientMessage()
        runBlocking { session.interrupt(playedMs = 1234) }

        val truncate = messagesOfType("conversation.item.truncate")
        assertNotNull(truncate, "no truncation was sent on barge-in")
        assertEquals(1234, truncate["audio_end_ms"]!!.jsonPrimitive.content.toInt())

        assertNotNull(messagesOfType("response.cancel"), "generation was not cancelled")
    }

    @Test
    fun `a negative playback position is clamped rather than sent`() {
        val session = connect()
        nextClientMessage()
        runBlocking { session.interrupt(playedMs = -5) }
        val truncate = messagesOfType("conversation.item.truncate")
        assertEquals(0, truncate!!["audio_end_ms"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `a tool result is sent and triggers a response`() {
        val session = connect()
        nextClientMessage()
        runBlocking { session.sendToolResult("call_1", "12:00") }

        val output = messagesOfType("conversation.item.create")
        assertNotNull(output)
        val item = output["item"]!!.jsonObject
        assertEquals("function_call_output", item["type"]!!.jsonPrimitive.content)
        assertEquals("call_1", item["call_id"]!!.jsonPrimitive.content)
        assertEquals("12:00", item["output"]!!.jsonPrimitive.content)
        assertNotNull(messagesOfType("response.create"), "the model was never asked to continue")
    }

    // ---- inbound ---------------------------------------------------------

    private fun collectEvent(predicate: (RealtimeEvent) -> Boolean, emit: () -> Unit): RealtimeEvent =
        runBlocking {
            val session = connect()
            nextClientMessage()
            var found: RealtimeEvent? = null
            withTimeout(5_000) {
                val job = launch { found = session.events.first(predicate) }
                // Give the collector a moment to subscribe; the flow has no
                // replay, so emitting before subscription would be lost.
                kotlinx.coroutines.delay(100)
                emit()
                job.join()
            }
            found!!
        }

    @Test
    fun `audio deltas are decoded`() {
        val event = collectEvent({ it is RealtimeEvent.AudioDelta }) {
            val b64 = Base64.encodeToString(byteArrayOf(9, 8, 7), Base64.NO_WRAP)
            serverSocket!!.send("""{"type":"response.audio.delta","delta":"$b64"}""")
        }
        assertEquals(listOf<Byte>(9, 8, 7), (event as RealtimeEvent.AudioDelta).pcm16.toList())
    }

    @Test
    fun `speech-started is surfaced so playback can stop locally`() {
        // The local stop must not wait for the server to stop sending. That
        // local latency IS what "instant barge-in" means.
        val event = collectEvent({ it is RealtimeEvent.SpeechStarted }) {
            serverSocket!!.send("""{"type":"input_audio_buffer.speech_started"}""")
        }
        assertTrue(event is RealtimeEvent.SpeechStarted)
    }

    @Test
    fun `a function call is surfaced with its id and arguments`() {
        val event = collectEvent({ it is RealtimeEvent.ToolCall }) {
            serverSocket!!.send(
                """{"type":"response.function_call_arguments.done","call_id":"c1",""" +
                    """"name":"get_current_time","arguments":"{\"tz\":\"UTC\"}"}""",
            )
        }
        val call = event as RealtimeEvent.ToolCall
        assertEquals("c1", call.callId)
        assertEquals("get_current_time", call.name)
        assertTrue("UTC" in call.argumentsJson)
    }

    @Test
    fun `assistant and user transcripts are distinguished`() {
        val assistant = collectEvent({ it is RealtimeEvent.TranscriptDelta }) {
            serverSocket!!.send("""{"type":"response.audio_transcript.done","transcript":"Hello."}""")
        } as RealtimeEvent.TranscriptDelta
        assertEquals(ProviderMessage.Role.assistant, assistant.role)
        assertTrue(assistant.final)

        val user = collectEvent({ it is RealtimeEvent.TranscriptDelta }) {
            serverSocket!!.send(
                """{"type":"conversation.item.input_audio_transcription.completed","transcript":"Hi."}""",
            )
        } as RealtimeEvent.TranscriptDelta
        assertEquals(ProviderMessage.Role.user, user.role)
    }

    @Test
    fun `a server error is surfaced as non-retryable`() {
        // A protocol error is not a transport blip: retrying the same bad
        // request produces the same error and burns audio-minutes doing it.
        val event = collectEvent({ it is RealtimeEvent.Error }) {
            serverSocket!!.send("""{"type":"error","error":{"code":"invalid_voice","message":"bad voice"}}""")
        } as RealtimeEvent.Error
        assertEquals("invalid_voice", event.code)
        assertTrue(!event.retryable)
    }

    @Test
    fun `audio usage is surfaced because it is the bill`() {
        val event = collectEvent({ it is RealtimeEvent.AudioUsage }) {
            serverSocket!!.send(
                """{"type":"response.done","response":{"usage":{""" +
                    """"input_token_details":{"audio_tokens":100},""" +
                    """"output_token_details":{"audio_tokens":250}}}}""",
            )
        } as RealtimeEvent.AudioUsage
        assertTrue(event.inputMs > 0 && event.outputMs > 0)
    }

    @Test
    fun `an unparseable frame does not kill the session`() {
        // A single malformed frame must not take down a live call.
        val event = collectEvent({ it is RealtimeEvent.SpeechStarted }) {
            serverSocket!!.send("not json at all")
            serverSocket!!.send("""{"type":"input_audio_buffer.speech_started"}""")
        }
        assertTrue(event is RealtimeEvent.SpeechStarted)
    }

    // ---- capability ------------------------------------------------------

    @Test
    fun `only realtime models are claimed`() {
        val p = provider()
        assertTrue(p.supportsRealtime("gpt-realtime"))
        assertTrue(p.supportsRealtime("gpt-4o-realtime-preview"))
        assertTrue(!p.supportsRealtime("gpt-5"))
        assertTrue(!p.supportsRealtime("claude-sonnet-4.6"))
    }

    @Test
    fun `the api key never appears in the request url`() {
        // Any request-URL logging — interceptors, crash reporters, proxies —
        // would capture a key placed in the query string.
        connect()
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recorded)
        assertTrue("test-key" !in recorded.path.orEmpty(), "the API key leaked into the URL")
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
    }
}
