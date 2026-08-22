package com.aura.creative

import android.util.Log
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps analysis, and compares it across revisions.
 *
 * The storing half is the boring half. [diffAgainstParent] is the reason this
 * exists: `CreativeRevisionEntity` is append-only with a `parentRevisionId`, so
 * every draft already knows which draft it came from, and once an analysis is
 * attached to each the app can say *what the rewrite actually did* rather than
 * offering another opinion about the new text.
 *
 * That is the gap in every writing tool including this one: they all generate
 * feedback and none of them measure whether the feedback worked. It is the same
 * move `ProactiveOutcomePass` makes for suggestions — optimise for the outcome,
 * not for the interaction — applied to prose.
 */
@Singleton
class CreativeAnalysisStore @Inject constructor(
    private val dao: CreativeAnalysisDao,
    private val revisionDao: CreativeRevisionDao,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Store a tension pass for one revision, replacing any previous pass for it. */
    suspend fun saveTension(
        revisionId: String,
        artifactId: String,
        report: TensionReport,
        note: String = "",
        now: Long = System.currentTimeMillis(),
    ): Boolean = runCatching {
        dao.upsert(
            CreativeAnalysisEntity(
                id = UUID.randomUUID().toString(),
                revisionId = revisionId,
                artifactId = artifactId,
                kind = CreativeAnalysisEntity.KIND_TENSION,
                payloadJson = json.encodeToString(TensionReport.serializer(), report),
                headline = report.meanTension,
                note = note,
                createdAt = now,
            ),
        )
        true
    }.onFailure { Log.w(TAG, "storing tension failed", it) }.getOrDefault(false)

    /**
     * How this revision's tension differs from the one it was written from.
     *
     * Null when there is nothing to compare against — no parent, or the parent
     * was never analysed. Deliberately null rather than an empty diff: "no
     * change" and "no basis for comparison" are different answers and a writer
     * reading a flat list of zeroes would take the first meaning.
     */
    suspend fun diffAgainstParent(revisionId: String): TensionDiff? {
        val revision = runCatching { revisionDao.getById(revisionId) }
            .onFailure { Log.w(TAG, "revision lookup failed", it) }
            .getOrNull() ?: return null
        val parentId = revision.parentRevisionId ?: return null

        val after = read(revisionId, CreativeAnalysisEntity.KIND_TENSION) ?: return null
        val before = read(parentId, CreativeAnalysisEntity.KIND_TENSION) ?: return null

        // Matched by label, not by index. Inserting a scene in the middle shifts
        // every later index by one, which would report the entire second half of
        // the manuscript as changed when nothing in it moved.
        val labels = (before.scenes.map { it.label } + after.scenes.map { it.label }).distinct()
        val deltas = labels.map { label ->
            SceneDelta(
                label = label,
                before = before.scenes.firstOrNull { it.label == label }?.tension,
                after = after.scenes.firstOrNull { it.label == label }?.tension,
            )
        }
        return TensionDiff(
            parentRevisionId = parentId,
            meanBefore = before.meanTension,
            meanAfter = after.meanTension,
            scenes = deltas,
        )
    }

    /** The trend across an artifact's history, newest first. */
    suspend fun trend(artifactId: String, limit: Int = 20): List<Float> =
        runCatching {
            dao.forArtifact(artifactId, CreativeAnalysisEntity.KIND_TENSION, limit).map { it.headline }
        }.onFailure { Log.w(TAG, "trend read failed", it) }.getOrDefault(emptyList())

    private suspend fun read(revisionId: String, kind: String): TensionReport? =
        runCatching {
            dao.forRevision(revisionId, kind)
                ?.let { json.decodeFromString(TensionReport.serializer(), it.payloadJson) }
        }.onFailure { Log.w(TAG, "analysis read failed", it) }.getOrNull()

    private companion object {
        const val TAG = "CreativeAnalysisStore"
    }
}

/**
 * What a rewrite did.
 *
 * [improved] is deliberately not a verdict on the writing. It says the mean
 * tension moved up, which is what was measured — calling that "better" would be
 * the analyser overreaching past its own evidence, and a quiet scene added on
 * purpose would fail it.
 */
data class TensionDiff(
    val parentRevisionId: String,
    val meanBefore: Float,
    val meanAfter: Float,
    val scenes: List<SceneDelta> = emptyList(),
) {
    val meanChange: Float get() = meanAfter - meanBefore
    val improved: Boolean get() = meanChange > 0f

    /** The scenes that actually moved, largest movement first. */
    fun moved(): List<SceneDelta> =
        scenes.filter { it.change != 0 }.sortedByDescending { kotlin.math.abs(it.change) }

    /** Scenes that were flat before and still are — the notes that went unacted-on. */
    fun stillFlat(threshold: Int = TensionReport.FLAT_AT): List<SceneDelta> =
        scenes.filter { (it.before ?: 99) <= threshold && (it.after ?: 99) <= threshold }
}
