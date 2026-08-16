package com.aura.memory

import android.util.Log
import com.aura.provenance.ConversationProvenance
import com.aura.security.Redactor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records what recall returned, so it can later be judged.
 *
 * Writes one row per returned memory per turn: the question, the memory, and
 * the rank it came back at. Nothing here decides whether the retrieval was
 * *good* — grades arrive later, from cheap heuristics and from a sampled judge.
 * Observation and judgment are kept apart deliberately, because the point of
 * sampling is to check the heuristics against something better, and a single
 * fused column would destroy that comparison.
 *
 * **Sampling is not decided here.** The recall path stays dumb: every row is
 * written unsampled, and the judge worker later draws the turns it will grade.
 * Deciding on the hot path would mean either an RNG whose answer changes when a
 * retry re-recalls the same turn, or a counting query on every recall. Drawing
 * later, from what is already recorded, is both cheaper and a better sample —
 * it can be stratified across the retention window rather than committed to
 * turn by turn as they happen.
 *
 * Every write is best-effort. Recall must not fail because telemetry did.
 */
@Singleton
class RetrievalLabelStore @Inject constructor(
    private val dao: RetrievalLabelDao,
) {

    /**
     * Record one turn's recall.
     *
     * Silently does nothing when [provenance] is absent, which is the point:
     * `RecallOptions.provenance` is empty for reads that are not serving a turn
     * — tool calls and the eval runner itself — and labelling those would fill
     * the corpus with questions the user never asked. [ConversationProvenance.isPresent]
     * is the repo's existing definition of "serving a real turn"; this reuses it
     * rather than restating the predicate.
     *
     * [queryText] is scrubbed before it is stored. It is the user's own words,
     * it will be exported in a backup, and `Redactor` is what the app already
     * uses for text it captured rather than was handed.
     */
    suspend fun record(
        queryText: String,
        memoryIdsInRankOrder: List<String>,
        provenance: ConversationProvenance,
        now: Long = System.currentTimeMillis(),
    ) {
        if (!provenance.isPresent || memoryIdsInRankOrder.isEmpty()) return
        val scrubbed = Redactor.scrub(queryText)
        val rows = memoryIdsInRankOrder.mapIndexed { index, memoryId ->
            RetrievalLabelEntity(
                id = RetrievalLabelEntity.idFor(provenance.conversationId, provenance.turnTimestamp, memoryId),
                conversationId = provenance.conversationId,
                turnTimestamp = provenance.turnTimestamp,
                queryText = scrubbed,
                memoryId = memoryId,
                rank = index + 1,
                createdAt = now,
            )
        }
        runCatching { dao.upsertAll(rows) }
            .onFailure { Log.w(TAG, "retrieval label write failed (non-fatal): ${it.message}", it) }
    }

    /**
     * The consult pass judged these recalled memories to bear on the question.
     *
     * One of only two signals in the app that is genuinely about *this memory
     * for this question* rather than about the answer as a whole, which is why
     * it writes [RetrievalLabelEntity.grade] and the turn-level signals do not.
     * Graded 2 — "relevant" — rather than 3: the pass decided the memory applies,
     * not that it is the ideal answer, and `RetrievalMetrics` reserves 3 for that.
     *
     * Memories recalled and *not* consulted are deliberately left ungraded.
     * Unselected is not the same as irrelevant, and grading them 0 here would
     * manufacture negatives the pass never asserted.
     */
    suspend fun recordConsulted(
        provenance: ConversationProvenance,
        consultedIds: List<String>,
        now: Long = System.currentTimeMillis(),
    ) {
        if (!provenance.isPresent || consultedIds.isEmpty()) return
        runCatching {
            for (memoryId in consultedIds) {
                dao.gradeOne(
                    id = RetrievalLabelEntity.idFor(provenance.conversationId, provenance.turnTimestamp, memoryId),
                    grade = GRADE_RELEVANT,
                    source = SOURCE_HEURISTIC,
                    now = now,
                )
            }
        }.onFailure { Log.w(TAG, "consulted label write failed (non-fatal): ${it.message}", it) }
    }

    /**
     * The user said this memory should not have answered that question.
     *
     * The strongest signal available and the only explicit negative: precise to
     * the pair, and asserted by a person rather than inferred. Graded 0 with
     * source `user`, so a later judge pass can see it was not guesswork.
     */
    suspend fun recordIrrelevantHere(
        provenance: ConversationProvenance,
        memoryId: String,
        now: Long = System.currentTimeMillis(),
    ) {
        if (!provenance.isPresent) return
        runCatching {
            dao.gradeOne(
                id = RetrievalLabelEntity.idFor(provenance.conversationId, provenance.turnTimestamp, memoryId),
                grade = GRADE_IRRELEVANT,
                source = SOURCE_USER,
                now = now,
            )
        }.onFailure { Log.w(TAG, "irrelevant-here label write failed (non-fatal): ${it.message}", it) }
    }

    /**
     * A verdict on the whole answer — thumbs, or a regenerate.
     *
     * Recorded against every memory recalled for that turn, into
     * [RetrievalLabelEntity.heuristicGrade] and never into `grade`. See the DAO
     * for why: a turn-level verdict spread across five memories produces five
     * rows sharing a grade, which nDCG cannot separate and which would dilute
     * the metric rather than inform it. It is kept because the judge sample
     * exists precisely to find out whether signals like this predict relevance.
     */
    suspend fun recordTurnSignal(
        provenance: ConversationProvenance,
        signal: TurnSignal,
    ) {
        if (!provenance.isPresent) return
        runCatching {
            dao.applyTurnHeuristic(
                conversationId = provenance.conversationId,
                turnTimestamp = provenance.turnTimestamp,
                heuristicGrade = signal.heuristicGrade,
                signalsJson = """["${signal.wire}"]""",
            )
        }.onFailure { Log.w(TAG, "turn signal write failed (non-fatal): ${it.message}", it) }
    }

    /**
     * The user rewrote the question and re-sent it.
     *
     * Marked rather than graded down, and excluded from export. An edit says
     * *the question* was wrong, not the memories; grading it as a miss would
     * teach the eval that correctly-retrieved memories for a badly-phrased
     * question are irrelevant, which is the opposite of true.
     */
    suspend fun markSupersededByEdit(provenance: ConversationProvenance) {
        if (!provenance.isPresent) return
        runCatching { dao.markSupersededByEdit(provenance.conversationId, provenance.turnTimestamp) }
            .onFailure { Log.w(TAG, "edit marker write failed (non-fatal): ${it.message}", it) }
    }

    /** A verdict on the answer as a whole. */
    enum class TurnSignal(val wire: String, val heuristicGrade: Int) {
        THUMBS_UP("thumbs_up", GRADE_RELEVANT),
        THUMBS_DOWN("thumbs_down", GRADE_IRRELEVANT),
        REGENERATED("regenerated", GRADE_IRRELEVANT),
    }

    /** Called when a conversation is deleted; see the entity KDoc for why no cascade can do this. */
    suspend fun forgetConversation(conversationId: String) {
        runCatching { dao.deleteForConversation(conversationId) }
            .onFailure { Log.w(TAG, "retrieval label conversation delete failed: ${it.message}", it) }
    }

    suspend fun forgetAll() {
        runCatching { dao.deleteAll() }
            .onFailure { Log.w(TAG, "retrieval label purge failed: ${it.message}", it) }
    }

    /**
     * Drop rows past the retention window.
     *
     * Called from `DecayWorker`, above its `decayEnabled` gate — retention is
     * not a feature the user opted into and must run whether or not decay is on.
     * A retention window with no production caller is a table that grows
     * forever, which `WorkerRunRecorder.prune` shipped as and this deliberately
     * does not.
     */
    suspend fun prune(now: Long = System.currentTimeMillis()) {
        runCatching { dao.deleteOlderThan(now - RETENTION_MS) }
            .onFailure { Log.w(TAG, "retrieval label prune failed: ${it.message}", it) }
    }

    companion object {
        private const val TAG = "RetrievalLabelStore"

        /** Grades, matching `RetrievalMetrics`: 0 irrelevant, 1 related, 2 relevant, 3 ideal. */
        const val GRADE_IRRELEVANT = 0
        const val GRADE_RELEVANT = 2

        const val SOURCE_HEURISTIC = "heuristic"
        const val SOURCE_USER = "user"

        /**
         * 30 days. Long enough to accumulate the ~50 judged queries below which
         * `docs/RETRIEVAL_EVAL.md` puts nDCG@10 noise at ±0.05, short enough
         * that the user's own questions do not linger indefinitely.
         */
        const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }
}
