package com.aura.creative.longform

import com.aura.creative.CreativeGenerationJobDao
import com.aura.creative.CreativeGenerationJobEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Lifecycle of one long-form run. Mirrors the string values the DAO already filters on. */
object LongformStatus {
    /** Created, not yet picked up by a worker. `pendingJobs()` returns these. */
    const val QUEUED = "queued"

    /** A worker is drafting. Also returned by `pendingJobs()`, which is what makes resume work. */
    const val RUNNING = "running"

    /** Cancel requested. Written to Room *before* the worker is cancelled. */
    const val CANCELLING = "cancelling"

    const val SUCCEEDED = "succeeded"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"

    /** Statuses a worker should pick up or continue. */
    val ACTIVE = setOf(QUEUED, RUNNING)

    /** Statuses that mean the run is over, whatever the outcome. */
    val TERMINAL = setOf(SUCCEEDED, FAILED, CANCELLED)
}

/** The brief and settings for a run, stored as JSON in the job row's `requestJson`. */
@Serializable
data class LongformRequest(
    val brief: String = "",
    /** Beats already drafted when the run last stopped. Advisory — the outline is the truth. */
    val completedBeats: Int = 0,
)

/**
 * Durable state for long-form runs, over the existing `creative_generation_jobs`
 * table.
 *
 * That table, its entity and its DAO were already in `MemoryDatabase` v17, Hilt
 * provided, and had **no production writer at all**. Its own KDoc says "survives
 * process death so the user can resume after a crash", and its DAO already
 * carries exactly the queries a durable run needs — `pendingJobs()`,
 * `observeForProject()`, `updateStatus()`, `complete()`, `cleanupOld()`. So the
 * run header for this feature needed no schema change; it needed a caller.
 *
 * The per-beat state deliberately does *not* live here. It lives in
 * `WorldBible.outline`, inside the project's `worldJson`, because that is
 * already the outline the user edits, already rendered into every system prompt,
 * and already carried through backup. Two copies of "which beats are done" would
 * only ever disagree.
 */
@Singleton
class LongformRunStore @Inject constructor(
    private val jobDao: CreativeGenerationJobDao,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun create(projectId: String, branchId: String, brief: String): String {
        val id = UUID.randomUUID().toString()
        jobDao.upsert(
            CreativeGenerationJobEntity(
                id = id,
                projectId = projectId,
                branchId = branchId,
                // A plain string, deliberately not a CapabilityKind entry.
                // Adding one would break the exhaustive `when`s in
                // CapabilityRouter and availablePipelines for a value no
                // capability backend can serve.
                capabilityKind = CAPABILITY_KIND,
                requestJson = json.encodeToString(LongformRequest.serializer(), LongformRequest(brief = brief)),
                status = LongformStatus.QUEUED,
            ),
        )
        return id
    }

    suspend fun get(jobId: String): CreativeGenerationJobEntity? = jobDao.getById(jobId)

    /** Runs this process should pick up on start — the reason a reboot does not lose work. */
    suspend fun pending(): List<CreativeGenerationJobEntity> =
        jobDao.pendingJobs().filter { it.capabilityKind == CAPABILITY_KIND }

    fun observeForProject(projectId: String): Flow<List<CreativeGenerationJobEntity>> =
        jobDao.observeForProject(projectId).map { jobs -> jobs.filter { it.capabilityKind == CAPABILITY_KIND } }

    suspend fun markRunning(jobId: String, progress: Int) {
        jobDao.updateStatus(jobId, LongformStatus.RUNNING, progress, System.currentTimeMillis())
    }

    /**
     * Ask a run to stop.
     *
     * Written to Room **before** WorkManager is told to cancel, so a worker that
     * is mid-scene, or one that re-enqueues in the race window, sees the request
     * rather than carrying on. A cancel that only calls `cancelUniqueWork` can be
     * outrun by the worker's own re-enqueue.
     */
    suspend fun markCancelling(jobId: String) {
        val current = jobDao.getById(jobId) ?: return
        if (current.status in LongformStatus.TERMINAL) return
        jobDao.updateStatus(jobId, LongformStatus.CANCELLING, current.progress, System.currentTimeMillis())
    }

    suspend fun finish(jobId: String, status: String, artifactIds: List<String>) {
        jobDao.complete(
            jobId,
            status,
            json.encodeToString(kotlinx.serialization.builtins.ListSerializer(String.serializer()), artifactIds),
            System.currentTimeMillis(),
        )
    }

    suspend fun fail(jobId: String, code: String, message: String) {
        val current = jobDao.getById(jobId) ?: return
        jobDao.upsert(
            current.copy(
                status = LongformStatus.FAILED,
                errorCode = code,
                errorMessage = message.take(MAX_ERROR_CHARS),
                attempts = current.attempts + 1,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /** Bump the retry counter without ending the run. */
    suspend fun recordAttempt(jobId: String) {
        val current = jobDao.getById(jobId) ?: return
        jobDao.upsert(current.copy(attempts = current.attempts + 1, updatedAt = System.currentTimeMillis()))
    }

    companion object {
        /**
         * Marks a job row as belonging to this feature.
         *
         * The column is a plain String and the table is shared with media
         * generation, so every read here filters on it. Deliberately not a
         * `CapabilityKind` value — see [create].
         */
        const val CAPABILITY_KIND = "LongformText"

        private const val MAX_ERROR_CHARS = 500
    }
}
