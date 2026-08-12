package com.aura.proactive

import android.util.Log
import com.aura.agent.ConversationStore
import com.aura.curiosity.OpenQuestionEntity
import com.aura.kg.KnowledgeGraphRepository
import com.aura.memory.MemoryDao
import com.aura.tasks.TaskDao
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks, some days after a suggestion went out, whether the thing it was worried
 * about actually resolved.
 *
 * This is the whole point of measuring outcome rather than engagement. A tap
 * says the card looked interesting; the task being finished says the card was
 * *right*. Only the second one is worth optimising toward, and only a system
 * that owns the underlying tables can see it — which is why no standalone
 * assistant does this.
 *
 * Rides the six-hourly [DecayWorker] rather than the daemon: the checks are
 * local SQL with no network, the daemon's 15–60 minute cadence would rescan for
 * due rows dozens of times between horizons for nothing, and the decay worker
 * carries no network or battery constraint.
 *
 * **Every signal here is chosen to be unconfounded**, which took more care than
 * it looks. Task salience drifts downward on its own via `TaskDecayPass`, so a
 * task near the quiet threshold would cross it unaided inside a 72-hour horizon
 * and be scored as a success this suggestion had nothing to do with; the
 * signals are `deferCount` and `lastTouchedAt`, which only a deliberate act
 * moves. Memory `decayScore` drifts the same way; `accessCount` is incremented
 * only by an actual read.
 */
