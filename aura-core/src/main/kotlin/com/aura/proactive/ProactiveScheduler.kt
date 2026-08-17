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
     * Enqueue the periodic calendar-check worker (every 15 minutes,
     * 30-minute lookahead inside [CalendarMonitor]). Mirrors
     * [scheduleDecay]: UPDATE policy so re-scheduling on each app
     * start is idempotent, and no network constraint — the check is
     * a local ContentProvider query.
     */
    fun scheduleCalendarChecks() {
        val request = PeriodicWorkRequestBuilder<CalendarCheckWorker>(
            CalendarCheckWorker.INTERVAL_MINUTES, TimeUnit.MINUTES,
        )
            .addTag("calendar-check")
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                CalendarCheckWorker.UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
    }

    fun cancelCalendarChecks() {
        WorkManager.getInstance(context).cancelUniqueWork(CalendarCheckWorker.UNIQUE_NAME)
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

    /**
     * Weekly, while charging.
     *
     * Charging rather than idle-and-unmetered: the file goes to a folder the user
     * picked, which may well be one their cloud syncs, and making the backup wait
     * for Wi-Fi means the week a backup was most needed is the week it did not
     * happen. A snapshot plus a 210k-iteration key derivation is not free, which
     * is what the charging constraint is actually for.
     *
     * Weekly rather than daily because the cost of a stale backup is a week of
     * lost conversation, and the cost of a daily one is a full database read every
     * night for a single user whose state changes slowly.
     */
    fun scheduleBackup() {
        val request = PeriodicWorkRequestBuilder<com.aura.backup.BackupWorker>(7, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresCharging(true)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setInitialDelay(1, TimeUnit.HOURS)
            .addTag("automatic-backup")
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                com.aura.backup.BackupWorker.UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
    }

    fun cancelBackup() {
        WorkManager.getInstance(context).cancelUniqueWork(com.aura.backup.BackupWorker.UNIQUE_NAME)
    }

    /**
     * Coarse place sampling, at WorkManager's fifteen-minute floor.
     *
     * No constraints beyond the default. Requiring charging would mean the log
     * only ever knows where the phone sleeps, which is the one place a person
     * does not need telling about — and the whole cost is one last-known-location
     * read, which is cheaper than the wakeup itself.
     */
    fun schedulePlaceLog() {
        val request = PeriodicWorkRequestBuilder<com.aura.place.PlaceLogWorker>(15, TimeUnit.MINUTES)
            .addTag("place-log")
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                com.aura.place.PlaceLogWorker.UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
    }

    fun cancelPlaceLog() {
        WorkManager.getInstance(context).cancelUniqueWork(com.aura.place.PlaceLogWorker.UNIQUE_NAME)
    }

    /**
     * The project ledger sweep, at WorkManager's fifteen-minute floor.
     *
     * Network-connected and battery-not-low, unlike the place log: this one
     * makes a model call, and a sweep that fires with no connection burns a
     * wakeup to log a failure. The same two constraints `DaemonWorker` carries,
     * for the same reason.
     *
     * Not user-switchable, and deliberately: it reads conversations the user
     * chose to attribute to a project and writes nothing new about them. The
     * spend it can cause is bounded by `BackgroundBudget` like every other
     * unattended caller, which is the control that actually matters.
     */
    fun scheduleProjectLedger() {
        val request = PeriodicWorkRequestBuilder<com.aura.projects.ProjectLedgerWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .addTag("project-ledger")
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                com.aura.projects.ProjectLedgerWorker.UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
    }

    /**
     * Run one backup now, through the same worker the schedule uses.
     *
     * This is how a person finds out their folder and passphrase actually work
     * without waiting a week to not find out. Deliberately the same code path
     * rather than a direct call: a "test" button that exercises different code
     * from the real thing tests the button.
     */
    fun requestBackupNow() {
        WorkManager.getInstance(context).enqueue(
            androidx.work.OneTimeWorkRequestBuilder<com.aura.backup.BackupWorker>()
                .addTag("automatic-backup")
                .build(),
        )
    }
}
