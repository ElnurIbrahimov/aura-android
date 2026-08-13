package com.aura.place

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aura.health.WorkerRunRecorder
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * One coarse location sample, on WorkManager's schedule.
 *
 * A periodic worker rather than a location subscription, deliberately. A live
 * listener would be more accurate and would also run all day, which is both the
 * battery cost this avoids and a far more invasive product: minute-resolution
 * movement is a trace, and "roughly where were you today" is not.
 *
 * WorkManager's fifteen-minute floor is a feature here, not a constraint to work
 * around. It bounds the resolution of the log at the platform level, so the
 * coarseness is enforced by the schedule as well as by
 * [PlaceVisitEntity.coarsen].
 */
@HiltWorker
class PlaceLogWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val placeLog: PlaceLog,
    private val recorder: WorkerRunRecorder? = null,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (recorder != null) {
            recorder.record(WORKER_NAME) {
                val outcome = placeLog.sample()
                outcome to if (outcome.wrote) {
                    WorkerRunRecorder.Result.ok(outcome.reason)
                } else {
                    // A skip with its reason, not a shrug. "Nothing is
                    // happening" and "the switch is off" and "there is no fix"
                    // are three different states that otherwise look identical.
                    WorkerRunRecorder.Result.skipped(outcome.reason)
                }
            }
        } else {
            placeLog.sample()
        }
        // Never retry: the next scheduled sample is the retry, and re-running a
        // location read on backoff would spend battery to learn the same thing.
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "place-log-periodic"
        const val WORKER_NAME = "PlaceLogWorker"
    }
}
