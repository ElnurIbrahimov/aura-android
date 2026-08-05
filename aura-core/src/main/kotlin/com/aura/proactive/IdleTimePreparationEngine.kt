package com.aura.proactive

import android.util.Log
import com.aura.agent.ConversationStore
import com.aura.data.UserPreferences
import com.aura.memory.MemoryStore
import com.aura.providers.ChatOptions
import com.aura.providers.ProviderMessage
import com.aura.providers.ProviderRegistry
import com.aura.tasks.TaskDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Idle-Time Preparation Engine (ProAct pattern).
 *
 * During daemon cycles, predicts what the user might need next and
 * pre-researches it. When the user opens the app, the answer is
 * ready as a suggestion chip.
 *
 * Inspired by ProAct (SJTU + Tencent, NeurIPS 2026): uses idle-time
 * compute to anticipate user needs. Reduces conversation turns by
 * 14.8% and follow-up requests by 11.7%.
 */
@Singleton
class IdleTimePreparationEngine @Inject constructor(
    private val providerRegistry: ProviderRegistry,
    private val userPreferences: UserPreferences,
    private val conversationStore: ConversationStore,
    private val memoryStore: MemoryStore,
    private val taskDao: TaskDao,
) {
    data class PreparedAnswer(
        val predictedQuestion: kotlin.String,
        val answer: kotlin.String,
        val confidence: Float,
        val createdAt: Long,
    )

    private val _prepared = MutableStateFlow<PreparedAnswer?>(null)
    val prepared: StateFlow<PreparedAnswer?> = _prepared

    /**
     * Predict what the user might ask next based on recent conversation,
     * memory, and context. Pre-generate an answer so it's ready when
     * they open the app.
     */
    suspend fun prepare(): PreparedAnswer? {
        val backgroundModel = runCatching { userPreferences.backgroundModel.first() }
            .onFailure { Log.w("IdleTimePreparationEngin", "runCatching failed: ${it.message}", it) }.getOrNull() ?: return null
        if (backgroundModel.isNullOrBlank()) return null

        val lastConv = conversationStore.recent(1).firstOrNull() ?: return null
        val recentTurns = lastConv.turns.takeLast(4)
        if (recentTurns.size < 2) return null

        val turnsText = recentTurns.joinToString("\n") { turn ->
            val role = if (turn.user != null) "user" else "assistant"
            "$role: ${(turn.user ?: turn.assistant ?: "").take(300)}"
        }

        val memories = runCatching {
            memoryStore.recent(3).map { it.content.take(100) }
        }.onFailure { Log.w("IdleTimePreparationEngin", "runCatching failed: ${it.message}", it) }.getOrDefault(emptyList())
        val tasks = runCatching {
            taskDao.all().filter { it.status == "pending" }.take(5).map { it.title }
        }.onFailure { Log.w("IdleTimePreparationEngin", "runCatching failed: ${it.message}", it) }.getOrDefault(emptyList())

        val systemPrompt = """
            You are Aura's predictive assistant. Based on the recent conversation
            and context, predict ONE question the user is likely to ask next.
            Then provide a brief, helpful answer (2-3 sentences).

            Format:
            QUESTION: <predicted question>
            ANSWER: <brief answer>

            Recent conversation:
            $turnsText

            Recent memories: ${memories.joinToString("; ")}
            Pending tasks: ${tasks.joinToString("; ")}
        """.trimIndent()

        return runCatching {
            val messages = listOf(
                ProviderMessage(role = ProviderMessage.Role.system, content = systemPrompt),
                ProviderMessage(role = ProviderMessage.Role.user, content = "What will I likely need next?"),
            )
            val chunks = providerRegistry.chat(backgroundModel, messages).toList()
            val response = chunks.joinToString("") { it.text ?: "" }.trim()

            val question = response.substringAfter("QUESTION:")
                .substringBefore("ANSWER:").trim()
            val answer = response.substringAfter("ANSWER:").trim()

            if (question.isBlank() || answer.isBlank()) return null

            val prepared = PreparedAnswer(
                predictedQuestion = question,
                answer = answer,
                confidence = 0.5f,
                createdAt = System.currentTimeMillis(),
            )
            _prepared.value = prepared
            prepared
        }.onFailure { Log.w("IdlePrep", "prepare failed: ${it.message}", it) }.getOrNull()
    }

    fun consume(): PreparedAnswer? {
        val value = _prepared.value
        _prepared.value = null
        return value
    }
}