package com.aura.realtime

import com.aura.providers.ProviderMessage
import kotlinx.coroutines.flow.Flow

/**
 * A live, duplex voice session with a model.
 *
 * Deliberately **not** an extension of `Provider`. `Provider.chat` is a
 * one-shot, non-suspend `Flow<ProviderChunk>`; a realtime session is
 * long-lived, bidirectional, stateful, and owns an audio sink. Forcing it into
 * that interface means either a default-throwing method on all seventeen
 * implementations or a `ProviderRegistry.parse` result whose type promises
 * something it cannot do.
 *
 * Only OpenAI implements this. Gemini Live is a second beta protocol with its
 * own event vocabulary, and carrying two of them doubles the churn every time
 * either changes shape — which both have done repeatedly. Adding it later is a
 * new class, not a refactor.
 */
interface RealtimeProvider {
    /** Matches the chat `Provider.prefix`, so the two can be correlated. */
    val prefix: String

    fun supportsRealtime(model: String): Boolean

    /** Open a session. Throws on auth or transport failure. */
    suspend fun connect(config: RealtimeConfig): RealtimeSession
}

/**
 * How a session should behave, fixed at connect time.
 *
 * @param instructions the system prompt. Seeded once — a realtime session does
 *   not run the agentic loop, so there is no per-turn recall to inject and this
 *   is the only chance to say who Aura is.
 * @param tools tools the model may call. Filtered by the caller to those that
 *   cannot raise a gate; see `RealtimeToolBridge`.
 * @param voice provider voice id.
 * @param seedContext one recall pass plus the user profile, folded into the
 *   instructions. Roughly 80% of the memory value for 1% of the complexity of
 *   wiring per-turn retrieval into a duplex stream.
 */
data class RealtimeConfig(
    val model: String,
    val instructions: String,
    val tools: List<com.aura.providers.ToolDefinition> = emptyList(),
    val voice: String = "alloy",
    val seedContext: String = "",
    val inputSampleRateHz: Int = 24_000,
)

/**
 * One open session. Close it or it bills until the server times out.
 */
interface RealtimeSession {
    val events: Flow<RealtimeEvent>

    /** Append PCM16 mono audio at the configured sample rate. */
    suspend fun sendAudio(pcm16: ByteArray)

    /** Inject text as if the user had spoken it. */
    suspend fun sendText(text: String)

    suspend fun sendToolResult(callId: String, output: String)

    /**
     * Tell the server the user interrupted, and how much of its reply was
     * actually heard.
     *
     * [playedMs] is load-bearing, not telemetry. The server has already
     * generated further than the speaker has played; without truncating to what
     * was really heard, the model believes it said sentences the user never
     * received and the conversation desynchronises from that point on. It is
     * the difference between barge-in that works and barge-in that feels
     * broken in a way nobody can describe.
     */
    suspend fun interrupt(playedMs: Long)

    suspend fun close(reason: String = "client closed")
}

/** Everything a session can report. */
sealed class RealtimeEvent {
    /** PCM16 audio to play. */
    data class AudioDelta(val pcm16: ByteArray) : RealtimeEvent() {
        override fun equals(other: Any?) =
            this === other || (other is AudioDelta && pcm16.contentEquals(other.pcm16))

        override fun hashCode() = pcm16.contentHashCode()
    }

    data class TranscriptDelta(
        val text: String,
        val role: ProviderMessage.Role,
        val final: Boolean,
    ) : RealtimeEvent()

    data class ToolCall(val callId: String, val name: String, val argumentsJson: String) : RealtimeEvent()

    /** Server VAD detected the user speaking. Stop playback locally, now. */
    object SpeechStarted : RealtimeEvent()

    object SpeechStopped : RealtimeEvent()

    object ResponseDone : RealtimeEvent()

    /** Billing is per audio-minute, so this is cost, not statistics. */
    data class AudioUsage(val inputMs: Long, val outputMs: Long) : RealtimeEvent()

    data class Error(val code: String, val message: String, val retryable: Boolean) : RealtimeEvent()

    object Closed : RealtimeEvent()
}
