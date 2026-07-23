package com.aura.proactive

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules and owns the lifecycle of proactive WorkManager jobs.
 * - MorningBriefWorker: fires daily at 7am (or the next time the device
 *   wakes if before 7am).
 * - DecayWorker: fires every 6 hours to run a memory decay pass.
 */
@Singleton
class ProactiveScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun scheduleMorningBrief(hourOfDay: Int = 7) {
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hourOfDay)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_YEAR, 1)
        }
        val initialDelay = next.timeInMillis - now.timeInMillis
        val request = PeriodicWorkRequestBuilder<MorningBriefWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag("morning-brief")
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                MorningBriefWorker.UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
    }

    fun cancelMorningBrief() {
        WorkManager.getInstance(context).cancelUniqueWork(MorningBriefWorker.UNIQUE_NAME)
    }

    /**
     * Enqueue a periodic decay worker that runs [MemoryStore.runDecayPass]
     * every 6 hours. Uses UPDATE policy so re-scheduling on each app start
     * is idempotent. No network constraint required — the decay pass is local.
     */
    fun scheduleDecay() {
        val request = PeriodicWorkRequestBuilder<DecayWorker>(6, TimeUnit.HOURS)
            .addTag("memory-decay")
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                DecayWorker.UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
    }

    fun cancelDecay() {
        WorkManager.getInstance(context).cancelUniqueWork(DecayWorker.UNIQUE_NAME)
    }

    /**
     * Enqueue a periodic dream-consolidation worker that runs the
     * [com.aura.dream.DreamConsolidator] every 24h. The two
     * constraints — battery-not-low + charging — match the Python
     * "sleep" semantic: do the work overnight when the phone is
     * plugged in. 24h interval because that's the minimum useful
     * cadence for memory consolidation (any faster and the
     * paraphrase count hasn't grown enough to find clusters).
     *
     * If the LLM call fails (rate limit, network), the worker
     * returns Result.retry() and WorkManager backs off per its
     * default policy (30s, 5min, 30min, 2hr, 5hr, 12hr, 24hr).
     */
    fun scheduleDream() {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresCharging(true)
            .build()
        val request = PeriodicWorkRequestBuilder<com.aura.dream.DreamWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .setInitialDelay(2, TimeUnit.HOURS) // give app time to settle
            .addTag("dream-consolidation")
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                com.aura.dream.DreamWorker.UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
    }

    fun cancelDream() {
        WorkManager.getInstance(context).cancelUniqueWork(com.aura.dream.DreamWorker.UNIQUE_NAME)
    }
}
