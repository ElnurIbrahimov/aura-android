package com.aura.creative.livingworld

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Advances every running world.
 *
 * A thin shell: all the deciding is in [LivingWorldRunner], which has no
 * `Context`.
 *
 * Worth being clear about what this worker is *not* responsible for. It does
 * not decide which tick is due — [WorldClock] derives that from the wall clock,
 * so a worker that runs late, early, or not at all produces exactly the same
 * world. WorkManager's fifteen-minute periodic floor therefore constrains how
 * promptly the user sees a tick, never which ticks happen.
 */
@HiltWorker
class LivingWorldTickWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val runner: LivingWorldRunner,
    private val reporter: LivingWorldReporter,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val deadline = System.currentTimeMillis() + SLICE_BUDGET_MS
        val outcome = runCatching {
            runner.runAllDue(deadlineMs = deadline, isStopped = { isStopped })
        }.onFailure {
            Log.w(TAG, "living world slice failed: ${it.message}", it)
        }.getOrDefault(TickOutcome.FAILED)

        // Narration is a separate pass, after the ticking, and only when there
        // is something above the notability floor. A world that merely ran
        // costs nothing; this is the only place a model can be reached.
        if (outcome == TickOutcome.CAUGHT_UP || outcome == TickOutcome.PAUSED_FOR_TIME) {
            runCatching { reporter.reportAll() }
                .onFailure { Log.w(TAG, "world report failed: ${it.message}", it) }
        }

        if (outcome == TickOutcome.PAUSED_FOR_TIME) {
            LivingWorldScheduler.catchUpNow(applicationContext)
        }
        // Terminal outcomes are already durable in Room. Result.failure() would
        // ask WorkManager to retry work that is not owed.
        return Result.success()
    }

    companion object {
        private const val TAG = "LivingWorldTickWorker"

        /** Seven minutes inside WorkManager's ~10, leaving room to commit. */
        const val SLICE_BUDGET_MS = 7 * 60 * 1000L
    }
}

/**
 * Schedules world ticking.
 *
 * Two deliberate differences from [com.aura.proactive.DaemonScheduler]:
 *
 * - **No network constraint.** Ticking is pure arithmetic and works offline;
 *   only narration needs the network, and that is a separate pass which no-ops
 *   when there is none. A world that stops living because the user is on a
 *   plane would be a worse world.
 * - **A one-shot catch-up path.** The periodic floor is fifteen minutes, but a
 *   user who wants the next hour now can ask for it, and the same runner serves
 *   both. This is what makes the floor invisible rather than merely tolerable.
 */
object LivingWorldScheduler {

    fun schedule(context: Context, intervalMinutes: Long = DEFAULT_INTERVAL_MINUTES) {
        // WorkManager's periodic floor. DaemonScheduler guards this explicitly
        // for the same reason: a smaller number is silently rounded up, so the
        // setting would lie rather than fail.
        val interval = intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES)
        val request = PeriodicWorkRequestBuilder<LivingWorldTickWorker>(interval, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(CATCH_UP_WORK_NAME)
    }

    /** Run a slice as soon as the system allows. Drives "Catch up now" and re-enqueue. */
    fun catchUpNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<LivingWorldTickWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            CATCH_UP_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    const val WORK_NAME = "living_world_tick"
    const val CATCH_UP_WORK_NAME = "living_world_catch_up"
    const val DEFAULT_INTERVAL_MINUTES = 60L
    const val MIN_INTERVAL_MINUTES = 15L
}
