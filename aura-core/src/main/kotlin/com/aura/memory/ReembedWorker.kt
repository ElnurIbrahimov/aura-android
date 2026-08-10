package com.aura.memory

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Re-embeds memories whose vectors were produced by a different embedding model.
 *
 * Needed because changing the embedding model in Settings used to silently
 * poison recall: `rebuildEmbeddings` selected on `embedding IS NULL`, a model
 * change nulls nothing, and every existing vector stayed in place while meaning
 * something else entirely. The 384-to-384 case — which every credible small
 * model is — produced plausible cosines with no error anywhere.
 *
 * One-time rather than periodic. There is nothing to do on a schedule: work
 * appears when the model changes, and once the corpus is converted it stays
 * converted. [enqueue] is idempotent and cheap to call on app start.
 */
@HiltWorker
class ReembedWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val memoryStore: MemoryStore,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val pending = memoryStore.countNeedingReembed()
        if (pending == 0) {
            Result.success()
        } else {
            android.util.Log.i(TAG, "re-embedding $pending memories after an embedding-model change")
            val rebuilt = memoryStore.rebuildEmbeddings { done, total ->
                if (done % PROGRESS_LOG_EVERY == 0) android.util.Log.i(TAG, "re-embedded $done/$total")
            }
            val remaining = memoryStore.countNeedingReembed()
            android.util.Log.i(TAG, "re-embedded $rebuilt; $remaining still pending")
            // Retry rather than success when rows remain. `rebuildEmbeddings`
            // stops a page that makes no progress, which is usually the
            // embedder being unreachable — exactly the transient case
            // WorkManager's backoff exists for. Reporting success would leave
            // the corpus half-converted with nothing scheduled to finish it.
            if (remaining > 0) Result.retry() else Result.success()
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w(TAG, "re-embed pass failed: ${e.message}", e)
        Result.retry()
    }

    companion object {
        private const val TAG = "ReembedWorker"
        private const val PROGRESS_LOG_EVERY = 200
        const val UNIQUE_NAME = "aura_reembed"

        /**
         * Queue a re-embed pass if one is not already queued.
         *
         * [ExistingWorkPolicy.KEEP], not REPLACE: a pass already running is
         * doing the same work, and replacing it would discard its progress and
         * restart from the top on every app launch.
         *
         * **No network constraint.** The embedder may be entirely on-device —
         * that is much of the point of having one — and requiring connectivity
         * would block a local re-embed for no reason. A cloud embedder that
         * cannot reach the network fails its page, makes no progress, and the
         * worker returns retry, which is the same outcome by a more honest
         * route.
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ReembedWorker>()
                .setConstraints(
                    Constraints.Builder()
                        // Re-embedding a large corpus is sustained CPU or a
                        // long run of network calls. Neither belongs on a
                        // nearly-flat battery.
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
