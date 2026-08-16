package com.aura.memory

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * One memory, as retrieved for one question, and how good that turned out to be.
 *
 * The retrieval eval harness measures fusion, BM25, candidate pools and decay
 * against `corpus.jsonl` and `queries.jsonl` — fixtures which are synthetic, and
 * whose absolute scores `docs/RETRIEVAL_EVAL.md` says mean nothing.
 * `EvalFixtures.isScaffold()` forces Gate B to print *inconclusive* because of
 * it. The missing input is judgments: which memory actually answers which
 * question. This table is where they accumulate from ordinary use, instead of
 * from a weekend of hand-grading.
 *
 * **Rows are self-contained.** [queryText] and [rank] are stored rather than
 * looked up from the turn, because the turn may be gone: conversations are
 * deleted, and `ConversationCompactor` drops earlier turns from the wire. A row
 * that merely pointed at a turn would be unjoinable long before it was exported.
 *
 * **There is no foreign key, and there cannot be one.** `conversations` lives in
 * `ConversationDatabase`; this lives in `MemoryDatabase`. SQLite has no
 * cross-database foreign keys, so orphans are the default state and every
 * deletion path is wired by hand — see [RetrievalLabelDao.deleteForConversation]
 * and its callers. A foreign key onto `memories` would be wrong for a different
 * reason: it would cascade labels away when a memory is forgotten, destroying
 * exactly the negative evidence worth keeping.
 */
@Entity(
    tableName = "retrieval_labels",
    indices = [
        Index("conversationId"),
        Index("createdAt"),
        Index(value = ["sampled", "grade"]),
    ],
)
data class RetrievalLabelEntity(
    /** Derived, never random — see [idFor]. */
    @PrimaryKey val id: String,
    val conversationId: String,
    /** Identifies the turn within the conversation. */
    val turnTimestamp: Long,
    /** The user's question, scrubbed through `Redactor` before it gets here. */
    val queryText: String,
    val memoryId: String,
    /** 1-based position in what recall returned. */
    val rank: Int,
    /**
     * Graded relevance, 0..3, matching `RetrievalMetrics` exactly: 0 irrelevant,
     * 1 related, 2 relevant, 3 ideal. `recallAt` counts >= 1 and
     * `reciprocalRank` counts >= 2, so the scale is not cosmetic.
     *
     * **Null means observed but not yet judged**, which is the normal state for
     * most rows. It must not default to 0 — `RetrievalMetrics` treats an absent
     * or zero grade as "irrelevant" and would score every unjudged recall
     * against the ranker.
     */
    val grade: Int? = null,
    /** `heuristic`, `judge` or `user`. Empty while [grade] is null. */
    val gradeSource: String = "",
    /**
     * What the cheap signals thought, kept even after a judge overwrites
     * [grade]. Calibrating the heuristics against the judge is the whole reason
     * for sampling; fusing the two into one column would destroy the comparison.
     */
    val heuristicGrade: Int? = null,
    /** Which signals fired, as a JSON array. Debugging, not scoring. */
    val signalsJson: String = "[]",
    /**
     * Whether this turn is in the judge's sample.
     *
     * Decided once per *turn*, never per row: sampling individual memories would
     * judge a fraction of each query and never assemble a complete judged set
     * for any of them.
     */
    val sampled: Boolean = false,
    val judgedAt: Long? = null,
    /** `lexical`, `synonym-only`, `deictic`, … Assigned by the judge. */
    val queryClass: String? = null,
    /**
     * The user rewrote the question and re-sent it.
     *
     * Recorded, and excluded from export rather than graded down. An edit means
     * *the question* was wrong, not the memories; grading it as a miss would
     * teach the eval that correctly-retrieved memories for a badly-phrased
     * question are irrelevant, which is the opposite of true.
     */
    val supersededByEdit: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        /**
         * The primary key, derived from what the row is about.
         *
         * A retry re-runs recall for the same turn and gets the same memories
         * back. Under a random id that would write a second row per memory per
         * attempt, and the export would count one query's judgments repeatedly —
         * silently weighting whichever queries the user happened to retry.
         * Deriving the key makes the second write an update, and lets the DAO
         * use `@Upsert` (a real UPDATE) rather than `@Insert(REPLACE)`, which is
         * a DELETE plus an INSERT and the thing `CascadeParentReplaceAuditTest`
         * exists to keep off cascade parents.
         */
        fun idFor(conversationId: String, turnTimestamp: Long, memoryId: String): String =
            "$conversationId|$turnTimestamp|$memoryId"
    }
}

@Dao
interface RetrievalLabelDao {

    @Upsert
    suspend fun upsert(row: RetrievalLabelEntity)

    @Upsert
    suspend fun upsertAll(rows: List<RetrievalLabelEntity>)

    @Query("SELECT * FROM retrieval_labels ORDER BY createdAt ASC")
    suspend fun all(): List<RetrievalLabelEntity>

    @Query("SELECT * FROM retrieval_labels WHERE conversationId = :conversationId ORDER BY turnTimestamp ASC, rank ASC")
    suspend fun forConversation(conversationId: String): List<RetrievalLabelEntity>

    /** The judge's work queue. */
    @Query("SELECT * FROM retrieval_labels WHERE sampled = 1 AND grade IS NULL ORDER BY createdAt ASC LIMIT :limit")
    suspend fun ungradedSampled(limit: Int): List<RetrievalLabelEntity>

    /**
     * How many distinct turns are already sampled since [since].
     *
     * Counted by turn rather than by row because sampling draws to a target
     * number of *queries*. A rate cannot reach the ~50-query floor
     * `docs/RETRIEVAL_EVAL.md` sets: 30 days at a plausible 20 recall-turns a
     * day is ~600 turns, and 5% of that is ~30 queries — permanently below the
     * point where a 3% retrieval gain is distinguishable from luck.
     */
    @Query(
        "SELECT COUNT(DISTINCT conversationId || '|' || turnTimestamp) FROM retrieval_labels " +
            "WHERE sampled = 1 AND createdAt >= :since",
    )
    suspend fun countSampledTurnsSince(since: Long): Int

    /**
     * Explicit, because no cascade can reach here — see the entity KDoc. Called
     * from every path that removes a conversation.
     */
    @Query("DELETE FROM retrieval_labels WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: String)

    @Query("DELETE FROM retrieval_labels WHERE createdAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM retrieval_labels")
    suspend fun deleteAll()
}
