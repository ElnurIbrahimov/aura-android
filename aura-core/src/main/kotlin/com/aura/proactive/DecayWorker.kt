package com.aura.proactive

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aura.memory.MemoryStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

/**
 * WorkManager periodic worker that runs a single decay pass over all memories.
 * Scheduled every 6 hours via [ProactiveScheduler.scheduleDecay].
 *
 * The actual work is one call to [MemoryStore.runDecayPass] — no need
 * to extract a builder class. The ProactiveRunner uses the same
 * MemoryStore directly when the user taps "fire decay pass now".
 */
@HiltWorker
class DecayWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val memoryStore: MemoryStore,
    private val userPreferences: com.aura.data.UserPreferences,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runNow()

    /**
     * Run a single decay pass. Returns a [Result] the same way
     * [doWork] does so the ProactiveRunner can surface
     * success / retry / failure to the UI.
     *
     * Respects the decayEnabled preference. When disabled, exits
     * cleanly without running the decay pass — the user chose to
     * preserve all memories at full importance.
     */
    suspend fun runNow(): Result {
        return try {
            if (!userPreferences.decayEnabled.first()) return Result.success()
            memoryStore.runDecayPass()
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("DecayWorker", "decay pass failed: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "memory-decay-periodic"
    }
}
