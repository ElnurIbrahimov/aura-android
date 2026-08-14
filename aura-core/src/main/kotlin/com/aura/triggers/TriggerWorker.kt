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
    private val opportunityEngine: com.aura.world.OpportunityEngine? = null,
    private val recorder: com.aura.health.WorkerRunRecorder? = null,
) : CoroutineWorker(appContext, params) {

    // if/else, not the elvis form — see DaemonWorker.doWork and BackupWorker's
    // KDoc. runPass() here has no try/catch of its own, so anything thrown by
    // the engine or by posting a notification reached record(), which swallowed
    // it and returned null, and the elvis then re-ran the pass — posting every
    // notification it had already sent a second time.
    override suspend fun doWork(): Result {
        if (recorder == null) return runPass()
        return recorder.record("TriggerWorker") { runPass() to lastOutcome } ?: Result.success()
    }

    private var lastOutcome: com.aura.health.WorkerRunRecorder.Result = com.aura.health.WorkerRunRecorder.Result.ok("")

    private suspend fun runPass(): Result {
        if (!userPreferences.triggersEnabled.first()) {
            lastOutcome = com.aura.health.WorkerRunRecorder.Result.skipped("triggers are switched off")
            return Result.success()
        }
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
        // Run the opportunity engine on every trigger cycle (15 min) so
        // world events from tool execution are processed into opportunities
        // without waiting for the daily dream cycle.
        runCatching { opportunityEngine?.runCycle() }
            .onFailure { Log.w("TriggerWorker", "opportunityEngine: ${it.message}", it) }
        // The success path used to leave lastOutcome at ok(""), so a worker
        // running every 15 minutes filled the run log with blank rows. The
        // count of configured triggers is carried even when none fired, because
        // "checked 4, none matched" and "you have no triggers" look identical
        // otherwise and only one of them is worth acting on.
        lastOutcome = com.aura.health.WorkerRunRecorder.Result.ok(
            when {
                triggers.isEmpty() -> "no triggers configured"
                actions.isEmpty() -> "checked ${triggers.size}, none fired"
                else -> "checked ${triggers.size}, fired ${actions.size}"
            },
        )
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
            extras = mapOf("chatPrefillDraft" to prompt, "openChat" to "true"),
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
