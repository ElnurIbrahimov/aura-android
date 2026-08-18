package com.aura.proactive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Reschedules proactive workers after device reboot.
 *
 * WorkManager persists work across reboots by default, but some OEMs clear
 * WorkManager state on cold boot — which is the entire reason this receiver
 * exists, and the reason the set it covers has to be the complete one.
 *
 * It was not. It re-enqueued seven of the eleven periodic workers and silently
 * omitted `BackupWorker`, `PlaceLogWorker` and `ProjectLedgerWorker`. On exactly
 * the phones this class is written for, the weekly automatic backup stopped at
 * the first reboot and did not resume until the app was next opened —
 * `allowBackup="false"` is correct, so that backup is the only copy of the
 * memory store that exists off the device, and losing it silently is the one
 * unrecoverable failure mode in the app. Nothing noticed, because a worker that
 * is not scheduled produces no run record to be missing.
 *
 * `LivingWorldTickWorker` is deliberately still absent: `WorldClock` derives the
 * due tick from wall time, so a world loses nothing by not being ticked, and the
 * catch-up path folds the gap in closed form on the next launch.
 *
 * **Every schedule here delegates to the same scheduler the app uses.** The old
 * version rebuilt six of the requests inline, and they had already drifted: the
 * dream request lost `setInitialDelay(2h)`, so a reboot could fire a full
 * consolidation pass while the phone was still settling, and several lost their
 * tags. Duplicating a `PeriodicWorkRequest` with `UPDATE` policy silently
 * replaces the real one with the weaker copy — the hazard the daemon comment
 * below already called out, applied to one worker and missed on the rest.
 *
 * Exact preferences (the brief's hour, the daemon's interval) are corrected by
 * `ProactiveBootstrap` on the next app launch. Every worker below either
 * self-gates on its own preference — `BackupWorker` on `autoBackupEnabled`,
 * `PlaceLog.sample` returning `Disabled` before it reads any location — or is
 * deliberately not user-switchable, so scheduling them unconditionally starts
 * nothing the user has switched off.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext

        // Both schedulers take nothing but a Context. Constructing them here
        // rather than injecting keeps this receiver Hilt-free while still using
        // the one definition of each request, constraints and all.
        val scheduler = com.aura.proactive.ProactiveScheduler(app)
        scheduler.scheduleDecay()
        scheduler.scheduleCalendarChecks()
        scheduler.scheduleMorningBrief()
        scheduler.scheduleDream()
        scheduler.scheduleBackup()
        scheduler.schedulePlaceLog()
        scheduler.scheduleProjectLedger()

        // Re-enqueue the daemon through its scheduler so its constraints
        // (network + battery-not-low) survive reboot — an inline unconstrained
        // request here with UPDATE policy would silently strip them.
        DaemonScheduler.schedule(app)
        com.aura.evolution.EvolutionScheduler(app).schedule()
        com.aura.triggers.TriggerWorker.schedule(app)
    }
}
