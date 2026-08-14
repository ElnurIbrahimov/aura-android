package com.aura.creative

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.serialization.Serializable

/**
 * What an analysis pass concluded, kept, and attached to the revision it read.
 *
 * The creative package could already analyse a manuscript several ways — tension
 * per scene, how characters change, the author's voice — and threw every one of
 * them away. `TensionAnalyzer` returned a `Flow<String>` and declared
 * `TensionReport`/`SceneScore` types it never once constructed; character
 * progression ran a model call after every draft turn and put 200 truncated
 * characters into a snackbar; the voice profile lived in a `MutableStateFlow`
 * and died with the ViewModel. Every other subsystem in this app accumulates —
 * memory, the graph, corrections, beliefs, proactive outcomes — and this was the
 * one place nothing did.
 *
 * The point is not storage for its own sake. It is that
 * [CreativeRevisionEntity] is append-only with a `parentRevisionId`, and its own
 * KDoc says the chain "enables diff, restore, and lineage" — so once analysis is
 * keyed to a revision, the app can answer the question a writer actually has:
 * **is this draft better than the last one?** Scene 4 went 3 → 6 after the
 * rewrite; scenes 7–9 are still flat. No writing tool answers that. They all
 * give feedback and none of them measure whether the feedback worked.
 *
 * One row per (revision, kind): re-running an analysis replaces the old one,
 * because two tension reports for the same bytes are not two data points.
 */
@Entity(
    tableName = "creative_analysis",
    foreignKeys = [
        ForeignKey(
            entity = CreativeRevisionEntity::class,
            parentColumns = ["id"],
            childColumns = ["revisionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["revisionId", "kind"], unique = true),
        Index("artifactId"),
    ],
)
data class CreativeAnalysisEntity(
    @PrimaryKey val id: String,
    val revisionId: String,
    /** Denormalised so a whole artifact's history is one query, not a join per revision. */
    val artifactId: String,
    /** [KIND_TENSION], [KIND_PROGRESSION] or [KIND_VOICE]. */
    val kind: String,
    /** The structured result. Shape depends on [kind]; see [TensionReport]. */
    val payloadJson: String,
    /**
     * One number that means "how did this draft do", comparable across revisions.
     *
     * Kept as a column rather than dug out of [payloadJson] on every read,
     * because the headline use is a trend across a chain of revisions and a
     * JSON parse per row to draw one sparkline is the kind of thing that makes a
     * feature feel slow for no reason.
     */
    val headline: Float = 0f,
    /** Non-null when the model refused or the parse failed. Kept, not discarded. */
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val KIND_TENSION = "tension"
        const val KIND_PROGRESSION = "progression"
        const val KIND_VOICE = "voice"
    }
}

/** A scene's tension, 1 (calm) to 10 (peak crisis). */
@Serializable
data class SceneScore(
    val label: String,
    val tension: Int,
    val note: String = "",
)

/**
 * A whole-manuscript tension pass.
 *
 * This type existed before, on `TensionAnalyzer`, and was never constructed —
 * the analyser streamed prose and the declared shape was decoration. It is real
 * now, and it is what gets stored.
 */
@Serializable
data class TensionReport(
    val scenes: List<SceneScore> = emptyList(),
    val diagnosis: String = "",
    val recommendations: List<String> = emptyList(),
) {
    /** The headline: mean tension, or 0 when there are no scenes. */
    val meanTension: Float
        get() = if (scenes.isEmpty()) 0f else scenes.sumOf { it.tension }.toFloat() / scenes.size

    /** Scenes at or below this are the ones a writer is being told to fix. */
    fun flat(threshold: Int = FLAT_AT): List<SceneScore> = scenes.filter { it.tension <= threshold }

    companion object {
        const val FLAT_AT = 3
    }
}

/** One scene's movement between two revisions. */
data class SceneDelta(
    val label: String,
    val before: Int?,
    val after: Int?,
) {
    val change: Int get() = (after ?: 0) - (before ?: 0)
    val isNew: Boolean get() = before == null
    val isGone: Boolean get() = after == null
}

@Dao
interface CreativeAnalysisDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: CreativeAnalysisEntity)

    @Query("SELECT * FROM creative_analysis WHERE revisionId = :revisionId AND kind = :kind LIMIT 1")
    suspend fun forRevision(revisionId: String, kind: String): CreativeAnalysisEntity?

    /** Newest first, for the trend across an artifact's history. */
    @Query("SELECT * FROM creative_analysis WHERE artifactId = :artifactId AND kind = :kind ORDER BY createdAt DESC LIMIT :limit")
    suspend fun forArtifact(artifactId: String, kind: String, limit: Int = 20): List<CreativeAnalysisEntity>

    @Query("SELECT * FROM creative_analysis ORDER BY createdAt ASC")
    suspend fun allForBackup(): List<CreativeAnalysisEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<CreativeAnalysisEntity>)

    @Query("DELETE FROM creative_analysis")
    suspend fun deleteAll()
}
