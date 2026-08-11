package com.aura.kg

import android.util.Log
import com.aura.agent.ConversationStore
import com.aura.provenance.ConversationProvenance
import com.aura.tools.KnowledgeGraphTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Re-extract the knowledge graph from stored conversations.
 *
 * Until the CASCADE fix, `kg_nodes` was written with `INSERT OR REPLACE` while
 * `kg_edges` declared `ON DELETE CASCADE` on both endpoints — so re-saving a
 * node deleted every edge touching it. `KgId.node` hashes (type, label), so a
 * node keeps its id across mentions, and the extractor labels the user as
 * `user` on essentially every turn: the graph was continuously truncated to
 * roughly one turn's worth of edges. It accumulates correctly now, but the
 * connections lost during that period are gone from `kg_edges`, and only from
 * there — the conversations that produced them are still on disk.
 *
 * So this is a recovery path, not a migration, and deliberately not automatic.
 * It costs one model call per turn: on a long history that is real money and
 * real time, which is a decision for the person paying rather than something to
 * do silently on a launch after an update.
 *
 * Idempotent by construction now that both writes are `@Upsert`. Re-running it
 * re-derives the same deterministic ids and updates rows in place; it will not
 * duplicate a graph, and it will not reset a node's `createdAt`, because
 * [KnowledgeGraphRepository.saveGraph] reads that back.
 */
@Singleton
class KnowledgeGraphRebuilder @Inject constructor(
    private val conversationStore: ConversationStore,
    private val knowledgeGraphTool: KnowledgeGraphTool,
    private val repository: KnowledgeGraphRepository,
) {
    /** How far a rebuild has got, for a progress indicator. */
    data class Progress(
        val turnsDone: Int,
        val turnsTotal: Int,
        val conversationsDone: Int,
        val conversationsTotal: Int,
        val failures: Int,
    )

    /**
     * Walk every stored conversation and re-run extraction over each turn.
     *
     * Sequential on purpose. The live path queues extractions and drops the
     * oldest past `MAX_PENDING`, which is right for a chat stream that must not
     * block and wrong here — a rebuild that silently skipped turns would leave
     * a graph indistinguishable from the truncated one it is meant to replace.
     * It also keeps the model call rate to something a provider will not
     * rate-limit, which matters more than wall-clock for a one-off.
     *
     * A failing turn is counted and stepped over: one unparseable conversation
     * out of hundreds must not cost the whole rebuild. Cancellation propagates,
     * so the caller can stop it.
     *
     * @param onProgress called after each turn, on the calling coroutine.
     * @return the final [Progress].
     */
    suspend fun rebuild(onProgress: (Progress) -> Unit = {}): Progress {
        // `recent` with an explicit ceiling rather than an unbounded read: the
        // whole history is decoded into memory here, and a rebuild that OOMs on
        // a long history would be a worse outcome than one that covers the last
        // MAX_CONVERSATIONS. The count is reported, so a truncated rebuild is
        // visible rather than silent.
        val conversations = runCatching { conversationStore.recent(MAX_CONVERSATIONS) }
            .onFailure { Log.w(TAG, "rebuild: listing conversations failed: ${it.message}", it) }
            .getOrDefault(emptyList())

        val totalTurns = conversations.sumOf { it.turns.size }
        var turnsDone = 0
        var conversationsDone = 0
        var failures = 0

        for (conversation in conversations) {
            for (turn in conversation.turns) {
                coroutineContext.ensureActive()
                val text = listOfNotNull(turn.user, turn.assistant)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                turnsDone++
                if (text.isNotBlank()) {
                    try {
                        val (nodes, edges) = knowledgeGraphTool.extract(text)
                        if (nodes.isNotEmpty() || edges.isNotEmpty()) {
                            repository.saveGraph(
                                nodes,
                                edges,
                                // The ORIGINAL turn's provenance, not now. A node's
                                // sourceTurnId is meant to name the turn that
                                // introduced it; stamping the rebuild would rewrite
                                // the history this is trying to recover.
                                ConversationProvenance(
                                    conversationId = conversation.id,
                                    turnTimestamp = turn.timestamp,
                                ),
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failures++
                        Log.w(TAG, "rebuild: turn failed in conv=${conversation.id}: ${e.message}", e)
                    }
                }
                onProgress(Progress(turnsDone, totalTurns, conversationsDone, conversations.size, failures))
            }
            conversationsDone++
        }

        val final = Progress(turnsDone, totalTurns, conversationsDone, conversations.size, failures)
        Log.i(TAG, "rebuild complete: $turnsDone turn(s) over ${conversations.size} conversation(s), $failures failure(s)")
        return final
    }

    internal companion object {
        const val TAG = "KgRebuilder"

        /**
         * Ceiling on conversations read in one rebuild.
         *
         * Every one is decoded into memory to reach its turns, so this is a
         * bound on heap rather than on cost — the model calls are per turn and
         * already sequential. Newest first, because a graph rebuilt from recent
         * history is more useful than one rebuilt from the oldest.
         */
        const val MAX_CONVERSATIONS = 500
    }
}
