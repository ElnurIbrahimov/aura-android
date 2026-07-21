package com.aura.proactive

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules the [DaemonWorker] as a periodic WorkManager job.
 * WorkManager enforces a 15-minute minimum floor for periodic work,
 * so the requested 8-minute interval is effectively ~15 minutes.
 */
object DaemonScheduler {

    const val WORK_NAME = "aura_daemon_thinking"
    internal const val INTERVAL_MINUTES = 15L

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<DaemonWorker>(
            INTERVAL_MINUTES, TimeUnit.MINUTES,
        ).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}