@Singleton
class ProactiveOutcomePass @Inject constructor(
    private val outcomeDao: ProactiveOutcomeDao,
    private val taskDao: TaskDao,
    private val memoryDao: MemoryDao,
    private val conversationStore: ConversationStore,
    private val kgRepository: KnowledgeGraphRepository? = null,
    private val openQuestionDao: com.aura.curiosity.OpenQuestionDao? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** @return how many rows were closed. */
    suspend fun run(now: Long = System.currentTimeMillis()): Int {
        var closed = 0
        closed += closeDue(now)
        closed += forceCloseStale(now)
        return closed
    }

    private suspend fun closeDue(now: Long): Int {
        val due = runCatching { outcomeDao.due(now, BATCH) }
            .onFailure { Log.w(TAG, "due scan failed: ${it.message}", it) }
            .getOrDefault(emptyList())
        if (due.isEmpty()) return 0

        // Set predicates ask the same question of every row that has them, so
        // they are answered once per pass — and only when a row needs them.
        val latestConversationAt =
            if (due.any { it.subjectKind == ProactiveOutcomeEntity.SUBJECT_CONVERSATION }) {
                runCatching { conversationStore.recent(1).firstOrNull()?.updatedAt ?: 0L }
                    .onFailure { Log.w(TAG, "conversation probe failed: ${it.message}", it) }
                    .getOrDefault(0L)
            } else {
                0L
            }
        val highPriorityNow =
            if (due.any { it.subjectKind == ProactiveOutcomeEntity.SUBJECT_TASK_SET }) {
                runCatching { taskDao.all().count { it.priority >= 2 && it.status == "pending" } }
                    .onFailure { Log.w(TAG, "priority probe failed: ${it.message}", it) }
                    .getOrDefault(-1)
            } else {
                -1
            }

        var closed = 0
        for (row in due) {
            val verdict = runCatching {
                when (row.subjectKind) {
                    ProactiveOutcomeEntity.SUBJECT_TASK -> checkTask(row)
                    ProactiveOutcomeEntity.SUBJECT_MEMORY_SET -> checkMemories(row)
                    ProactiveOutcomeEntity.SUBJECT_KG_NODE_SET -> checkKgNodes(row)
                    ProactiveOutcomeEntity.SUBJECT_CONVERSATION -> checkConversation(row, latestConversationAt)
                    ProactiveOutcomeEntity.SUBJECT_TASK_SET -> checkTaskSet(row, highPriorityNow)
                    ProactiveOutcomeEntity.SUBJECT_QUESTION -> checkQuestion(row)
                    else -> null
                }
            }.onFailure { Log.w(TAG, "checking outcome ${row.id} failed: ${it.message}", it) }.getOrNull()

            if (verdict == null) {
                close(row, ProactiveOutcomeEntity.OUTCOME_IGNORED, now, "Nothing changed.")
            } else {
                close(row, verdict.first, now, verdict.second)
            }
            closed++
        }
        return closed
    }

    /**
     * Nothing stays pending forever.
     *
     * A row whose subject vanished, or whose check kept erroring, would
     * otherwise sit open and quietly shrink the denominator the ledger counts.
     */
    private suspend fun forceCloseStale(now: Long): Int {
        val stale = runCatching { outcomeDao.staleOpen(now - MAX_OPEN_MS) }
            .onFailure { Log.w(TAG, "stale scan failed: ${it.message}", it) }
            .getOrDefault(emptyList())
        for (row in stale) {
            close(row, ProactiveOutcomeEntity.OUTCOME_IGNORED, now, "Nothing changed in two weeks.")
        }
        return stale.size
    }

    private suspend fun close(row: ProactiveOutcomeEntity, outcome: String, at: Long, reason: String) {
        runCatching { outcomeDao.close(row.id, outcome, at, reason) }
            .onFailure { Log.w(TAG, "closing outcome ${row.id} failed: ${it.message}", it) }
    }

    // ---- per-subject checks ------------------------------------------------

    /**
     * Did the nudge get the question answered?
     *
     * The cleanest outcome in the whole pass: the question either closed or it
     * did not, and the row records which. No proxy, no inference.
     */
    private suspend fun checkQuestion(row: ProactiveOutcomeEntity): Pair<String, String> {
        val dao = openQuestionDao ?: return resolvedUnknown()
        val id = ids(row).firstOrNull() ?: return resolvedUnknown()
        val question = dao.byId(id)
            ?: return ProactiveOutcomeEntity.OUTCOME_IGNORED to "That question is gone."
        return when (question.status) {
            OpenQuestionEntity.STATUS_ANSWERED ->
                ProactiveOutcomeEntity.OUTCOME_RESOLVED to "You answered it."
            OpenQuestionEntity.STATUS_RESEARCHED ->
                ProactiveOutcomeEntity.OUTCOME_RESOLVED to "Aura found the answer itself."
            // A refusal is a real answer about the nudge, and not a good one.
            OpenQuestionEntity.STATUS_DISMISSED ->
                ProactiveOutcomeEntity.OUTCOME_IGNORED to "You asked not to be asked again."
            else -> ProactiveOutcomeEntity.OUTCOME_IGNORED to "Still unanswered."
        }
    }

    private suspend fun checkTask(row: ProactiveOutcomeEntity): Pair<String, String> {
        val id = ids(row).firstOrNull() ?: return resolvedUnknown()
        val task = taskDao.get(id)
            ?: return ProactiveOutcomeEntity.OUTCOME_RESOLVED to "You deleted that task."
        if (task.status != "pending") {
            return ProactiveOutcomeEntity.OUTCOME_RESOLVED to "You marked that task done."
        }
        val base = row.baselineJson.obj()
        val wasDeferred = base.long("deferCount")
        val wasTouched = base.long("lastTouchedAt")
        if (task.deferCount > wasDeferred) {
            return ProactiveOutcomeEntity.OUTCOME_RESOLVED to "You pushed that task back — a decision either way."
        }
        if (task.lastTouchedAt > row.postedAt && task.lastTouchedAt > wasTouched) {
            return ProactiveOutcomeEntity.OUTCOME_RESOLVED to "You went and touched that task."
        }
        return ProactiveOutcomeEntity.OUTCOME_IGNORED to "That task is still sitting there, untouched."
    }

    private suspend fun checkMemories(row: ProactiveOutcomeEntity): Pair<String, String> {
        val ids = ids(row)
        if (ids.isEmpty()) return resolvedUnknown()
        for (id in ids) {
            val memory = memoryDao.getById(id)
                ?: return ProactiveOutcomeEntity.OUTCOME_RESOLVED to "You cleared some of those memories."
            // accessCount == 0 was a precondition of being in this set, so any
            // read at all is unambiguous. decayScore would not be — the decay
            // pass moves that on its own.
            if (memory.accessCount > 0) {
                return ProactiveOutcomeEntity.OUTCOME_RESOLVED to "You went and read some of those memories."
            }
        }
        return ProactiveOutcomeEntity.OUTCOME_IGNORED to "Those memories are still untouched."
    }

    private suspend fun checkKgNodes(row: ProactiveOutcomeEntity): Pair<String, String> {
        val repo = kgRepository ?: return resolvedUnknown()
        val ids = ids(row)
        if (ids.isEmpty()) return resolvedUnknown()
        for (id in ids) {
            val neighbors = runCatching { repo.getNeighbors(id) }.getOrNull()
                ?: return ProactiveOutcomeEntity.OUTCOME_RESOLVED to "That entity is gone from the graph."
            val edges = neighbors.incoming + neighbors.outgoing
            val stillConflicting = edges.groupBy { it.targetId }
                .any { (_, rels) -> rels.size > 1 && rels.map { it.type }.distinct().size > 1 }
            if (!stillConflicting) {
                return ProactiveOutcomeEntity.OUTCOME_RESOLVED to "That contradiction is no longer there."
            }
        }
        return ProactiveOutcomeEntity.OUTCOME_IGNORED to "The graph still contradicts itself there."
    }

    private fun checkConversation(row: ProactiveOutcomeEntity, latestAt: Long): Pair<String, String> =
        if (latestAt > row.postedAt) {
            ProactiveOutcomeEntity.OUTCOME_RESOLVED to "You picked the conversation back up."
        } else {
            ProactiveOutcomeEntity.OUTCOME_IGNORED to "Still quiet since then."
        }

    private fun checkTaskSet(row: ProactiveOutcomeEntity, currentCount: Int): Pair<String, String> {
        if (currentCount < 0) return resolvedUnknown()
        val baseline = row.baselineJson.obj().long("highPriorityCount").toInt()
        return if (currentCount < baseline) {
            ProactiveOutcomeEntity.OUTCOME_RESOLVED to "You brought the high-priority pile down."
        } else {
            ProactiveOutcomeEntity.OUTCOME_IGNORED to "The high-priority pile is the same size or bigger."
        }
    }

    /**
     * The subject existed but cannot be read now.
     *
     * Counted as resolved rather than ignored on purpose: the alternative is
     * penalising a category for a database hiccup, and a system that learns to
     * stay quiet because a query failed is worse than one that occasionally
     * gives a suggestion the benefit of the doubt.
     */
    private fun resolvedUnknown(): Pair<String, String> =
        ProactiveOutcomeEntity.OUTCOME_RESOLVED to "Could not tell — giving it the benefit of the doubt."

    private fun ids(row: ProactiveOutcomeEntity): List<String> =
        runCatching { json.decodeFromString<List<String>>(row.subjectIds) }.getOrDefault(emptyList())

    private fun String.obj() = runCatching { json.parseToJsonElement(this).jsonObject }.getOrNull()

    private fun kotlinx.serialization.json.JsonObject?.long(key: String): Long =
        this?.get(key)?.jsonPrimitive?.longOrNull ?: 0L

    companion object {
        private const val TAG = "ProactiveOutcomePass"

        /** Rows checked per pass. Index-covered and bounded. */
        const val BATCH = 50

        /** After this long, waiting further proves nothing. */
        const val MAX_OPEN_MS = 14L * 24 * 60 * 60 * 1000

        /** How long each kind of subject gets before it is judged. */
        fun horizonFor(type: ProactiveFindingType): Long = when (type) {
            ProactiveFindingType.RELATIONSHIP_GAP -> 48L * 60 * 60 * 1000
            ProactiveFindingType.CONTRADICTION_ALERT -> 7L * 24 * 60 * 60 * 1000
            ProactiveFindingType.STUCK_TASKS,
            ProactiveFindingType.STALE_MEMORIES,
            ProactiveFindingType.PRIORITY_SHIFT,
            ProactiveFindingType.OPEN_QUESTION,
            -> 72L * 60 * 60 * 1000

            // No horizon: nothing observable will happen.
            //
            // deadline_approaching reads the calendar through a ContentProvider
            // Aura cannot write and cannot see attendance in. stress_correlation
            // measures tension, which EmotionEngine decays toward a baseline on
            // its own — any drop inside a horizon is the decay function, not the
            // suggestion. pattern_alert describes a state and proposes no
            // resolution, which is why it is the one check with no action.
            ProactiveFindingType.DEADLINE_APPROACHING,
            ProactiveFindingType.STRESS_CORRELATION,
            ProactiveFindingType.PATTERN_ALERT,
            -> 0L
        }
    }
}
