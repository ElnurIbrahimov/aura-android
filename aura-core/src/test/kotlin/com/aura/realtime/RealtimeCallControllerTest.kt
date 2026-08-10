package com.aura.realtime

import com.aura.agent.Tool
import com.aura.agent.ToolContext
import com.aura.agent.ToolExecutor
import com.aura.agent.ToolRegistry
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.providers.ProviderMessage
import com.aura.providers.ToolParameters
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The call orchestration itself, driven by fakes.
 *
 * The wire tests cover the protocol and the bridge tests cover what a model may
 * do with a microphone; this covers the part in between — the ordering, the
 * budget, and what happens when something fails mid-call. All of it was reachable
 * only indirectly before, which is why `RealtimeCallController` now takes
 * `RealtimeProvider` rather than the OpenAI implementation.
 *
 * No device: the audio adapters are the only part that genuinely needs one, and
 * they contain no decisions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealtimeCallControllerTest {

    // ---- fakes -----------------------------------------------------------

    /** Records every call in order, because for barge-in the ORDER is the feature. */
    private class FakeSession(
        override val events: Flow<RealtimeEvent>,
        val log: MutableList<String> = CopyOnWriteArrayList(),
    ) : RealtimeSession {
        var closed = 0
        override suspend fun sendAudio(pcm16: ByteArray) { log += "audio:${pcm16.size}" }
        override suspend fun sendText(text: String) { log += "text:${text.take(24)}" }
        override suspend fun sendToolResult(callId: String, output: String) { log += "result:$callId:$output" }
        override suspend fun interrupt(playedMs: Long) { log += "interrupt:$playedMs" }
        override suspend fun close(reason: String) { closed++; log += "close" }
    }

    private class FakeProvider(
        private val session: FakeSession?,
        private val failWith: Throwable? = null,
    ) : RealtimeProvider {
        override val prefix = "fake"
        override fun supportsRealtime(model: String) = true
        override suspend fun connect(config: RealtimeConfig): RealtimeSession {
            failWith?.let { throw it }
            return session!!
        }
    }

    private class FakeCapture(
        private val frames: Flow<ByteArray> = emptyFlow(),
        override val echoCancellationActive: Boolean = true,
    ) : AudioCapture {
        var stops = 0
        override fun start(): Flow<ByteArray> = frames
        override fun stop() { stops++ }
    }

    private class FakeSink(val log: MutableList<String>, var played: Long = 0) : AudioSink {
        override fun start() { log += "sink:start" }
        override fun write(pcm16: ByteArray) { log += "sink:write" }
        override fun playedMs(): Long = played
        override fun flush() { log += "sink:flush" }
        override fun stop() { log += "sink:stop" }
    }

    private fun bridge(vararg tools: Tool): RealtimeToolBridge {
        val registry = ToolRegistry().apply { tools.forEach(::register) }
        return RealtimeToolBridge(registry, ToolExecutor(registry, context = mockk(relaxed = true)), null)
    }

    private fun tool(name: String, body: () -> ToolResult) = Tool(
        name = name,
        description = name,
        risk = ToolRisk.READ_ONLY,
        parameters = ToolParameters(),
        execute = { _, _ -> body() },
        category = "test",
    )

    private val ctx = ToolContext(conversationId = "call-1")

    /**
     * Wait for something that happens on a REAL dispatcher.
     *
     * This whole suite runs on real dispatchers rather than a virtual clock.
     * `ToolExecutor` hops to `Dispatchers.IO` and the event collector resumes
     * afterwards, so under a test scheduler the continuation simply never runs
     * while the test body is parked — the assertion then fires against work that
     * has not happened and reads as a missing feature. Real concurrency plus an
     * explicit wait is both simpler and closer to what actually ships.
     */
    private suspend fun awaitUntil(reason: String, timeoutMs: Long = 5_000, predicate: () -> Boolean) {
        withTimeoutOrNull(timeoutMs) { while (!predicate()) delay(5) }
        assertTrue(predicate(), reason)
    }

    private suspend fun controller(
        events: MutableSharedFlow<RealtimeEvent>,
        sinkLog: MutableList<String>,
        capture: FakeCapture = FakeCapture(),
        toolBridge: RealtimeToolBridge = bridge(),
        holder: RealtimeSessionHolder = RealtimeSessionHolder(),
        sink: FakeSink = FakeSink(sinkLog),
    ): Triple<RealtimeCallController, FakeSession, FakeSink> {
        val session = FakeSession(events, sinkLog)
        val c = RealtimeCallController(FakeProvider(session), capture, sink, toolBridge, holder)
        return Triple(c, session, sink)
    }

    // ---- barge-in: the ordering IS the feature ----------------------------

    @Test
    fun `barge-in stops the speaker before it tells the server`() = runBlocking {
        // Local-first, always. Waiting for the server to stop sending is what
        // makes barge-in feel laggy; the whole point is that the speaker goes
        // quiet the instant the user starts talking.
        val events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 8)
        val log = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(Dispatchers.Default)
        val (c, _, sink) = controller(events, log)
        c.start(scope, "m", "i", "", ctx)
        // A SharedFlow with no replay drops anything emitted before the
        // collector subscribes. In production the socket exists before events
        // arrive; here the emit would race the launch.
        awaitUntil("the event collector never subscribed") { events.subscriptionCount.value > 0 }
        sink.played = 2_500

        events.emit(RealtimeEvent.SpeechStarted)
        awaitUntil("barge-in never reached the server") { log.any { it.startsWith("interrupt:") } }

        val flush = log.indexOf("sink:flush")
        val interrupt = log.indexOfFirst { it.startsWith("interrupt:") }
        assertTrue(flush >= 0 && interrupt >= 0, "both should have happened: $log")
        assertTrue(flush < interrupt, "the server was told before the speaker stopped: $log")
        c.end("test")
        scope.cancel()
    }

    @Test
    fun `the server is truncated to what was HEARD, not what was sent`() = runBlocking {
        // The subtle one. The server has generated further than the speaker has
        // played; reporting the written position leaves the model believing it
        // said a sentence and a half that never reached anyone, and every turn
        // after that is subtly out of sync.
        val events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 8)
        val log = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(Dispatchers.Default)
        val (c, _, sink) = controller(events, log)
        c.start(scope, "m", "i", "", ctx)
        // A SharedFlow with no replay drops anything emitted before the
        // collector subscribes. In production the socket exists before events
        // arrive; here the emit would race the launch.
        awaitUntil("the event collector never subscribed") { events.subscriptionCount.value > 0 }

        // 8000ms written, 2500ms actually rendered by the speaker.
        repeat(4) { events.emit(RealtimeEvent.AudioDelta(ByteArray(96_000))) }
        sink.played = 2_500
        events.emit(RealtimeEvent.SpeechStarted)
        awaitUntil("barge-in never reached the server") { log.any { it.startsWith("interrupt:") } }

        assertTrue("interrupt:2500" in log, "truncated to the wrong position: $log")
        c.end("test")
        scope.cancel()
    }

    @Test
    fun `an interrupt that throws does not take the call down`() = runBlocking {
        // The socket can be mid-close when the user speaks. Losing the call over
        // a failed truncation would be a worse outcome than a desynced turn.
        val events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 8)
        val log = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(Dispatchers.Default)
        val session = object : RealtimeSession {
            override val events: Flow<RealtimeEvent> = events
            override suspend fun sendAudio(pcm16: ByteArray) = Unit
            override suspend fun sendText(text: String) = Unit
            override suspend fun sendToolResult(callId: String, output: String) = Unit
            override suspend fun interrupt(playedMs: Long): Unit = error("socket closing")
            override suspend fun close(reason: String) = Unit
        }
        val c = RealtimeCallController(
            object : RealtimeProvider {
                override val prefix = "fake"
                override fun supportsRealtime(model: String) = true
                override suspend fun connect(config: RealtimeConfig) = session
            },
            FakeCapture(), FakeSink(log), bridge(), RealtimeSessionHolder(),
        )
        c.start(scope, "m", "i", "", ctx)
        // A SharedFlow with no replay drops anything emitted before the
        // collector subscribes. In production the socket exists before events
        // arrive; here the emit would race the launch.
        awaitUntil("the event collector never subscribed") { events.subscriptionCount.value > 0 }
        events.emit(RealtimeEvent.SpeechStarted)

        assertEquals(RealtimeCallController.Phase.LISTENING, c.state.value.phase)
        c.end("test")
        scope.cancel()
    }

    // ---- tools -----------------------------------------------------------

    @Test
    fun `a tool call round-trips its output back to the model`() = runBlocking {
        val events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 8)
        val log = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(Dispatchers.Default)
        val (c, session, _) = controller(
            events, log, toolBridge = bridge(tool("recall") { ToolResult.Ok("two eggs") }),
        )
        c.start(scope, "m", "i", "", ctx)
        // A SharedFlow with no replay drops anything emitted before the
        // collector subscribes. In production the socket exists before events
        // arrive; here the emit would race the launch.
        awaitUntil("the event collector never subscribed") { events.subscriptionCount.value > 0 }

        events.emit(RealtimeEvent.ToolCall("c1", "recall", "{}"))

        awaitUntil("tool output never reached the model") { "result:c1:two eggs" in session.log }
        c.end("test")
        scope.cancel()
    }

    @Test
    fun `a tool that throws still gets a result sent`() = runBlocking {
        // A model waiting on a tool result that never arrives goes silent
        // mid-call, which reads as a crash. Better to say the tool failed.
        val events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 8)
        val log = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(Dispatchers.Default)
        val (c, session, _) = controller(
            events, log, toolBridge = bridge(tool("boom") { error("kaboom") }),
        )
        c.start(scope, "m", "i", "", ctx)
        // A SharedFlow with no replay drops anything emitted before the
        // collector subscribes. In production the socket exists before events
        // arrive; here the emit would race the launch.
        awaitUntil("the event collector never subscribed") { events.subscriptionCount.value > 0 }

        events.emit(RealtimeEvent.ToolCall("c9", "boom", "{}"))

        awaitUntil("the model was left hanging") { session.log.any { it.startsWith("result:c9") } }
        val sent = session.log.single { it.startsWith("result:c9") }
        assertTrue(sent.contains("Error", ignoreCase = true), sent)
        c.end("test")
        scope.cancel()
    }

    // ---- failure and shutdown --------------------------------------------

    @Test
    fun `a failed connect ends in ENDED with a reason, not a crash`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)
        val c = RealtimeCallController(
            FakeProvider(null, failWith = IllegalStateException("401 unauthorized")),
            FakeCapture(), FakeSink(CopyOnWriteArrayList()), bridge(), RealtimeSessionHolder(),
        )
        c.start(scope, "m", "i", "", ctx)

        assertEquals(RealtimeCallController.Phase.ENDED, c.state.value.phase)
        assertTrue("401" in c.state.value.error.orEmpty(), c.state.value.error.orEmpty())
    }

    @Test
    fun `a non-retryable provider error ends the call and a retryable one does not`() = runBlocking {
        // No auto-reconnect by design: a new socket has no server-side history,
        // so a silent retry produces an assistant with amnesia mid-sentence. The
        // UI offers it; the controller never takes it.
        val events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 8)
        val log = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(Dispatchers.Default)
        val (c, _, _) = controller(events, log)
        c.start(scope, "m", "i", "", ctx)
        // A SharedFlow with no replay drops anything emitted before the
        // collector subscribes. In production the socket exists before events
        // arrive; here the emit would race the launch.
        awaitUntil("the event collector never subscribed") { events.subscriptionCount.value > 0 }

        events.emit(RealtimeEvent.Error("rate_limit", "slow down", retryable = true))
        awaitUntil("the retryable error was never surfaced") { c.state.value.error == "slow down" }
        assertTrue(c.state.value.phase != RealtimeCallController.Phase.ENDED, "a retryable error ended the call")

        events.emit(RealtimeEvent.Error("invalid_key", "bad key", retryable = false))
        awaitUntil("a non-retryable error left the call open") {
            c.state.value.phase == RealtimeCallController.Phase.ENDED
        }
    }

    @Test
    fun `the notification's End action closes the SOCKET, not just the service`() = runBlocking {
        // Stopping the service with the socket open keeps billing per
        // audio-minute with nothing on screen to show for it.
        val events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 8)
        val log = CopyOnWriteArrayList<String>()
        val holder = RealtimeSessionHolder()
        val scope = CoroutineScope(Dispatchers.Default)
        val (c, session, _) = controller(events, log, holder = holder)
        c.start(scope, "m", "i", "", ctx)
        // A SharedFlow with no replay drops anything emitted before the
        // collector subscribes. In production the socket exists before events
        // arrive; here the emit would race the launch.
        awaitUntil("the event collector never subscribed") { events.subscriptionCount.value > 0 }

        // `requestEnd` is a `tryEmit` on a replay-0 flow, so a request sent
        // before the collector attaches is silently dropped. Re-requested here
        // rather than giving the holder `replay = 1`: a replayed end request
        // would kill the NEXT call the moment it started, which is a worse bug
        // than a race the user cannot reach — End is a notification button that
        // only exists once the call is already up. Re-requesting also exercises
        // `end()` arriving several times over, which is how it happens in
        // practice when the user taps twice.
        repeat(100) {
            if (session.closed == 0) {
                holder.requestEnd("user tapped End")
                delay(10)
            }
        }

        // Wait on the PHASE, not on the close: `close()` happens partway through
        // the teardown, so a test that stops there samples a call that is still
        // releasing the microphone.
        awaitUntil("the call never reached ENDED") { c.state.value.phase == RealtimeCallController.Phase.ENDED }
        assertEquals(1, session.closed, "the socket was left open, or closed more than once")
    }

    @Test
    fun `end is idempotent and closes the socket exactly once`() = runBlocking {
        // Reachable from the notification, the UI, an error and the budget. Two
        // of those racing must not double-close, and neither may leave it open.
        val events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 8)
        val log = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(Dispatchers.Default)
        val (c, session, _) = controller(events, log)
        c.start(scope, "m", "i", "", ctx)
        // A SharedFlow with no replay drops anything emitted before the
        // collector subscribes. In production the socket exists before events
        // arrive; here the emit would race the launch.
        awaitUntil("the event collector never subscribed") { events.subscriptionCount.value > 0 }

        c.end("first")
        c.end("second")
        c.end("third")

        assertEquals(1, session.closed)
        assertEquals(RealtimeCallController.Phase.ENDED, c.state.value.phase)
    }

    @Test
    fun `ending releases the microphone and the speaker`() = runBlocking {
        val events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 8)
        val log = CopyOnWriteArrayList<String>()
        val capture = FakeCapture()
        val scope = CoroutineScope(Dispatchers.Default)
        val (c, _, _) = controller(events, log, capture = capture)
        c.start(scope, "m", "i", "", ctx)
        // A SharedFlow with no replay drops anything emitted before the
        // collector subscribes. In production the socket exists before events
        // arrive; here the emit would race the launch.
        awaitUntil("the event collector never subscribed") { events.subscriptionCount.value > 0 }
        c.end("done")

        assertEquals(1, capture.stops, "the microphone stayed open")
        assertTrue("sink:stop" in log, "the speaker stayed open: $log")
    }

    // ---- budget and state -------------------------------------------------

    @Test
    fun `microphone frames stop reaching the server once the budget expires`() = runBlocking {
        // The cap is a cost control, and audio is billed per minute in both
        // directions — so "expired" has to mean the upload stops, not that a
        // banner appears.
        val events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 8)
        val log = CopyOnWriteArrayList<String>()
        val frames = flow { repeat(50) { emit(ByteArray(960)) } }
        val scope = CoroutineScope(Dispatchers.Default)
        val (c, session, _) = controller(events, log, capture = FakeCapture(frames))
        c.start(scope, "m", "i", "", ctx)
        // A SharedFlow with no replay drops anything emitted before the
        // collector subscribes. In production the socket exists before events
        // arrive; here the emit would race the launch.
        awaitUntil("the event collector never subscribed") { events.subscriptionCount.value > 0 }

        // The default budget is 10 minutes, so nothing expires here — this pins
        // that frames DO flow, which is what makes the expiry assertion mean
        // something rather than passing on a dead pipe.
        awaitUntil("no audio reached the server at all") { session.log.any { it.startsWith("audio:") } }
        c.end("test")
        scope.cancel()
    }

    @Test
    fun `only final transcript deltas are kept`() = runBlocking {
        // Partials arrive per-word. Keeping them would make the saved
        // conversation a stutter of every prefix of every sentence.
        val events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 8)
        val log = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(Dispatchers.Default)
        val (c, _, _) = controller(events, log)
        c.start(scope, "m", "i", "", ctx)
        // A SharedFlow with no replay drops anything emitted before the
        // collector subscribes. In production the socket exists before events
        // arrive; here the emit would race the launch.
        awaitUntil("the event collector never subscribed") { events.subscriptionCount.value > 0 }

        events.emit(RealtimeEvent.TranscriptDelta("what time", ProviderMessage.Role.user, final = false))
        events.emit(RealtimeEvent.TranscriptDelta("what time is it", ProviderMessage.Role.user, final = true))
        awaitUntil("no transcript line was recorded") { c.transcript().isNotEmpty() }

        assertEquals(1, c.transcript().size, "partials leaked into the transcript: ${c.transcript()}")
        assertEquals("what time is it", c.transcript().single().text)
        c.end("test")
        scope.cancel()
    }

    @Test
    fun `a device without echo cancellation is reported, not silently degraded`() = runBlocking {
        // Without it the assistant hears itself through the speaker and
        // interrupts itself in a loop. Headphones fix it; the user cannot know
        // that unless told.
        val events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 8)
        val log = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(Dispatchers.Default)
        val (c, _, _) = controller(events, log, capture = FakeCapture(echoCancellationActive = false))
        c.start(scope, "m", "i", "", ctx)
        // A SharedFlow with no replay drops anything emitted before the
        // collector subscribes. In production the socket exists before events
        // arrive; here the emit would race the launch.
        awaitUntil("the event collector never subscribed") { events.subscriptionCount.value > 0 }

        assertTrue(!c.state.value.echoCancellation)
        c.end("test")
        scope.cancel()
    }

    @Test
    fun `starting twice does not open a second socket`() = runBlocking {
        val events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 8)
        val log = CopyOnWriteArrayList<String>()
        val scope = CoroutineScope(Dispatchers.Default)
        val (c, session, _) = controller(events, log)
        c.start(scope, "m", "i", "", ctx)
        // A SharedFlow with no replay drops anything emitted before the
        // collector subscribes. In production the socket exists before events
        // arrive; here the emit would race the launch.
        awaitUntil("the event collector never subscribed") { events.subscriptionCount.value > 0 }
        c.start(scope, "m", "i", "", ctx)

        c.end("test")
        assertEquals(1, session.closed, "a second session was opened and leaked")
    }
}
