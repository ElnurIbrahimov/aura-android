package com.aura.realtime

import android.util.Log
import com.aura.agent.ToolContext
import com.aura.providers.ProviderMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
// kotlinx's `update`, which retries on compareAndSet. This file used to declare
// a private extension of the same name doing `value = block(value)` — a plain
// read-modify-write that silently shadowed the atomic one at all nine call
// sites. Three coroutines mutate this state concurrently (the event collector,
// the mic pump and the end job), so a barge-in landing while a transcript line
// was being appended could drop either. Harmless only for as long as the
// feature had no caller.
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives one live voice call: socket, microphone, speaker, tools, budget.
 *
 * Everything it touches is behind an interface, so the orchestration is
 * testable with fakes and only the platform adapters need a device.
 */
@Singleton
class RealtimeCallController @Inject constructor(
    // The interface, not OpenAiRealtimeProvider. The KDoc above claims the
    // orchestration is testable with fakes, and a concrete dependency here is
    // the one thing that would have made that claim false.
    private val provider: RealtimeProvider,
    private val capture: AudioCapture,
    private val sink: AudioSink,
    private val toolBridge: RealtimeToolBridge,
    private val sessionHolder: RealtimeSessionHolder,
) {

    data class State(
        val phase: Phase = Phase.IDLE,
        val transcript: List<Line> = emptyList(),
        val remainingMs: Long = 0,
        val warned: Boolean = false,
        val error: String? = null,
        /** False when the platform echo canceller is unavailable — see [AudioCapture]. */
        val echoCancellation: Boolean = true,
    )

    enum class Phase { IDLE, CONNECTING, LISTENING, SPEAKING, ENDED }

    data class Line(val role: ProviderMessage.Role, val text: String)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * All four are @Volatile because [end] promises to be safe from any thread
     * and these are what it reads.
     *
     * They are written from [start] on whatever scope opened the call, and read
     * and nulled by [end], which is reachable from the mic pump when the budget
     * runs out, from the notification's End action, from the UI, and from the
     * error path — three distinct coroutines on two dispatchers. Without the
     * annotation one thread could see a stale non-null [session] that another
     * had already closed, or worse, see null for a socket still open and skip
     * the close entirely. That last one is the expensive shape: an OpenAI
     * Realtime socket bills per audio-minute for as long as it stays up, with
     * nothing on screen to say it is.
     *
     * [OpenAiRealtimeProvider] annotates its own socket field for the same
     * reason and says so there.
     */
    @Volatile
    private var session: RealtimeSession? = null

    @Volatile
    private var eventJob: Job? = null

    @Volatile
    private var micJob: Job? = null

    @Volatile
    private var endJob: Job? = null
    private val budget = RealtimeBudget()

    /**
     * Open a call. Returns once connected; the call runs until [end].
     */
    suspend fun start(
        scope: CoroutineScope,
        model: String,
        instructions: String,
        seedContext: String,
        toolContext: ToolContext,
    ) {
        if (_state.value.phase != Phase.IDLE && _state.value.phase != Phase.ENDED) return
        _state.value = State(phase = Phase.CONNECTING)

        val tools = runCatching { toolBridge.advertisableTools(toolContext) }
            .onFailure { Log.w(TAG, "tool filtering failed; continuing with none: ${it.message}", it) }
            .getOrDefault(emptyList())

        val opened = runCatching {
            provider.connect(
                RealtimeConfig(
                    model = model,
                    instructions = instructions,
                    seedContext = seedContext,
                    tools = tools,
                ),
            )
        }.getOrElse { e ->
            _state.value = State(phase = Phase.ENDED, error = e.message ?: "could not connect")
            return
        }

        session = opened
        budget.start()
        sink.start()
        // Read here AND refreshed on the first frame below.
        //
        // `AndroidAudioCapture` attaches the AEC inside the flow builder that
        // `capture.start()` returns, which does not run until `micJob` collects
        // it five lines down. So on a real device this read is always false and
        // the sheet told every user echo cancellation was unavailable, on every
        // device, including the ones where it worked. A capture that already
        // knows its own state answers correctly here; one that cannot know yet
        // is corrected as soon as audio flows.
        _state.value = State(
            phase = Phase.LISTENING,
            remainingMs = budget.remainingMs(),
            echoCancellation = capture.echoCancellationActive,
        )

        eventJob = scope.launch { collectEvents(opened, toolContext) }
        micJob = scope.launch {
            var echoRefreshed = false
            capture.start().collect { frame ->
                if (!echoRefreshed) {
                    echoRefreshed = true
                    // By now the AEC either attached or did not, so this is the
                    // first moment the answer is real on a device.
                    _state.value = _state.value.copy(echoCancellation = capture.echoCancellationActive)
                }
                if (budget.isExpired()) {
                    end("the call reached its time limit")
                    return@collect
                }
                opened.sendAudio(frame)
                maybeWarn(opened)
            }
        }
        // The notification's End action ends the SESSION, not just the service:
        // stopping the service with the socket open would keep billing per
        // audio-minute with nothing on screen to show for it.
        endJob = scope.launch {
            sessionHolder.endRequests.collect { reason -> end(reason) }
        }
    }

    private suspend fun collectEvents(session: RealtimeSession, toolContext: ToolContext) {
        session.events.collect { event ->
            when (event) {
                is RealtimeEvent.AudioDelta -> {
                    _state.update { it.copy(phase = Phase.SPEAKING) }
                    sink.write(event.pcm16)
                }

                RealtimeEvent.SpeechStarted -> {
                    // Stop LOCALLY first, before telling the server. Waiting for
                    // the server to stop sending is what makes barge-in feel
                    // laggy; that local latency IS the feature.
                    sink.flush()
                    runCatching { session.interrupt(sink.playedMs()) }
                        .onFailure { Log.w(TAG, "interrupt failed: ${it.message}", it) }
                    _state.update { it.copy(phase = Phase.LISTENING) }
                }

                RealtimeEvent.SpeechStopped -> Unit

                is RealtimeEvent.TranscriptDelta ->
                    if (event.final) {
                        _state.update { it.copy(transcript = it.transcript + Line(event.role, event.text)) }
                    }

                is RealtimeEvent.ToolCall -> {
                    val output = runCatching { toolBridge.execute(event, toolContext) }
                        .onFailure { Log.w(TAG, "tool ${event.name} threw: ${it.message}", it) }
                        .getOrElse { "Error: ${it.message}" }
                    runCatching { session.sendToolResult(event.callId, output) }
                        .onFailure { Log.w(TAG, "tool result send failed: ${it.message}", it) }
                }

                RealtimeEvent.ResponseDone -> _state.update { it.copy(phase = Phase.LISTENING) }

                is RealtimeEvent.AudioUsage -> budget.record(event)

                is RealtimeEvent.Error -> {
                    // Surfaced, never auto-retried. Reconnecting loses
                    // server-side conversation state, so a silent retry
                    // produces an assistant with amnesia mid-sentence — see
                    // OpenAiRealtimeSession's KDoc.
                    _state.update { it.copy(error = event.message) }
                    if (!event.retryable) end("provider error: ${event.code}")
                }

                RealtimeEvent.Closed -> _state.update { it.copy(phase = Phase.ENDED) }
            }
        }
    }

    private suspend fun maybeWarn(session: RealtimeSession) {
        _state.update { it.copy(remainingMs = budget.remainingMs()) }
        if (!budget.shouldWarn() || _state.value.warned) return
        _state.update { it.copy(warned = true) }
        // Spoken, not shown: the user is on a call and may not be looking at
        // the screen, and a session that just stops reads as a crash.
        runCatching {
            session.sendText("[system] About a minute of call time remains. Mention it briefly.")
        }.onFailure { Log.w(TAG, "budget warning failed: ${it.message}", it) }
    }

    /**
     * End the call and release everything.
     *
     * Idempotent, and safe from any thread — it is reachable from the
     * notification, the UI, an error, and the budget, and two of those racing
     * must not leave a socket open.
     */
    suspend fun end(reason: String) {
        val s = session
        session = null
        if (s == null && _state.value.phase == Phase.IDLE) return

        val mic = micJob; micJob = null
        val ev = eventJob; eventJob = null
        val en = endJob; endJob = null

        mic.stopWithoutSelfJoin()
        capture.stop()
        sink.stop()
        runCatching { s?.close(reason) }.onFailure { Log.w(TAG, "socket close failed: ${it.message}", it) }
        ev.stopWithoutSelfJoin()
        en.stopWithoutSelfJoin()

        val (inSec, outSec) = budget.billedAudioSeconds()
        Log.i(TAG, "call ended ($reason): ${inSec}s in, ${outSec}s out")
        _state.update { it.copy(phase = Phase.ENDED, remainingMs = 0) }
    }

    /**
     * Cancel this job, and join it only if we are not running inside it.
     *
     * Three of the four ways a call ends run [end] from inside one of these
     * jobs: the notification's End action arrives on [endJob], a fatal provider
     * error on [eventJob], and budget expiry on [micJob]. A plain
     * `cancelAndJoin` there waits for the coroutine doing the waiting, so the
     * teardown never completes — and because everything after it is skipped,
     * the socket stays open and keeps billing per audio-minute with the UI
     * already showing the call as over. It deadlocks silently: no exception, no
     * log, just a call that will not end.
     */
    private suspend fun Job?.stopWithoutSelfJoin() {
        if (this == null) return
        cancel()
        val current = currentCoroutineContext()[Job]
        if (current != null && isAncestorOf(current)) return
        runCatching { join() }.onFailure { Log.w(TAG, "job join failed: ${it.message}", it) }
    }

    private fun Job.isAncestorOf(other: Job): Boolean =
        this === other || children.any { it.isAncestorOf(other) }

    /** The finished transcript, for persisting as a conversation. */
    fun transcript(): List<Line> = _state.value.transcript

    private companion object {
        const val TAG = "RealtimeCall"
    }
}
