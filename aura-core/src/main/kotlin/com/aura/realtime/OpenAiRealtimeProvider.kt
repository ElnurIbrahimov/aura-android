package com.aura.realtime

import android.util.Base64
import com.aura.providers.ProviderKeys
import com.aura.providers.ProviderMessage
import com.aura.providers.ToolDefinition
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OpenAI Realtime over WebSocket.
 *
 * The first WebSocket in this codebase — everything else streams over SSE. No
 * new dependency: OkHttp already ships `okhttp3.WebSocket`.
 */
@Singleton
class OpenAiRealtimeProvider @Inject constructor(
    private val providerKeys: ProviderKeys,
    private val httpClient: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : RealtimeProvider {

    override val prefix = "openai"

    override fun supportsRealtime(model: String): Boolean =
        model.contains("realtime", ignoreCase = true)

    override suspend fun connect(config: RealtimeConfig): RealtimeSession {
        val key = providerKeys.keyForAwaiting(prefix)
            ?: error("No OpenAI API key configured for realtime voice.")
        val model = config.model.substringAfter(':', config.model)

        val request = Request.Builder()
            .url("$baseUrl?model=$model")
            // Header auth, never a query parameter. Any request-URL logging —
            // OkHttp interceptors, crash reporters, proxies — would capture a
            // key placed in the query string.
            .addHeader("Authorization", "Bearer $key")
            .addHeader("OpenAI-Beta", "realtime=v1")
            .build()

        return OpenAiRealtimeSession(httpClient, request, config)
    }

    companion object {
        const val DEFAULT_BASE_URL = "wss://api.openai.com/v1/realtime"
    }
}

/**
 * One live session.
 *
 * **No auto-reconnect.** A dropped socket loses server-side conversation state,
 * so silently reconnecting produces an assistant with amnesia mid-sentence — it
 * answers as though the last two minutes never happened, and the user cannot
 * tell why. The session emits a retryable [RealtimeEvent.Error] and stops; the
 * UI offers "Reconnect", and a fresh session is seeded with a summary of what
 * was said. Written down because "helpfully" adding a retry loop here is the
 * obvious next change and it makes the product worse.
 */
internal class OpenAiRealtimeSession(
    httpClient: OkHttpClient,
    request: Request,
    private val config: RealtimeConfig,
) : RealtimeSession {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * `extraBufferCapacity` with DROP_OLDEST rather than an unbounded buffer.
     * Audio deltas arrive faster than a slow collector drains them, and an
     * unbounded buffer under back-pressure grows until the process dies —
     * dropping the oldest audio degrades the tail of a reply, which is
     * recoverable, where OOM is not.
     */
    private val _events = MutableSharedFlow<RealtimeEvent>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<RealtimeEvent> = _events.asSharedFlow()

    /**
     * Mirrors `EventSourceHolder`, which exists because of a cancel-race in the
     * SSE path: null the reference BEFORE closing, so a concurrent close cannot
     * act on a socket already being torn down.
     */
    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var closed = false

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(sessionUpdate().toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = runCatching { json.parseToJsonElement(text).jsonObject }
                .onFailure { android.util.Log.w(TAG, "unparseable realtime event: ${it.message}", it) }
                .getOrNull() ?: return
            handle(obj)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (closed) return
            _events.tryEmit(
                RealtimeEvent.Error(
                    code = "realtime_transport",
                    message = t.message ?: "connection failed",
                    // Retryable, but the CALLER decides — see the class KDoc on
                    // why this must not reconnect itself.
                    retryable = true,
                ),
            )
            _events.tryEmit(RealtimeEvent.Closed)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            _events.tryEmit(RealtimeEvent.Closed)
        }
    }

    init {
        socket = httpClient.newWebSocket(request, listener)
    }

    // ---- outbound --------------------------------------------------------

    override suspend fun sendAudio(pcm16: ByteArray) {
        send {
            put("type", "input_audio_buffer.append")
            put("audio", Base64.encodeToString(pcm16, Base64.NO_WRAP))
        }
    }

    override suspend fun sendText(text: String) {
        send {
            put("type", "conversation.item.create")
            put(
                "item",
                buildJsonObject {
                    put("type", "message")
                    put("role", "user")
                    put(
                        "content",
                        JsonArray(
                            listOf(buildJsonObject { put("type", "input_text"); put("text", text) }),
                        ),
                    )
                },
            )
        }
        send { put("type", "response.create") }
    }

    override suspend fun sendToolResult(callId: String, output: String) {
        send {
            put("type", "conversation.item.create")
            put(
                "item",
                buildJsonObject {
                    put("type", "function_call_output")
                    put("call_id", callId)
                    put("output", output)
                },
            )
        }
        send { put("type", "response.create") }
    }

    override suspend fun interrupt(playedMs: Long) {
        // Truncated to what was ACTUALLY HEARD. The server has generated
        // further than the speaker has played; without this the model believes
        // it said sentences the user never received, and every later turn is
        // reasoning about a conversation that did not happen.
        send {
            put("type", "conversation.item.truncate")
            put("audio_end_ms", playedMs.coerceAtLeast(0))
        }
        send { put("type", "response.cancel") }
    }

    override suspend fun close(reason: String) {
        closed = true
        val s = socket
        socket = null
        s?.close(NORMAL_CLOSURE, reason)
    }

    private inline fun send(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        val s = socket ?: return
        s.send(buildJsonObject(build).toString())
    }

    // ---- inbound ---------------------------------------------------------

    private fun handle(obj: JsonObject) {
        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "response.audio.delta" -> {
                val b64 = obj["delta"]?.jsonPrimitive?.contentOrNull ?: return
                runCatching { Base64.decode(b64, Base64.DEFAULT) }
                    .onFailure { android.util.Log.w(TAG, "bad audio delta: ${it.message}", it) }
                    .getOrNull()
                    ?.let { _events.tryEmit(RealtimeEvent.AudioDelta(it)) }
            }

            "response.audio_transcript.delta" ->
                obj["delta"]?.jsonPrimitive?.contentOrNull?.let {
                    _events.tryEmit(RealtimeEvent.TranscriptDelta(it, ProviderMessage.Role.assistant, false))
                }

            "response.audio_transcript.done" ->
                obj["transcript"]?.jsonPrimitive?.contentOrNull?.let {
                    _events.tryEmit(RealtimeEvent.TranscriptDelta(it, ProviderMessage.Role.assistant, true))
                }

            "conversation.item.input_audio_transcription.completed" ->
                obj["transcript"]?.jsonPrimitive?.contentOrNull?.let {
                    _events.tryEmit(RealtimeEvent.TranscriptDelta(it, ProviderMessage.Role.user, true))
                }

            "response.function_call_arguments.done" -> {
                val callId = obj["call_id"]?.jsonPrimitive?.contentOrNull ?: return
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return
                val args = obj["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}"
                _events.tryEmit(RealtimeEvent.ToolCall(callId, name, args))
            }

            "input_audio_buffer.speech_started" -> _events.tryEmit(RealtimeEvent.SpeechStarted)
            "input_audio_buffer.speech_stopped" -> _events.tryEmit(RealtimeEvent.SpeechStopped)
            "response.done" -> {
                emitUsage(obj)
                _events.tryEmit(RealtimeEvent.ResponseDone)
            }

            "error" -> {
                val err = obj["error"]?.jsonObject
                _events.tryEmit(
                    RealtimeEvent.Error(
                        code = err?.get("code")?.jsonPrimitive?.contentOrNull ?: "realtime_error",
                        message = err?.get("message")?.jsonPrimitive?.contentOrNull ?: "unknown error",
                        retryable = false,
                    ),
                )
            }
        }
    }

    /** Audio minutes are the bill; surfacing them is cost control, not stats. */
    private fun emitUsage(obj: JsonObject) {
        val usage = obj["response"]?.jsonObject?.get("usage")?.jsonObject ?: return
        val inTokens = usage["input_token_details"]?.jsonObject
            ?.get("audio_tokens")?.jsonPrimitive?.intOrNull ?: 0
        val outTokens = usage["output_token_details"]?.jsonObject
            ?.get("audio_tokens")?.jsonPrimitive?.intOrNull ?: 0
        if (inTokens == 0 && outTokens == 0) return
        // Audio tokens are billed per token, but users think in minutes; the
        // published rate is ~10ms of audio per token.
        _events.tryEmit(RealtimeEvent.AudioUsage(inTokens * MS_PER_AUDIO_TOKEN, outTokens * MS_PER_AUDIO_TOKEN))
    }

    private fun sessionUpdate(): JsonObject = buildJsonObject {
        put("type", "session.update")
        put(
            "session",
            buildJsonObject {
                put(
                    "instructions",
                    listOf(config.instructions, config.seedContext)
                        .filter { it.isNotBlank() }
                        .joinToString("\n\n"),
                )
                put("voice", config.voice)
                put("input_audio_format", "pcm16")
                put("output_audio_format", "pcm16")
                put(
                    "input_audio_transcription",
                    buildJsonObject { put("model", "whisper-1") },
                )
                // Server-side VAD. Client VAD on Android is a research project,
                // and the server's is tuned and free.
                put(
                    "turn_detection",
                    buildJsonObject {
                        put("type", "server_vad")
                        put("threshold", 0.5)
                        put("silence_duration_ms", 500)
                    },
                )
                if (config.tools.isNotEmpty()) {
                    put("tools", JsonArray(config.tools.map(::toolJson)))
                    put("tool_choice", JsonPrimitive("auto"))
                }
            },
        )
    }

    private fun toolJson(tool: ToolDefinition): JsonObject = buildJsonObject {
        // Flat shape, like the Responses API — name and parameters are siblings
        // of type, not nested under a "function" object as in Chat Completions.
        put("type", "function")
        put("name", tool.name)
        put("description", tool.description)
        put("parameters", com.aura.providers.toolParametersJson(tool.parameters))
    }

    private companion object {
        const val TAG = "OpenAiRealtime"
        const val NORMAL_CLOSURE = 1000
        const val MS_PER_AUDIO_TOKEN = 10L
    }
}
