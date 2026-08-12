package com.aura.health

import android.util.Log
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records that a worker ran, and what came of it.
 *
 * One wrapper rather than a call at every exit: a worker has several ways out
 * — a disabled toggle, a missing model, an exception, success — and the ones
 * that matter most for "why did nothing happen" are the early returns, which
 * are exactly the ones a hand-placed call gets forgotten at.
 *
 * Never throws. A health record that can break the thing it observes is worse
 * than no health record.
 */
@Singleton
class WorkerRunRecorder @Inject constructor(
    private val dao: WorkerRunDao,
) {

    /** What a worker wants said about its run. */
    data class Result(val outcome: String, val detail: String) {
        companion object {
            fun ok(detail: String) = Result(WorkerRunEntity.OUTCOME_OK, detail)

            /** Ran, did nothing, and here is the precondition that was missing. */
            fun skipped(reason: String) = Result(WorkerRunEntity.OUTCOME_SKIPPED, reason)
        }
    }

    /**
     * Run [block], recording the attempt either way.
     *
     * The row is written *before* the work so a process killed mid-run leaves
     * evidence it started — an unfinished row is itself a finding, and the
     * common cause of a worker producing nothing is being killed rather than
     * failing.
     */
    suspend fun <T> record(worker: String, block: suspend () -> Pair<T, Result>): T? {
        val started = System.currentTimeMillis()
        val id = runCatching { dao.insert(WorkerRunEntity(worker = worker, startedAt = started)) }
            .onFailure { Log.w(TAG, "could not open a run record for $worker", it) }
            .getOrNull()
        return try {
            val (value, result) = block()
            finish(id, result.outcome, result.detail)
            value
        } catch (cancelled: CancellationException) {
            finish(id, WorkerRunEntity.OUTCOME_FAILED, "cancelled")
            throw cancelled
        } catch (t: Throwable) {
            finish(id, WorkerRunEntity.OUTCOME_FAILED, t.message?.take(MAX_DETAIL) ?: t::class.java.simpleName)
            Log.w(TAG, "$worker failed", t)
            null
        }
    }

    suspend fun recent(limit: Int = 50): List<WorkerRunEntity> =
        runCatching { dao.recent(limit) }.getOrDefault(emptyList())

    suspend fun latestPerWorker(): List<WorkerRunEntity> =
        runCatching { dao.latestPerWorker() }.getOrDefault(emptyList())

    /** Trim the log. Called from the same sweep that prunes proactive events. */
    suspend fun prune(now: Long = System.currentTimeMillis()): Int =
        runCatching { dao.deleteOlderThan(now - RETENTION_MS) }.getOrDefault(0)

    private suspend fun finish(id: Long?, outcome: String, detail: String) {
        if (id == null) return
        runCatching { dao.finish(id, System.currentTimeMillis(), outcome, detail.take(MAX_DETAIL)) }
            .onFailure { Log.w(TAG, "could not close run record $id", it) }
    }

    private companion object {
        const val TAG = "WorkerRunRecorder"
        const val MAX_DETAIL = 200
        const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }
}
