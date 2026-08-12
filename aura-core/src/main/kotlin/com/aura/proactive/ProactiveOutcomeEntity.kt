package com.aura.proactive

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Whether a proactive suggestion actually helped.
 *
 * Every assistant that nudges you optimises for engagement — did you tap. That
 * is the metric that produces notifications engineered to be tapped rather than
 * to be right, and it is why notification feeds get worse over time. The
 * stronger question is whether the thing the suggestion was worried about
 * resolved, and Aura can answer it because it owns the tables: did the stuck
 * task get finished or touched, did the contradiction go away, did a
 * conversation start.
 *
 * So this row records what a suggestion was *about* — the prose in
 * `proactive_events` cannot be re-queried — and what became of it.
 *
 * Its own table rather than more rows in `proactive_interactions`, for four
 * reasons that are breakages rather than preferences: that table's `summary()`
 * feeds the salience appetite multiplier and its `recent(20)` feeds the
 * motivation threshold, so extra rows would move both for reasons unrelated to
 * anything the user did; its rows are bucketed by timestamp to learn *when* the
 * user is receptive, and an outcome row's timestamp is when a six-hourly worker
 * happened to run; and an interaction row has nowhere to put a subject, a
 * baseline or a horizon.
 *
 * Deliberately **no foreign key** to `proactive_events`: `ProactiveEvents.init`
 * sweeps events older than thirty days on every app start, and a cascade would
 * silently delete the evidence the interruption ledger counts.
 */
@Entity(
    tableName = "proactive_outcomes",
    indices = [
        Index(value = ["outcome", "dueAt"]),
        Index(value = ["findingType", "postedAt"]),
    ],
)
data class ProactiveOutcomeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** The `proactive_events` row that was surfaced. Not an FK — see the class KDoc. */
    val eventId: Long,

    /** [ProactiveFindingType.wire], denormalised so the ledger can group without a join. */
    val findingType: String,

    /** `task`, `memory_set`, `kg_node_set`, `conversation`, `task_set`, or `none`. */
    val subjectKind: String,

    /** JSON array of row ids. `"[]"` when the subject is a predicate rather than rows. */
    val subjectIds: String = "[]",

    /** What the subject looked like when the suggestion went out. */
    val baselineJson: String = "{}",

    /** `card` or `notification`. The ledger's revocation rule only counts notifications. */
    val surface: String = SURFACE_CARD,

    val postedAt: Long,

    /** When to check. `postedAt + horizon`, or 0 for a subject with no observable outcome. */
    val dueAt: Long = 0L,

    /** `pending`, `resolved`, `ignored`, or `unobservable`. */
    val outcome: String = OUTCOME_PENDING,

    val outcomeAt: Long = 0L,

    /** Why, in words. Rendered to the user, so it is a sentence rather than a code. */
    val outcomeReason: String = "",
) {
    companion object {
        const val OUTCOME_PENDING = "pending"
        const val OUTCOME_RESOLVED = "resolved"
        const val OUTCOME_IGNORED = "ignored"

        /**
         * The suggestion was real and its effect cannot be seen from here.
         *
         * Three of the eight checks are permanently this, and saying so is the
         * honest alternative to inventing a checker that measures something
         * adjacent and calls it success.
         */
        const val OUTCOME_UNOBSERVABLE = "unobservable"

        const val SURFACE_CARD = "card"
        const val SURFACE_NOTIFICATION = "notification"

        const val SUBJECT_NONE = "none"
        const val SUBJECT_TASK = "task"
        const val SUBJECT_TASK_SET = "task_set"
        const val SUBJECT_MEMORY_SET = "memory_set"
        const val SUBJECT_KG_NODE_SET = "kg_node_set"
        const val SUBJECT_CONVERSATION = "conversation"

        /** Statuses that count toward the ledger. Pending and unobservable do not. */
        val CLOSED = setOf(OUTCOME_RESOLVED, OUTCOME_IGNORED)
    }
}

@Dao
interface ProactiveOutcomeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: ProactiveOutcomeEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<ProactiveOutcomeEntity>)

    /** What the checker has to look at. Index-covered and bounded. */
    @Query(
        "SELECT * FROM proactive_outcomes WHERE outcome = 'pending' AND dueAt > 0 AND dueAt <= :now " +
            "ORDER BY dueAt ASC LIMIT :limit",
    )
    suspend fun due(now: Long, limit: Int = 50): List<ProactiveOutcomeEntity>

    /** Pending rows past the point where waiting longer proves anything. */
    @Query("SELECT * FROM proactive_outcomes WHERE outcome = 'pending' AND postedAt < :before LIMIT :limit")
    suspend fun staleOpen(before: Long, limit: Int = 100): List<ProactiveOutcomeEntity>

    @Query(
        "UPDATE proactive_outcomes SET outcome = :outcome, outcomeAt = :at, outcomeReason = :reason WHERE id = :id",
    )
    suspend fun close(id: Long, outcome: String, at: Long, reason: String)

    /** The ledger's counts. One row per (type, outcome). */
    @Query(
        "SELECT findingType, outcome, COUNT(*) as count FROM proactive_outcomes " +
            "WHERE postedAt >= :since GROUP BY findingType, outcome",
    )
    suspend fun tallySince(since: Long): List<OutcomeTally>

    /** When the successes happened, for the hour rule. */
    @Query(
        "SELECT postedAt FROM proactive_outcomes WHERE findingType = :type AND outcome = 'resolved' " +
            "AND postedAt >= :since",
    )
    suspend fun resolvedTimesSince(type: String, since: Long): List<Long>

    /** Closed rows, for learning which hours a suggestion actually lands in. */
    @Query(
        "SELECT postedAt, outcome FROM proactive_outcomes WHERE outcome IN ('resolved','ignored') " +
            "AND postedAt >= :since ORDER BY postedAt DESC LIMIT 200",
    )
    suspend fun tallyForTiming(since: Long): List<OutcomeTiming>

    @Query("SELECT * FROM proactive_outcomes WHERE eventId IN (:eventIds)")
    suspend fun forEvents(eventIds: List<Long>): List<ProactiveOutcomeEntity>

    @Query("SELECT COUNT(*) FROM proactive_outcomes WHERE findingType = :type AND surface = 'notification' AND postedAt >= :since")
    suspend fun notificationsSince(type: String, since: Long): Int

    @Query("SELECT COUNT(*) FROM proactive_outcomes WHERE surface = 'notification' AND postedAt >= :since")
    suspend fun allNotificationsSince(since: Long): Int

    @Query("DELETE FROM proactive_outcomes WHERE postedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("SELECT * FROM proactive_outcomes ORDER BY postedAt ASC")
    suspend fun allForBackup(): List<ProactiveOutcomeEntity>

    @Query("DELETE FROM proactive_outcomes")
    suspend fun deleteAll()
}

/** When a closed outcome was posted, and how it went. */
data class OutcomeTiming(val postedAt: Long, val outcome: String)

/** One `(findingType, outcome)` bucket. */
data class OutcomeTally(
    val findingType: String,
    val outcome: String,
    val count: Int,
)
