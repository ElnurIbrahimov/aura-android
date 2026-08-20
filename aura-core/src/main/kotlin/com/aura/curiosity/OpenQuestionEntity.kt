package com.aura.curiosity

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Something Aura wants to know, and cannot find out on its own.
 *
 * Aura has a curiosity drive and no way to express it.
 * `IntrinsicMotivation.CURIOSITY` computes its intensity from
 * `KnowledgeGraphDao.gapNodeCount()` — nodes it has recorded and knows almost
 * nothing about — and renders that into the system prompt as a *count*: "14
 * unexplored topics". The model is told it is curious and not what about, only
 * inside a turn the user started. And `NarrativeSelf.unresolvedQuestions`, the
 * field built to hold exactly this, has never held anything: its only writer
 * seeds it from its own previous value.
 *
 * A row here is the missing half. Every question names a **real row** —
 * [subjectId] is a graph node, a contradiction, or a memory — so "is this still
 * a gap" stays re-checkable and asking is something that can finish. That is
 * what separates this from the usual assistant curiosity, which is a
 * personality affect: unfalsifiable, and therefore infinite.
 */
@Entity(
    tableName = "open_questions",
    indices = [
        Index("status"),
        // Enforces "never ask about this again": a dismissal is recorded
        // against the subject, and the scanner looks here before proposing.
        Index("subjectKind", "subjectId"),
        Index("createdAt"),
    ],
)
data class OpenQuestionEntity(
    @PrimaryKey val id: String,
    /** Why: [KIND_GAP], [KIND_CONTRADICTION], [KIND_STALE] or [KIND_SHALLOW]. */
    val kind: String,
    /** [SUBJECT_KG_NODE], [SUBJECT_CONTRADICTION] or [SUBJECT_MEMORY]. */
    val subjectKind: String,
    val subjectId: String,
    /** The question as a sentence, written for a person to read. */
    val question: String,
    /** [STATUS_OPEN], [STATUS_ANSWERED], [STATUS_DISMISSED], [STATUS_RESEARCHED]. */
    val status: String = STATUS_OPEN,
    /**
     * Whether only the user can answer this, or the world can.
     *
     * Set when the question is written and acted on by the self-serve
     * researcher. A question about a person is always [ANSWERABLE_USER]:
     * looking up a name on the internet is not curiosity about someone, it is
     * something else entirely.
     */
    val answerable: String = ANSWERABLE_USER,
    /**
     * How much this subject was judged to matter, 0-100. 0 means never scored.
     *
     * Only one question is open at a time, so this does not order a queue — it records what
     * the scan-time decision was, which is what makes the choice arguable after the fact.
     */
    val voiScore: Int = 0,
    /** Why it was chosen, in the model's words, or null when it did not say. */
    val voiReason: String? = null,
    /** The memory the answer became, once there is one. */
    val answerMemoryId: String? = null,
    /** Last time this was actually put in front of the user. */
    val askedAt: Long? = null,
    val timesAsked: Int = 0,
    val answeredAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val KIND_GAP = "gap"
        const val KIND_CONTRADICTION = "contradiction"
        const val KIND_STALE = "stale"

        /** Well-connected and never used: recorded without being understood. */
        const val KIND_SHALLOW = "shallow"

        /**
         * Aura asking whether one of its own beliefs still holds.
         *
         * Unlike the other kinds, this is not a gap in what Aura knows — it is a
         * test of what Aura already claims to know. The answer becomes a
         * `claim_resolutions` verdict, which is the only honest input the
         * calibration report has: nothing else in the system can distinguish a
         * belief that was right from one that merely went uncontradicted.
         */
        const val KIND_VERIFICATION = "verification"

        const val SUBJECT_KG_NODE = "kg_node"
        const val SUBJECT_CONTRADICTION = "contradiction"
        const val SUBJECT_MEMORY = "memory"

        /**
         * A row in `beliefs`, written by `BeliefVerificationAuthor`.
         *
         * Keeps this table's invariant that every subject names a real row, so
         * "is this still worth asking" stays re-checkable, and inherits the
         * per-subject dismissal path — here meaning "stop asking me about this
         * particular belief", which is the right granularity for a claim.
         */
        const val SUBJECT_BELIEF = "belief"

        /**
         * A row in `projects`, written by `ProjectLedgerExtractor`.
         *
         * Reuses this table rather than adding a fourth `kind` to
         * `project_notes`, which would have reimplemented the dismissal path,
         * the `(subjectKind, subjectId)` index, the curiosity scanner and the
         * Mind screen's section, all of which this table already has.
         *
         * The subject is the **project**, not a topic within it, so
         * [STATUS_DISMISSED] means "stop asking me about this project" — a
         * coarser instruction than the other subject kinds carry, and a coherent
         * one. It also keeps the invariant this table's KDoc rests on: every
         * [subjectId] names a real row, so "is this still worth asking" stays
         * re-checkable by looking the project up.
         */
        const val SUBJECT_PROJECT = "project"

        /** Live: waiting to be asked, or asked and not yet resolved. */
        const val STATUS_OPEN = "open"
        const val STATUS_ANSWERED = "answered"

        /** The user said never ask about this. Permanent, per subject. */
        const val STATUS_DISMISSED = "dismissed"

        /** Aura found the answer itself. */
        const val STATUS_RESEARCHED = "researched"

        const val ANSWERABLE_USER = "user"
        const val ANSWERABLE_WORLD = "world"
    }
}

