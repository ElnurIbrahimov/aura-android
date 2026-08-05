package com.aura.kg

import com.aura.provenance.ConversationProvenance
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log

/**
 * Best-effort extractor that runs after an assistant turn completes.
 *
 * Each turn is queued (not overwritten) and processed in arrival order
 * after a 2s debounce. A long-running extraction does NOT block new
 * turns from being queued — they drain after the current run finishes.
 * A safety cap (MAX_PENDING) bounds memory if the consumer
 * (tool.extract) is much slower than the producer (chat turns).
 *
 * Failures are logged at WARN — the chat stream must not be
 * interrupted, but the user/developer must see the failure in
 * logcat.
 */
@Singleton
class ConversationKgExtractor private constructor(
    private val knowledgeGraphTool: com.aura.tools.KnowledgeGraphTool,
    private val repository: KnowledgeGraphRepository,
    dispatcher: CoroutineDispatcher,
) {
    /**
     * Hilt entry point. Uses the IO dispatcher because extraction is
     * disk+network bound.
     */
    @Inject
    constructor(
        tool: com.aura.tools.KnowledgeGraphTool,
        repo: KnowledgeGraphRepository,
    ) : this(tool, repo, Dispatchers.IO)

    /** Visible for testing. */
    internal constructor(
        tool: com.aura.tools.KnowledgeGraphTool,
        repo: KnowledgeGraphRepository,
        dispatcher: CoroutineDispatcher,
        @Suppress("UNUSED_PARAMETER") testMarker: Unit,
    ) : this(tool, repo, dispatcher)

    /**
     * Process-scoped scope (lives as long as the app). A real app might
     * prefer an application-scoped qualifier; here the singleton lifetime
     * matches the process, so cancel() is only invoked in tests or on
     * explicit teardown if one is added later.
     */
    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    private data class PendingExtraction(
        val text: String,
        val provenance: ConversationProvenance,
    )

    /**
     * Thread-safe FIFO queue of pending turns awaiting extraction.
     * ConcurrentLinkedQueue is used because extract() is called from
     * the agentic loop's coroutine and the drain runs on the
     * extractor scope — different threads, no shared mutability
     * conflicts.
     */
    private val pending = ConcurrentLinkedQueue<PendingExtraction>()

    /**
     * A new turn arriving ALWAYS restarts the debounce — but does not
     * drop already-queued turns. The previous debounceJob is cancelled
     * (its delay is interrupted), and a new one starts that will
     * drain the queue when it fires.
     */
    @Volatile private var debounceJob: Job? = null

    /**
     * `true` while an extraction run is in progress (between
     * tool.extract and the final saveGraph). New turns still queue
     * during this window — they are processed by a chained
     * continuation, not dropped.
     */
    @Volatile private var running: Boolean = false

    /**
     * Stats for diagnostics. Surfaced via a getter for Settings →
     * Diagnostics so the user can see "X turns were dropped because
     * the queue overflowed".
     */
    @Volatile private var droppedCount: Int = 0
    fun getDroppedCount(): Int = droppedCount

    /** Visible for testing / teardown. */
    fun shutdown() {
        scope.cancel()
        debounceJob?.cancel()
        debounceJob = null
    }

    /**
     * Queue a turn for extraction. The actual extraction runs after
     * a DEBOUNCE_MS delay (restarts on every new turn). If multiple
     * turns arrive within the debounce window, they are queued and
     * all processed in order — none are dropped.
     *
     * If the queue overflows (the consumer is too slow), the
     * oldest entries are dropped and a counter is incremented
     * for Diagnostics to surface.
     */
    fun extract(
        turnText: String,
        provenance: ConversationProvenance = ConversationProvenance(),
    ) {
        if (turnText.isBlank()) return
        // Enforce the cap. Drop the oldest if at capacity and
        // count it. Newer turns are more relevant than older ones
        // for KG extraction.
        if (pending.size >= MAX_PENDING) {
            val dropped = pending.poll()
            if (dropped != null) droppedCount += 1
        }
        pending.add(PendingExtraction(turnText, provenance))

        // Restart the debounce — the previous delay is interrupted,
        // a new one starts. This still preserves the queued turns
        // (they live in `pending`, not in the debounce job).
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            drainQueue()
        }
    }

    /**
     * Drain the queue serially. Called after debounce OR after a
     * previous run finishes (via the chain below).
     *
     * The `running` flag prevents re-entrancy if multiple debounces
     * fire concurrently. New turns that arrive DURING a drain are
     * simply queued — they will be picked up by the chained
     * continuation at the end of the current run.
     */
    private suspend fun drainQueue() {
        if (running) return
        running = true
        try {
            while (true) {
                val request = pending.poll() ?: return
                runOne(request)
            }
        } finally {
            running = false
            // Chained continuation: if turns arrived while we
            // were draining (and a debounce fired but found
            // running=true), make sure they get processed.
            // The debounce already restarted for any newly
            // arrived turns, but if NO new debounce fired
            // (e.g. the last turn was queued just as drain
            // started), kick off one now.
            if (pending.isNotEmpty()) {
                debounceJob?.cancel()
                debounceJob = scope.launch {
                    delay(DEBOUNCE_MS)
                    drainQueue()
                }
            }
        }
    }

    /**
     * Process a single extraction request. Failures are logged but
     * do not abort the queue drain — the next pending request is
     * tried regardless.
     */
    private suspend fun runOne(request: PendingExtraction) {
        try {
            val (nodes, edges) = knowledgeGraphTool.extract(request.text)
            if (nodes.isNotEmpty() || edges.isNotEmpty()) {
                repository.saveGraph(nodes, edges, request.provenance)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Log so the failure surfaces in logcat. The chat
            // stream must not be interrupted — best-effort
            // extraction is the contract. MEMORY_AUDIT E2: a
            // silent swallow made extraction breakage invisible;
            // Log.w gives the user/developer a signal.
            android.util.Log.w("KgExtractor",
                "KG extraction failed for turn from conv=${request.provenance.conversationId}: ${e.message}", e)
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 2_000L
        /**
         * Cap on the pending queue. Beyond this, the oldest
         * entries are dropped (with a droppedCount increment
         * surfaced via Diagnostics). 64 turns is enough for a
         * multi-tool long conversation; if extraction can't keep
         * up beyond that, the user is generating turns faster
         * than the network can extract, and dropping is better
         * than unbounded memory.
         */
        private const val MAX_PENDING = 64
    }
}
