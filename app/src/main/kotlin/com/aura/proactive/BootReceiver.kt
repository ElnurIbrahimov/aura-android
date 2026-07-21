package com.aura.proactive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
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
        // Re-enqueue daemon worker (every 15 min, idempotent)
        val daemonRequest = PeriodicWorkRequestBuilder<DaemonWorker>(15, TimeUnit.MINUTES).build()
        wm.enqueueUniquePeriodicWork(
            DaemonScheduler.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            daemonRequest,
        )
    }
}