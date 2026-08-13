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
    private val taskDecayPass: com.aura.tasks.TaskDecayPass? = null,
    private val outcomePass: ProactiveOutcomePass? = null,
    private val workerRunRecorder: com.aura.health.WorkerRunRecorder? = null,
    private val placeLog: com.aura.place.PlaceLog? = null,
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
            // Above the decayEnabled gate on purpose. That preference means
            // "do not let my memories fade"; it must not also silently switch
            // off measuring whether proactive suggestions helped.
            runCatching { outcomePass?.run() }
                .onFailure { android.util.Log.w("DecayWorker", "outcome pass failed: ${it.message}", it) }

            // Above the gate for the same reason, and it is the same reason
            // twice: decayEnabled means "do not let my memories fade", not
            // "keep a worker run log forever".
            //
            // WorkerRunRecorder.prune() shipped with a unit test and no
            // production caller. Its KDoc named "the same sweep that prunes
            // proactive events" — that sweep is ProactiveEvents.init, which
            // prunes the event and outcome tables and nothing else, so the run
            // log grew one row per worker run with no bound. This is that call.
            // DecayWorker rather than ProactiveEvents.init because retention
            // belongs on the 6-hourly schedule, not on every process start.
            runCatching { workerRunRecorder?.prune() }
                .onFailure { android.util.Log.w("DecayWorker", "worker-run prune failed: ${it.message}", it) }

            // Same sweep, same reason, and the same defect avoided: a retention
            // window with no caller is a table that grows forever.
            runCatching { placeLog?.prune() }
                .onFailure { android.util.Log.w("DecayWorker", "place prune failed: ${it.message}", it) }

            if (!userPreferences.decayEnabled.first()) return Result.success()
            memoryStore.runDecayPass()
            // Tasks decay on the same pass as memories, deliberately. It is the
            // same question asked of a different table, and two workers asking
            // it on two schedules is how two answers start disagreeing.
            taskDecayPass?.run()
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
