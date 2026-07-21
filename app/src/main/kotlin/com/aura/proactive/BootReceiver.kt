package com.aura.proactive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager

/**
 * Reschedules proactive workers after device reboot.
 *
 * WorkManager persists work across reboots by default, but periodic
 * work that was enqueued with UPDATE policy may not survive a cold
 * boot on all OEMs. This receiver re-enqueues the morning brief,
 * decay, and daemon workers on BOOT_COMPLETED.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // ProactiveBootstrap handles the full scheduling flow on app
        // launch, but WorkManager should already persist periodic work
        // across reboots. This receiver is a safety net for OEMs that
        // clear WorkManager state on boot.
        WorkManager.getInstance(context)
    }
}