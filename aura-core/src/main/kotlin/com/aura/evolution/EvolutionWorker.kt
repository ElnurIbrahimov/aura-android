package com.aura.evolution

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

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
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
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
