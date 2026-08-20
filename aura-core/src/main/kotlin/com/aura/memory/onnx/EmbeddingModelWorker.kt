package com.aura.memory.onnx

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aura.memory.ReembedWorker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Fetches the embedding model once, then hands over to [ReembedWorker].
 *
 * Two steps that must happen in this order and only this order. Until the model is on
 * disk, [RoutedEmbedder] answers with the hash sketch and tags every vector `local-hash-v2`;
 * the moment it lands, `Embedder.isCurrent` starts excluding those vectors from cosine
 * scoring, and recall runs on the lexical signal alone until they are rebuilt. So the gap
 * between "model arrived" and "corpus converted" is a window where memory is measurably
 * worse than before, and closing it immediately is the whole reason these are chained
 * rather than scheduled independently.
 *
 * [ReembedWorker]'s KDoc describes this exact hazard from the other direction: a model
 * change nulls no vectors, so every existing one stays in place meaning something else.
 * It was written for a model changed in Settings; this is the same event arriving by
 * download.
 */
@HiltWorker
class EmbeddingModelWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val modelStore: EmbeddingModelStore,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (modelStore.isReady()) {
            // Already here. Still chain the rebuild: a previous run may have downloaded the
            // model and been killed before the corpus was converted, and ReembedWorker is
            // idempotent and cheap when there is nothing to do.
            ReembedWorker.enqueue(applicationContext)
            return Result.success()
        }

        var lastLogged = 0L
        val ok = modelStore.ensureDownloaded { written, total ->
            // Logged sparsely. A 137 MB download at 64 KB a read is over two thousand
            // callbacks, and a log line each would be the most expensive part of it.
            if (written - lastLogged > LOG_EVERY_BYTES) {
                lastLogged = written
                Log.i(TAG, "embedding model: ${written / 1_000_000} / ${total / 1_000_000} MB")
            }
        }

        return if (ok) {
            Log.i(TAG, "model ready; converting the corpus")
            ReembedWorker.enqueue(applicationContext)
            Result.success()
        } else {
            // Retry rather than failure. The usual cause is the network going away
            // mid-download, and the backoff below is measured in tens of minutes because
            // there is no hurry: the hash sketch is still answering.
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "EmbeddingModelWorker"
        private const val UNIQUE_NAME = "embedding_model_download"
        private const val LOG_EVERY_BYTES = 20L * 1024 * 1024

        /**
         * Idempotent and cheap to call on every start.
         *
         * `KEEP` rather than `REPLACE`, so an app restart during a download does not throw
         * away the progress and begin again from zero.
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<EmbeddingModelWorker>()
                .setConstraints(
                    Constraints.Builder()
                        // Unmetered only. 137 MB over cellular is somebody's data plan, and
                        // nothing about this is urgent — the app works without it.
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }

        /**
         * Stop a download that has not finished.
         *
         * Needed because the constraints mean a queued download can sit for days
         * waiting for unmetered wifi. Without this, switching the setting off
         * would delete the model and leave a worker still scheduled to fetch it
         * again the next time the phone touched wifi.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
