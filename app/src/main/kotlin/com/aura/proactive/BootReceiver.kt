package com.aura.proactive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Reschedules proactive workers after device reboot.
 *
 * WorkManager persists work across reboots by default, but some OEMs
 * clear WorkManager state on cold boot. This receiver re-enqueues the
 * decay and daemon workers directly (they don't need DI — just
 * WorkManager scheduling). Morning brief and calendar monitor are
 * rescheduled by ProactiveBootstrap on the next app launch.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val wm = WorkManager.getInstance(context)
        // Re-enqueue decay worker (every 6h, idempotent UPDATE policy)
        val decayRequest = PeriodicWorkRequestBuilder<DecayWorker>(6, TimeUnit.HOURS).build()
        wm.enqueueUniquePeriodicWork(
            DecayWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            decayRequest,
        )
        // Re-enqueue the daemon through its scheduler so its constraints
        // (network + battery-not-low) survive reboot — an inline
        // unconstrained request here with UPDATE policy would silently
        // strip them. The default interval is corrected to the user's
        // setting by ProactiveBootstrap on next app launch.
        DaemonScheduler.schedule(context)
        // Re-enqueue morning brief and dream workers. Their exact timing
        // preferences are read inside the workers, so using defaults here
        // is safe until the next ProactiveBootstrap run. This keeps the
        // headline proactive features alive across reboots on OEMs that
        // clear WorkManager state.
        val morningRequest = PeriodicWorkRequestBuilder<MorningBriefWorker>(1, TimeUnit.DAYS).build()
        wm.enqueueUniquePeriodicWork(
            MorningBriefWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            morningRequest,
        )
        val dreamRequest = PeriodicWorkRequestBuilder<com.aura.dream.DreamWorker>(1, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresCharging(true)
                    .build()
            )
            .build()
        wm.enqueueUniquePeriodicWork(
            com.aura.dream.DreamWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            dreamRequest,
        )
        // Re-enqueue evolution worker (default 24h, network + battery-not-low).
        // The worker itself no-ops if evolution is disabled, so this is safe.
        val evolutionRequest = PeriodicWorkRequestBuilder<com.aura.evolution.EvolutionWorker>(24, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()
        wm.enqueueUniquePeriodicWork(
            com.aura.evolution.EvolutionWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            evolutionRequest,
        )
        // Re-enqueue trigger worker (15 min). The worker itself no-ops if
        // triggers are disabled, so this is safe — same pattern as evolution.
        com.aura.triggers.TriggerWorker.schedule(context)
    }
}