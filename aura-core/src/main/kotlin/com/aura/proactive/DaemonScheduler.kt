package com.aura.proactive

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules the [DaemonWorker] as a periodic WorkManager job.
 * Runs every ~8 minutes when enabled.
 */
object DaemonScheduler {

    private const val WORK_NAME = "aura_daemon_thinking"
    private const val INTERVAL_MINUTES = 8L

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