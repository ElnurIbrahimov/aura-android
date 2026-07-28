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
    private val calendarReadTool: com.aura.tools.CalendarReadTool? = null,
    private val memoryStore: com.aura.memory.MemoryStore? = null,
    private val taskDao: com.aura.tasks.TaskDao? = null,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val daemonEnabled = userPreferences.daemonEnabled.first()
        if (!daemonEnabled) return Result.success()

        val backgroundModel = userPreferences.backgroundModel.first()
        if (backgroundModel.isNullOrBlank()) return Result.success()

        return try {
            val conversations = conversationStore.recent(limit = 1)
            // Gather context from tools the daemon can read:
            // calendar (today's events), memory (decayed items),
            // tasks (due today). These are read-only queries that
            // help the daemon prepare relevant insights instead of
            // just reflecting on the last conversation.
            val calendarContext = runCatching {
                val events = calendarReadTool?.readTodaysEvents() ?: emptyList()
                if (events.isNotEmpty()) {
                    "Today's calendar: ${events.joinToString("; ")}"
                } else ""
            }.getOrDefault("")
            val memoryContext = runCatching {
                val decayed = memoryStore?.decayedBelow(0.4f, 5) ?: emptyList()
                if (decayed.isNotEmpty()) {
                    "Fading memories: ${decayed.joinToString("; ") { it.content.take(80) }}"
                } else ""
            }.getOrDefault("")
            val taskContext = runCatching {
                val today = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                }.timeInMillis
                val tomorrow = today + 24L * 60 * 60 * 1000
                val due = taskDao?.dueInRange(today, tomorrow) ?: emptyList()
                if (due.isNotEmpty()) {
                    "Tasks due today: ${due.joinToString("; ") { it.title }}"
                } else ""
            }.getOrDefault("")

            val contextBlock = listOfNotNull(
                calendarContext.ifBlank { null },
                memoryContext.ifBlank { null },
                taskContext.ifBlank { null },
            ).joinToString("\n")

            if (conversations.isEmpty() && contextBlock.isBlank()) return Result.success()

            val conv = conversations.firstOrNull()
            val turnsText = if (conv != null) {
                val recentTurns = conv.turns.takeLast(6)
                if (recentTurns.size >= 2) {
                    recentTurns.joinToString("\n") { turn ->
                        val role = if (turn.user != null) "user" else "assistant"
                        val content = turn.user ?: turn.assistant ?: ""
                        "$role: ${content.take(500)}"
                    }
                } else ""
            } else ""

            val systemPrompt = if (contextBlock.isNotBlank()) {
                """
                You are Aura's background thinking daemon. Review the recent
                conversation and the user's current context (calendar, fading
                memories, due tasks). Generate a brief, helpful insight
                (max 2 sentences). Connect the conversation to the context
                when relevant. If there's nothing notable, respond with
                exactly "SKIP". Do not repeat what was already said.
                
                Current context:
                $contextBlock
                """.trimIndent()
            } else {
                """
                You are Aura's background thinking daemon. Review the recent
                conversation and generate a brief insight or observation
                (max 2 sentences). If there's nothing notable, respond with
                exactly "SKIP". Do not repeat what was already said.
                """.trimIndent()
            }

            val userMessage = buildString {
                if (turnsText.isNotBlank()) {
                    append("Recent conversation:\n\n$turnsText")
                } else if (contextBlock.isNotBlank()) {
                    append("The user hasn't had any recent conversations, but here is their current context.")
                }
            }
            if (userMessage.isBlank()) return Result.success()

            val messages = listOf(
                ProviderMessage(role = ProviderMessage.Role.system, content = systemPrompt),
                ProviderMessage(role = ProviderMessage.Role.user, content = userMessage),
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