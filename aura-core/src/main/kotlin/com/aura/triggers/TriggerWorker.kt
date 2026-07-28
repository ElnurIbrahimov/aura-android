package com.aura.triggers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aura.data.UserPreferences
import com.aura.tools.NotificationsTool
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Periodic worker that evaluates all user-defined triggers and performs actions. */
@HiltWorker
class TriggerWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val triggerEngine: TriggerEngine,
    private val notificationsTool: NotificationsTool,
    private val userPreferences: UserPreferences,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!userPreferences.triggersEnabled.first()) return Result.success()
        val triggers = userPreferences.triggers.first()
        val now = java.time.ZonedDateTime.now()
        val actions = triggerEngine.checkAll(triggers, now)
        for (action in actions) {
            when (action) {
                is TriggerAction.Notify -> notificationsTool.post(action.title, action.body)
                is TriggerAction.RunHand -> {
                    // TODO: enqueue hand via AgentRunExecutor
                    android.util.Log.d("TriggerWorker", "RunHand ${action.handId}")
                }
                is TriggerAction.StartChat -> {
                    // TODO: start chat with prompt via notification tap
                    // P0-BUILD-DX-F2: do NOT log the user prompt text (privacy);
                    // the handler is unimplemented so the log adds no operational value.
                    // Length-only signal preserves a debugging affordance without
                    // leaking content.
                    android.util.Log.d("TriggerWorker", "StartChat handler=TODO promptLen=${action.prompt.length}")
                }
            }
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "trigger-engine"

        fun schedule(context: Context) {
            val work = PeriodicWorkRequestBuilder<TriggerWorker>(15, TimeUnit.MINUTES)
                .addTag("trigger")
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                work,
            )
        }
    }
}
