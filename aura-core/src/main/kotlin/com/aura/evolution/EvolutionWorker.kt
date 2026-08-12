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

    override suspend fun doWork(): Result =
        recorder?.record("EvolutionWorker") { runPass() to lastOutcome } ?: runPass()

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
            coordinator.runAll()
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "aura_evolution_worker"
        const val TAG = "aura_evolution"
    }
}
