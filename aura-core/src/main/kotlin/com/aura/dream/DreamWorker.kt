package com.aura.dream

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aura.data.UserPreferences
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * WorkManager periodic worker that runs one [DreamConsolidator]
 * consolidation cycle. Scheduled every 24h by
 * [com.aura.proactive.ProactiveScheduler.scheduleDream] with
 * `batteryNotLow + charging` constraints — the "overnight" semantic.
 *
 * Two entry points:
 *  - [doWork] — called by WorkManager on its periodic schedule
 *  - [runNow] — called by the Settings "Run now" button (one-shot
 *    WorkRequest) and by [com.aura.proactive.ProactiveRunner] if it
 *    ever wants to fire a dream pass on user demand
 *
 * Idempotency: the worker has no internal state. Re-running
 * produces the same summaries (clusterId-keyed upsert in
 * [DreamConsolidationDao.insert]).
 *
 * Gating: if [UserPreferences.dreamEnabled] flips off between
 * scheduling and execution, the worker exits with [Result.success]
 * without doing work — that's the polite no-op, not a failure.
 * WorkManager will not retry the gated-off case.
 */
@HiltWorker
class DreamWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val consolidator: DreamConsolidator,
    private val userPreferences: UserPreferences,
    private val recorder: com.aura.health.WorkerRunRecorder? = null,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runNow()

    /**
     * Run a single dream cycle. Exposed publicly so the Settings
     * "Run now" path and any test that wants to drive the cycle
     * synchronously can call it directly without re-enqueueing
     * WorkManager work.
     */
    suspend fun runNow(): Result {
        // Recorded either way. An empty dream database used to be unreadable —
        // "never fired" and "fired and found nothing to consolidate" look
        // identical from outside, and both were common.
        return recorder?.record("DreamWorker") { runCycle() to lastOutcome }
            ?: runCycle()
    }

    private var lastOutcome: com.aura.health.WorkerRunRecorder.Result =
        com.aura.health.WorkerRunRecorder.Result.ok("")

    private suspend fun runCycle(): Result {
        return try {
            if (!userPreferences.dreamEnabled.first()) {
                // Gated off — exit cleanly so WorkManager doesn't retry.
                lastOutcome = com.aura.health.WorkerRunRecorder.Result.skipped("dreams are switched off")
                return Result.success()
            }
            val report = consolidator.runCycle()
            if (report.summariesWritten > 0 || report.clustersFormed > 0) {
                userPreferences.recordDreamRun(report)
            }
            lastOutcome = if (report.summariesWritten > 0) {
                com.aura.health.WorkerRunRecorder.Result.ok(
                    "${report.summariesWritten} summaries, ${report.clustersFormed} clusters" +
                        if (report.questionsRaised > 0) ", raised a question" else "",
                )
            } else {
                com.aura.health.WorkerRunRecorder.Result.skipped(
                    "nothing to consolidate (${report.memoriesProcessed} memories, " +
                        "${report.clustersFormed} clusters above the minimum)",
                )
            }
            Result.success(
                androidx.work.Data.Builder()
                    .putInt("summariesWritten", report.summariesWritten)
                    .putInt("clustersFormed", report.clustersFormed)
                    .putInt("totalCharsSaved", report.totalCharsSaved)
                    .putLong("durationMs", report.durationMs)
                    .build(),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("DreamWorker", "dream cycle failed: ${e.message}", e)
            lastOutcome = com.aura.health.WorkerRunRecorder.Result(
                com.aura.health.WorkerRunEntity.OUTCOME_FAILED,
                e.message ?: e::class.java.simpleName,
            )
            // Capped, like EvolutionWorker. An uncapped retry on a periodic
            // worker is an unbounded backoff loop against a fault that is
            // usually not transient, and WorkManager keeps every attempt
            // alive across reboots. Three tries, then let the next scheduled
            // run be the retry.
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        /**
         * The unique WorkManager name. Used by
         * [com.aura.proactive.ProactiveScheduler.scheduleDream] and
         * `cancelDream`. Picking a stable constant here (rather than
         * in the scheduler) means the worker can also be referenced
         * by [com.aura.proactive.ProactiveRunner] for one-shot
         * enqueue.
         */
        const val UNIQUE_NAME = "dream-consolidation-periodic"
    }
}