@Dao
interface OpenQuestionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: OpenQuestionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<OpenQuestionEntity>)

    @Query("SELECT * FROM open_questions WHERE id = :id")
    suspend fun byId(id: String): OpenQuestionEntity?

    /**
     * The question currently in play, if there is one.
     *
     * Singular by design. One open question at a time is the entire defence
     * against an assistant that plays twenty questions, and it is enforced here
     * — in the store — rather than left as a rule someone remembers.
     */
    @Query("SELECT * FROM open_questions WHERE status = 'open' ORDER BY createdAt ASC LIMIT 1")
    suspend fun current(): OpenQuestionEntity?

    @Query("SELECT COUNT(*) FROM open_questions WHERE status = 'open'")
    suspend fun openCount(): Int

    /** Subjects already spoken for, in one query, so the scanner can skip them. */
    @Query("SELECT subjectKind || '/' || subjectId FROM open_questions")
    suspend fun claimedSubjects(): List<String>

    @Query("SELECT * FROM open_questions WHERE status = :status ORDER BY createdAt DESC LIMIT :limit")
    suspend fun byStatus(status: String, limit: Int = 50): List<OpenQuestionEntity>

    @Query(
        "SELECT * FROM open_questions WHERE status = 'open' AND answerable = :answerable " +
            "ORDER BY createdAt ASC LIMIT :limit",
    )
    suspend fun openAnswerableBy(answerable: String, limit: Int = 5): List<OpenQuestionEntity>

    @Query("SELECT * FROM open_questions ORDER BY createdAt ASC")
    suspend fun allForExport(): List<OpenQuestionEntity>

    @Query(
        "UPDATE open_questions SET status = :status, answerMemoryId = :answerMemoryId, " +
            "answeredAt = :now WHERE id = :id AND status = 'open'",
    )
    suspend fun close(id: String, status: String, answerMemoryId: String?, now: Long): Int

    @Query("UPDATE open_questions SET askedAt = :now, timesAsked = timesAsked + 1 WHERE id = :id")
    suspend fun markAsked(id: String, now: Long)

    /**
     * When a question of this kind was last written. Null if never.
     *
     * Drives the verification cooldown. Reads `createdAt` rather than `askedAt`
     * because the cooldown bounds how often Aura *decides* to ask; a question
     * written and never surfaced still occupies the single open slot.
     */
    @Query("SELECT MAX(createdAt) FROM open_questions WHERE kind = :kind")
    suspend fun lastCreatedAtForKind(kind: String): Long?

    @Query("DELETE FROM open_questions")
    suspend fun deleteAll()
}
