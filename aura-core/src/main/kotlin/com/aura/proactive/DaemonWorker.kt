package com.aura.proactive

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aura.agent.ConversationStore
import com.aura.data.UserPreferences
import com.aura.providers.ChatOptions
import com.aura.providers.ProviderMessage
import com.aura.providers.ProviderRegistry
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList

/**
 * Background "thinking" worker — runs periodically (every ~8 min),
 * reviews recent conversation, and if the model generates something
 * substantive, posts it as a proactive event via [ProactiveEventBus].
 *
 * Respects the daemonEnabled preference. Uses the background model
 * to avoid interfering with active chats.
 */
@HiltWorker
class DaemonWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val userPreferences: UserPreferences,
    private val providerRegistry: ProviderRegistry,
    private val conversationStore: ConversationStore,
    private val eventBus: ProactiveEventBus,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val daemonEnabled = userPreferences.daemonEnabled.first()
        if (!daemonEnabled) return Result.success()

        val backgroundModel = userPreferences.backgroundModel.first()
        if (backgroundModel.isNullOrBlank()) return Result.success()

        return try {
            val conversations = conversationStore.recent(limit = 1)
            if (conversations.isEmpty()) return Result.success()

            val conv = conversations.first()
            val recentTurns = conv.turns.takeLast(6)
            if (recentTurns.size < 2) return Result.success()

            val turnsText = recentTurns.joinToString("\n") { turn ->
                val role = if (turn.user != null) "user" else "assistant"
                val content = turn.user ?: turn.assistant ?: ""
                "$role: ${content.take(500)}"
            }

            val systemPrompt = """
                You are Aura's background thinking daemon. Review the recent
                conversation and generate a brief insight or observation
                (max 2 sentences). If there's nothing notable, respond with
                exactly "SKIP". Do not repeat what was already said.
            """.trimIndent()

            val messages = listOf(
                ProviderMessage(role = ProviderMessage.Role.system, content = systemPrompt),
                ProviderMessage(role = ProviderMessage.Role.user, content = "Recent conversation:\n\n$turnsText"),
            )

            val chunks = providerRegistry.chat(backgroundModel, messages).toList()
            val insight = chunks.joinToString("") { it.text ?: "" }.trim()

            if (insight.isBlank() || insight == "SKIP") {
                Log.d(TAG, "Daemon: nothing to surface")
                return Result.success()
            }

            eventBus.emit(
                ProactiveEventBus.Event.DaemonInsight(
                    title = "Thought of something",
                    body = insight,
                ),
            )
            Log.d(TAG, "Daemon: posted insight: ${insight.take(80)}")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Daemon worker failed: ${e.message}")
            Result.success()
        }
    }

    companion object {
        private const val TAG = "DaemonWorker"
    }
}