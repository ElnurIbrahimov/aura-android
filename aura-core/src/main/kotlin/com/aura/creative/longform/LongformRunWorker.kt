package com.aura.creative.longform

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Runs a long-form drafting job in the background.
 *
 * A thin shell on purpose: every decision lives in [LongformRunner], which has
 * no `Context` and can be driven by a test with a mocked `Brain`. The precedent
 * to avoid is `AgentRunExecutorWorker`, which holds its logic inside `doWork()`
 * and therefore has no unit test of that logic at all.
 *
 * WorkManager gives a worker roughly ten minutes before the system may stop it.
 * A scene takes thirty to ninety seconds, so the runner is given a deadline
 * comfortably inside that window and the worker re-enqueues itself for whatever
 * is left. Re-enqueueing rather than running longer is what makes a
 * forty-minute book possible on a platform that will not let anything run for
 * forty minutes.
 */
@HiltWorker
class LongformRunWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val runner: LongformRunner,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID)
            ?: return Result.failure()

        val deadline = System.currentTimeMillis() + SLICE_BUDGET_MS
        val outcome = runCatching {
            runner.runSlice(jobId = jobId, deadlineMs = deadline, isStopped = { isStopped })
        }.onFailure {
            Log.w(TAG, "longform slice failed for $jobId: ${it.message}", it)
        }.getOrDefault(LongformOutcome.FAILED)

        return when (outcome) {
            LongformOutcome.PAUSED_FOR_TIME -> {
                LongformRunService.enqueue(applicationContext, jobId)
                Result.success()
            }
            // Everything else is terminal and already recorded on the job row.
            // Result.failure() would be misleading: the work is over and its
            // outcome is durable, which is not the same as WorkManager needing
            // to retry it.
            LongformOutcome.COMPLETED,
            LongformOutcome.CANCELLED,
            LongformOutcome.FAILED,
            -> Result.success()
        }
    }

    companion object {
        const val KEY_JOB_ID = "longform_job_id"
        const val WORK_NAME_PREFIX = "longform_"
        private const val TAG = "LongformRunWorker"

        /**
         * How long one execution drafts before handing back.
         *
         * Seven minutes inside WorkManager's ~10, leaving room for the scene in
         * flight to finish and for the re-enqueue to land. Taking the whole
         * window would mean being killed mid-scene with nothing committed.
         */
        const val SLICE_BUDGET_MS = 7 * 60 * 1000L
    }
}

/**
 * Enqueues [LongformRunWorker]. Mirrors `AgentRunExecutorService`.
 *
 * Unique work keyed by job id, so a re-enqueue replaces rather than stacks:
 * two workers drafting the same outline would both pick the same next beat and
 * write it twice.
 */
object LongformRunService {
    fun enqueue(context: Context, jobId: String) {
        val request = OneTimeWorkRequestBuilder<LongformRunWorker>()
            .setInputData(workDataOf(LongformRunWorker.KEY_JOB_ID to jobId))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "${LongformRunWorker.WORK_NAME_PREFIX}$jobId",
                ExistingWorkPolicy.REPLACE,
                request,
            )
    }

    /**
     * Stop a run.
     *
     * The Room write must come first. `cancelUniqueWork` alone races the
     * worker's own re-enqueue, and a worker that re-enqueues after being
     * cancelled carries on drafting; a `cancelling` status in Room is seen by
     * the runner at its next check whichever way that race lands.
     */
    fun cancel(context: Context, jobId: String) {
        WorkManager.getInstance(context)
            .cancelUniqueWork("${LongformRunWorker.WORK_NAME_PREFIX}$jobId")
    }
}
