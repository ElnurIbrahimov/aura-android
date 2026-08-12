package com.aura.memory

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * The user telling Aura it was wrong.
 *
 * Aura collected three separate signals about being wrong and consumed none of
 * them: `memory_feedback` had no readers at all, skill failures were recorded
 * against the literal id `"_unknown_"`, and neither reached retrieval. What was
 * missing was not another thumbs-down — it was a way for a correction to have
 * an effect, and a way to say *which kind* of wrong.
 *
 * The four kinds are the point. "I never lived in Baku" and "I moved away from
 * Baku" used to produce the identical outcome — a slow decay — and they are not
 * the same fact. A mistake should stop being retrievable; a fact the world
 * moved past is history with an end date and a successor; a memory that is true
 * but surfaced for the wrong question needs demoting for that question only,
 * not deleting; and a bad answer is about the skill that produced it, not about
 * any memory at all.
 *
 * A correction is additive. Nothing here deletes a memory, so being wrong about
 * being wrong is recoverable — which matters most for exactly the users who
 * correct things most readily.
 */
@Entity(
    tableName = "corrections",
    indices = [
        Index("targetId"),
        Index("kind"),
        Index("createdAt"),
        Index("undoneAt"),
    ],
)
data class CorrectionEntity(
    @PrimaryKey val id: String,
    /** [TARGET_MEMORY] or [TARGET_SKILL]. */
    val targetKind: String,
    val targetId: String,
    /** One of [NEVER_TRUE], [NO_LONGER_TRUE], [IRRELEVANT_HERE], [BAD_ANSWER]. */
    val kind: String,
    /** For [NO_LONGER_TRUE]: the memory that replaces the corrected one. */
    val replacementId: String? = null,
    /** What the user typed, when they typed anything. */
    val note: String = "",
    /**
     * For [IRRELEVANT_HERE]: the question this memory should not have answered.
     *
     * Kept with its embedding rather than as text alone, because the penalty
     * has to apply to *questions like this one* — "what should I cook" and
     * "dinner ideas" are the same question — and a string comparison cannot say
     * that. Null when the correction is not scoped to a query.
     */
    val queryText: String = "",
    val queryEmbedding: ByteArray? = null,
    val sourceConversationId: String = "",
    val sourceTurnTimestamp: Long = 0L,
    /** What the one-hop propagation touched, as [PropagatedItem] JSON. */
    val propagatedJson: String = "[]",
    val createdAt: Long = System.currentTimeMillis(),
    /** Set when the user undid this correction; undone rows have no effect. */
    val undoneAt: Long? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CorrectionEntity) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    companion object {
        const val TARGET_MEMORY = "memory"
        const val TARGET_SKILL = "skill"

        /** A mistake. It stops being retrievable. */
        const val NEVER_TRUE = "never_true"

        /** It was right and the world moved. The replacement wins; this becomes history. */
        const val NO_LONGER_TRUE = "no_longer_true"

        /** True, but it should not have surfaced for this kind of question. */
        const val IRRELEVANT_HERE = "irrelevant_here"

        /** The skill that produced this answer did badly. */
        const val BAD_ANSWER = "bad_answer"

        val MEMORY_KINDS = setOf(NEVER_TRUE, NO_LONGER_TRUE, IRRELEVANT_HERE)
    }
}

/**
 * One derived artifact a correction reached, kept so the propagation can be
 * undone exactly. Stores the whole row, not a pointer, because undo has to work
 * after the original is gone.
 */
@kotlinx.serialization.Serializable
data class PropagatedItem(
    val kind: String,
    val id: String,
    val snapshotJson: String,
) {
    companion object {
        const val KG_EDGE = "kg_edge"
    }
}

@Dao
interface CorrectionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: CorrectionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<CorrectionEntity>)

    @Query("SELECT * FROM corrections WHERE id = :id")
    suspend fun byId(id: String): CorrectionEntity?

    @Query("SELECT * FROM corrections WHERE targetId = :targetId AND undoneAt IS NULL ORDER BY createdAt DESC")
    suspend fun forTarget(targetId: String): List<CorrectionEntity>

    /**
     * Live scoped demotions, for the recall path.
     *
     * Read whole rather than per-candidate: this is one bounded query per
     * recall instead of one per memory in the pool, and the set is small by
     * nature — it grows only when the user says something surfaced in the wrong
     * place.
     */
    @Query(
        "SELECT * FROM corrections WHERE kind = :kind AND undoneAt IS NULL " +
            "AND queryEmbedding IS NOT NULL ORDER BY createdAt DESC LIMIT :limit",
    )
    suspend fun scopedDemotions(
        kind: String = CorrectionEntity.IRRELEVANT_HERE,
        limit: Int = 200,
    ): List<CorrectionEntity>

    @Query("SELECT * FROM corrections WHERE undoneAt IS NULL ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 50): List<CorrectionEntity>

    @Query("SELECT * FROM corrections ORDER BY createdAt ASC")
    suspend fun allForExport(): List<CorrectionEntity>

    @Query("UPDATE corrections SET undoneAt = :now WHERE id = :id AND undoneAt IS NULL")
    suspend fun markUndone(id: String, now: Long): Int

    @Query("SELECT COUNT(*) FROM corrections WHERE undoneAt IS NULL")
    suspend fun count(): Int

    @Query("DELETE FROM corrections")
    suspend fun deleteAll()
}
