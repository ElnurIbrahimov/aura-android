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
    private val handRunEnqueuer: com.aura.tools.HandRunEnqueuer,
    private val handRepository: com.aura.hands.HandRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!userPreferences.triggersEnabled.first()) return Result.success()
        val triggers = userPreferences.triggers.first()
        val now = java.time.ZonedDateTime.now()
        val actions = triggerEngine.checkAll(triggers, now)
        for (action in actions) {
            when (action) {
                is TriggerAction.Notify -> notificationsTool.post(action.title, action.body)
                is TriggerAction.RunHand -> enqueueHand(action.handId)
                is TriggerAction.StartChat -> postChatNotification(action.prompt)
            }
        }
        return Result.success()
    }

    private suspend fun enqueueHand(handId: String) {
        val hand = handRepository.getById(handId)
            ?: handRepository.getByName(handId)
            ?: return
        handRunEnqueuer.enqueue(
            handName = hand.name,
            variablesJson = "{}",
            trigger = "trigger",
            conversationId = "",
            modelId = "",
            context = null,
        )
    }

    private fun postChatNotification(prompt: String) {
        notificationsTool.post(
            title = "Aura",
            body = prompt.take(160).ifEmpty { "Tap to continue in Aura" },
        )
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
