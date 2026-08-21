package com.aura.proactive

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * WorkManager periodic worker that runs a single [CalendarMonitor]
 * check every 15 minutes with a 30-minute lookahead, replacing the
 * old permanent foreground service. Scheduled via
 * [ProactiveScheduler.scheduleCalendarChecks].
 *
 * Respects the calendarMonitorEnabled preference inside doWork so an
 * unconditional re-enqueue (BootReceiver) is safe — the worker
 * no-ops when the user turned the feature off.
 */
@HiltWorker
class CalendarCheckWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val calendarMonitor: CalendarMonitor,
    private val userPreferences: com.aura.data.UserPreferences,
    private val recorder: com.aura.health.WorkerRunRecorder? = null,
) : CoroutineWorker(appContext, params) {

    // if/else, not the elvis form — see DaemonWorker.doWork and BackupWorker's
    // KDoc. record() returns null on the failure path having already written
    // the row, so `?: runPass()` ran the check twice.
    override suspend fun doWork(): Result {
        if (recorder == null) return runPass()
        return recorder.record("CalendarCheckWorker") { runPass() to lastOutcome } ?: Result.success()
    }

    private var lastOutcome: com.aura.health.WorkerRunRecorder.Result = com.aura.health.WorkerRunRecorder.Result.ok("")

    private suspend fun runPass(): Result {
        return try {
            if (!userPreferences.calendarMonitorEnabled.first()) {
                lastOutcome = com.aura.health.WorkerRunRecorder.Result.skipped("calendar monitoring is switched off")
                return Result.success()
            }
            calendarMonitor.checkOnce()
            lastOutcome = com.aura.health.WorkerRunRecorder.Result.ok("checked")
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("CalendarCheckWorker", "calendar check failed: ${e.message}", e)
            // This logged but never set `lastOutcome`, so `record` wrote the
            // `ok("")` initialiser and BackgroundHealth showed a green calendar
            // check over a worker that had thrown on every run.
            lastOutcome = com.aura.health.WorkerRunRecorder.Result.failed(e)
            // Capped, like EvolutionWorker. An uncapped retry on a periodic
            // worker is an unbounded backoff loop against a fault that is
            // usually not transient, and WorkManager keeps every attempt
            // alive across reboots. Three tries, then let the next scheduled
            // run be the retry.
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val UNIQUE_NAME = "calendar-check-periodic"
        const val INTERVAL_MINUTES = 15L
    }
}
