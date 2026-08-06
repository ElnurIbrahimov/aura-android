package com.aura.proactive

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.aura.data.UserPreferences
import java.util.concurrent.TimeUnit

/**
 * Schedules the [DaemonWorker] as a periodic WorkManager job.
 *
 * Every run can make LLM calls, so the request is constrained to
 * network-connected + battery-not-low (mirrors EvolutionScheduler).
 * The interval is user-configurable (default 60 min); WorkManager
 * floors periodic work at 15 minutes.
 *
 * BootReceiver MUST re-enqueue through [schedule] too — an inline
 * unconstrained request with UPDATE policy would silently strip
 * these constraints on every reboot.
 */
object DaemonScheduler {

    const val WORK_NAME = "aura_daemon_thinking"

    fun schedule(
        context: Context,
        intervalMinutes: Int = UserPreferences.DEFAULT_DAEMON_INTERVAL_MINUTES,
    ) {
        val request = PeriodicWorkRequestBuilder<DaemonWorker>(
            intervalMinutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .addTag("daemon-thinking")
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
