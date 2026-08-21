package com.aura.projects

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aura.health.WorkerRunRecorder
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * Runs the project ledger sweep on WorkManager's schedule.
 *
 * A thin adapter over [ProjectLedgerSweep], the same split `PlaceLogWorker` has
 * over `PlaceLog`: everything that decides anything lives in the sweep, where it
 * is testable without a scheduler.
 *
 * **A timed sweep rather than a per-turn hook, and the reason is the budget.**
 * Extracting after every turn would put a model call on the chat hot path and
 * sit exactly on the boundary `ChatOptions.attended` draws: the user is present,
 * so the call defaults to attended and `BackgroundBudget` never caps it —
 * `UnattendedCallersAreMarkedTest` exists because that flag fails open silently.
 * On a timer there is no ambiguity. It is also better extraction: one call per
 * conversation rather than per turn, over the whole exchange, with zero latency
 * added to chat. The cost is up to one interval of lag, which for "where is
 * ARC-AGI-2" is not a cost.
 */
@HiltWorker
class ProjectLedgerWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val sweep: ProjectLedgerSweep,
    private val recorder: WorkerRunRecorder? = null,
    private val userPreferences: com.aura.data.UserPreferences? = null,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Self-gate, so an un-cancelled schedule is harmless.
        //
        // `ProactiveBootstrap` cancels this worker when the preference goes
        // off, but a schedule that survives — an upgrade, a reconcile that
        // failed, a boot that raced the DataStore read — would otherwise keep
        // making a model call every fifteen minutes with the switch showing
        // off. `BootReceiver` states this contract for every worker it
        // re-schedules unconditionally, and it is the reason that file is
        // allowed to be unconditional.
        //
        // Absent preferences (test construction) read as enabled, matching the
        // default.
        val enabled = userPreferences?.let {
            runCatching { it.projectLedgerEnabled.first() }
                .onFailure { e -> android.util.Log.w(WORKER_NAME, "preference read failed", e) }
                .getOrDefault(true)
        } ?: true
        if (!enabled) {
            recorder?.record(WORKER_NAME) {
                Unit to WorkerRunRecorder.Result.skipped("the project ledger is switched off")
            }
            return Result.success()
        }
        if (recorder != null) {
            recorder.record(WORKER_NAME) {
                val outcome = sweep.sweep()
                outcome to if (outcome.conversationsRead > 0) {
                    WorkerRunRecorder.Result.ok(outcome.reason)
                } else {
                    // A reason, not a shrug: "no background model", "nothing
                    // tagged" and "nothing new said" are three different states
                    // and BackgroundHealth is the only place they are visible.
                    WorkerRunRecorder.Result.skipped(outcome.reason)
                }
            }
        } else {
            sweep.sweep()
        }
        // Never retry. The next scheduled sweep is the retry, and the watermark
        // guarantees it picks up exactly what this run did not.
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "project-ledger-periodic"
        const val WORKER_NAME = "ProjectLedgerWorker"
    }
}
