package com.aura.evolution

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * Periodic WorkManager worker that triggers the evolution pipeline.
 * Runs at most once per configured interval; the coordinator itself
 * is a no-op if evolution is disabled.
 */
@HiltWorker
class EvolutionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val coordinator: EvolutionCoordinator,
    private val userPreferences: com.aura.data.UserPreferences,
    private val recorder: com.aura.health.WorkerRunRecorder? = null,
) : CoroutineWorker(context, params) {

    // if/else, not the elvis form — see DaemonWorker.doWork and BackupWorker's
    // KDoc. record() returns null both when the block throws and when it
    // catches BackgroundBudgetExhausted, having already written the row, so
    // `?: runPass()` re-ran the whole evolution pipeline on the failure path.
    override suspend fun doWork(): Result {
        if (recorder == null) return runPass()
        return recorder.record("EvolutionWorker") { runPass() to lastOutcome } ?: Result.success()
    }

    private var lastOutcome: com.aura.health.WorkerRunRecorder.Result = com.aura.health.WorkerRunRecorder.Result.ok("")

    private suspend fun runPass(): Result {
        // Bail early if the user has disabled evolution. The worker may still
        // be enqueued from a prior session; this gate prevents wasted API
        // calls and battery drain.
        if (!userPreferences.evolutionEnabled.first()) {
            lastOutcome = com.aura.health.WorkerRunRecorder.Result.skipped("evolution is switched off")
            return Result.success()
        }
        return try {
            // The success path used to leave lastOutcome at its ok("")
            // initialiser, so every completed run recorded an empty detail and
            // BackgroundHealth showed a worker that had plainly run and had
            // nothing to say about it. "Ran and found nothing" is a result and
            // has to be legible as one.
            val pass = coordinator.runAll()
            lastOutcome = com.aura.health.WorkerRunRecorder.Result.ok(
                when {
                    pass.candidateCount == 0 -> "nothing to propose"
                    pass.promotedCount == 0 -> "${pass.candidateCount} candidate(s), none promoted"
                    else -> "${pass.candidateCount} candidate(s), ${pass.promotedCount} awaiting review"
                },
            )
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Rethrow before the generic catch: `catch (e: Exception)` swallows
            // structured cancellation, which every other worker rethrows.
            throw e
        } catch (e: Exception) {
            // All three of these were missing. `lastOutcome` kept its `ok("")`
            // initialiser, so `record` wrote OUTCOME_OK over a pass that had
            // just thrown, and BackgroundHealth reported the evolution pipeline
            // green through three consecutive failures. Nothing was logged
            // either — the only catch block in the package with no log line —
            // so the failure left no trace anywhere at all.
            android.util.Log.w("EvolutionWorker", "evolution pass failed: ${e.message}", e)
            lastOutcome = com.aura.health.WorkerRunRecorder.Result.failed(e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "aura_evolution_worker"
        const val TAG = "aura_evolution"
    }
}
