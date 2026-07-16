package com.aura.evolution

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules the periodic [EvolutionWorker]. The default interval is 24 hours,
 * with a require-network constraint because reflection may need the cloud.
 */
@Singleton
class EvolutionScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun schedule(intervalHours: kotlin.Long = 24L) {
        val request = PeriodicWorkRequestBuilder<EvolutionWorker>(intervalHours, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .addTag(EvolutionWorker.TAG)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            EvolutionWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(EvolutionWorker.WORK_NAME)
    }
}
