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
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            if (!userPreferences.calendarMonitorEnabled.first()) return Result.success()
            calendarMonitor.checkOnce()
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("CalendarCheckWorker", "calendar check failed: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "calendar-check-periodic"
        const val INTERVAL_MINUTES = 15L
    }
}
