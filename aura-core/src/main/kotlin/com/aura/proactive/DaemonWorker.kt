package com.aura.proactive

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aura.agent.ConversationStore
import com.aura.data.UserPreferences
import com.aura.emotion.EmotionEngine
import com.aura.kg.KnowledgeGraphRepository
import com.aura.memory.MemoryStore
import com.aura.providers.ProviderMessage
import com.aura.providers.ProviderRegistry
import com.aura.tasks.TaskDao
import com.aura.tools.CalendarReadTool
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList

/**
 * Background "thinking" worker — runs periodically (~15 min).
 *
 * Orchestrates the full proactive pipeline:
 *   1. Awareness checks (8 heuristic checks, no LLM cost)
 *   2. Salience filter (4-factor scoring, only high-salience pass)
 *   3. Motivation scoring (5-factor + adaptive threshold)
 *   4. Adaptive timing (defer to high-engagement window)
 *   5. Curiosity scan (KG gap detection)
 *   6. Idle-time preparation (predict + pre-research)
 *   7. Proactive outreach (varied messages with rationale)
 *   8. LLM insight (review recent conversation + context)
 *
 * Respects daemonEnabled preference. Uses background model for LLM calls.
 */
@HiltWorker
class DaemonWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val userPreferences: UserPreferences,
    private val providerRegistry: ProviderRegistry,
    private val conversationStore: ConversationStore,
    private val eventBus: ProactiveEventBus,
    private val calendarReadTool: CalendarReadTool,
    private val memoryStore: MemoryStore,
    private val taskDao: TaskDao,
    // New proactive components
    private val awarenessEngine: ProactiveAwarenessEngine? = null,
    private val agentPresence: com.aura.consciousness.AgentPresence? = null,
    private val proactiveMessageStore: ProactiveMessageStore? = null,
    private val motivationAccumulator: MotivationAccumulator? = null,
    private val curiosityScanner: CuriosityScanner? = null,
    private val salienceFilter: SalienceFilter? = null,
    private val adaptiveTimingEngine: AdaptiveTimingEngine? = null,
    private val idleTimePreparationEngine: IdleTimePreparationEngine? = null,
    private val proactiveMessageLibrary: ProactiveMessageLibrary? = null,
    // Council — overnight agent society debates
    private val councilOrchestrator: com.aura.agent.council.CouncilOrchestrator? = null,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val daemonEnabled = userPreferences.daemonEnabled.first()
        if (!daemonEnabled) return Result.success()

        return try {
            // 1. Awareness checks (8 heuristic checks, no LLM cost)
            val findings = runCatching { awarenessEngine?.runAll().orEmpty() }
                .onFailure { Log.w(TAG, "awareness: ${it.message}", it) }
                .getOrDefault(emptyList())

            // 2. Salience filter — only high-salience findings pass
            val salient = if (salienceFilter != null && findings.isNotEmpty()) {
                runCatching { salienceFilter!!.filter(findings) }
                    .onFailure { Log.w(TAG, "salience: ${it.message}", it) }
                    .getOrDefault(findings.map { SalienceFilter.FilteredFinding(it, 1f, true) })
                    .filter { it.passed }
                    .map { it.finding }
            } else findings

            // 3+4. Motivation scoring + adaptive timing — post only if both pass
            val isGoodTime = adaptiveTimingEngine?.isGoodTime() ?: true
            for (finding in salient) {
                if (motivationAccumulator != null) {
                    val message = MotivationAccumulator.PotentialMessage(
                        content = finding.message,
                        source = finding.type,
                        relevanceToUser = if (finding.actionRoute != null) 0.8f else 0.4f,
                        timeSinceSimilar = 0.7f,
                        emotionalUrgency = finding.urgency,
                        curiosityDrive = 0.3f,
                        userReceptivity = if (isGoodTime) 0.7f else 0.3f,
                    )
                    val score = runCatching { motivationAccumulator!!.evaluate(message) }
                        .onFailure { Log.w(TAG, "motivation: ${it.message}", it) }
                        .getOrNull()
                    if (score?.shouldDeliver == true) {
                        postFinding(finding)
                    }
                } else {
                    // No motivation scoring — just post if timing is good
                    if (isGoodTime) postFinding(finding)
                }
            }

            // 5. Curiosity scan — KG gaps with natural questions
            runCatching {
                val targets = curiosityScanner?.scan().orEmpty()
                if (targets.isNotEmpty()) {
                    val top = targets.first()
                    if (motivationAccumulator != null) {
                        val msg = MotivationAccumulator.PotentialMessage(
                            content = top.question,
                            source = "curiosity",
                            relevanceToUser = 0.6f,
                            timeSinceSimilar = 0.8f,
                            emotionalUrgency = top.urgency,
                            curiosityDrive = 1.0f,
                            userReceptivity = if (isGoodTime) 0.6f else 0.3f,
                        )
                        val score = motivationAccumulator.evaluate(msg)
                        if (score.shouldDeliver) {
                            eventBus.emit(ProactiveEventBus.Event.DaemonInsight(
                                title = "Curiosity: ${top.entityName}",
                                body = top.question,
                            ))
                        }
                    } else {
                        eventBus.emit(ProactiveEventBus.Event.DaemonInsight(
                            title = "Curiosity: ${top.entityName}",
                            body = top.question,
                        ))
                    }
                }
            }.onFailure { Log.w(TAG, "curiosity: ${it.message}", it) }

            // 6. Idle-time preparation — predict next question, pre-research
            runCatching { idleTimePreparationEngine?.prepare() }
                .onFailure { Log.w(TAG, "idle prep: ${it.message}", it) }

            // 7. Proactive outreach — varied messages with rationale
            if (agentPresence != null && proactiveMessageStore != null) {
                runCatching {
                    val lastConv = conversationStore.recent(1).firstOrNull()
                    val daysSince = if (lastConv != null) {
                        ((System.currentTimeMillis() - lastConv.updatedAt) / (1000L * 60 * 60 * 24)).toInt()
                    } else 0
                    if (daysSince >= 3) {
                        val outreach = if (proactiveMessageLibrary != null) {
                            val tod = proactiveMessageLibrary.timeOfDay()
                            proactiveMessageLibrary.pick(tod, "You haven't been around in $daysSince days.")
                        } else {
                            agentPresence?.generateOutreachMessage(daysSince)
                        }
                        if (outreach != null) {
                            proactiveMessageStore?.setMessage(outreach)
                        }
                    }
                }.onFailure { Log.w(TAG, "outreach: ${it.message}", it) }
            }

            // 8. LLM insight — review conversation + context
            generateLlmInsight()

            // 9. Council — agents debate findings and propose interventions
            runCatching {
                councilOrchestrator?.let { orchestrator ->
                    if (salient.isNotEmpty()) {
                        val councilContext = buildString {
                            val calEvents = runCatching {
                                calendarReadTool.readTodaysEvents()
                            }.getOrDefault(emptyList())
                            if (calEvents.isNotEmpty()) {
                                append("Calendar: ${calEvents.joinToString("; ")}\n")
                            }
                            val today = java.util.Calendar.getInstance().apply {
                                set(java.util.Calendar.HOUR_OF_DAY, 0)
                                set(java.util.Calendar.MINUTE, 0)
                                set(java.util.Calendar.SECOND, 0)
                                set(java.util.Calendar.MILLISECOND, 0)
                            }.timeInMillis
                            val tomorrow = today + 24L * 60 * 60 * 1000
                            val due = runCatching { taskDao.dueInRange(today, tomorrow) }.getOrDefault(emptyList())
                            if (due.isNotEmpty()) {
                                append("Tasks due: ${due.joinToString("; ") { it.title }}\n")
                            }
                        }
                        val results = orchestrator.runFromFindings(salient, councilContext)
                        for (result in results) {
                            if (result.quorumReached && result.proposal != null) {
                                val body = when (result.proposal) {
                                    is com.aura.agent.council.Intervention.Schedule -> result.proposal.description
                                    is com.aura.agent.council.Intervention.Message -> result.proposal.draftBody.take(200)
                                    is com.aura.agent.council.Intervention.Reminder -> result.proposal.rationale
                                    is com.aura.agent.council.Intervention.SelfCare -> result.proposal.rationale
                                    is com.aura.agent.council.Intervention.Memory -> result.proposal.connection
                                }
                                eventBus.emit(ProactiveEventBus.Event.DaemonInsight(
                                    title = "Council: ${result.proposal.summary}",
                                    body = body,
                                ))
                            }
                        }
                    }
                }
            }.onFailure { Log.w(TAG, "council: ${it.message}", it) }

            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "daemon failed: ${e.message}")
            Result.success()
        }
    }

    private suspend fun postFinding(finding: ProactiveAwarenessEngine.ProactiveFinding) {
        eventBus.emit(ProactiveEventBus.Event.DaemonInsight(
            title = finding.title,
            body = finding.message,
        ))
    }

    private suspend fun generateLlmInsight() {
        val backgroundModel = runCatching { userPreferences.backgroundModel.first() }
            .getOrNull() ?: return
        if (backgroundModel.isNullOrBlank()) return

        val calendarContext = runCatching {
            val events = calendarReadTool.readTodaysEvents()
            if (events.isNotEmpty()) "Today's calendar: ${events.joinToString("; ")}"
            else ""
        }.getOrDefault("")

        val memoryContext = runCatching {
            val decayed = memoryStore.decayedBelow(0.4f, 5)
            if (decayed.isNotEmpty()) "Fading memories: ${decayed.joinToString("; ") { it.content.take(80) }}"
            else ""
        }.getOrDefault("")

        val taskContext = runCatching {
            val today = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
            val tomorrow = today + 24L * 60 * 60 * 1000
            val due = taskDao.dueInRange(today, tomorrow)
            if (due.isNotEmpty()) "Tasks due today: ${due.joinToString("; ") { it.title }}"
            else ""
        }.getOrDefault("")

        val contextBlock = listOfNotNull(
            calendarContext.ifBlank { null },
            memoryContext.ifBlank { null },
            taskContext.ifBlank { null },
        ).joinToString("\n")

        val conversations = conversationStore.recent(1)
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

        if (turnsText.isBlank() && contextBlock.isBlank()) return

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
        if (userMessage.isBlank()) return

        runCatching {
            val messages = listOf(
                ProviderMessage(role = ProviderMessage.Role.system, content = systemPrompt),
                ProviderMessage(role = ProviderMessage.Role.user, content = userMessage),
            )
            val chunks = providerRegistry.chat(backgroundModel, messages).toList()
            val insight = chunks.joinToString("") { it.text ?: "" }.trim()
            if (insight.isNotBlank() && insight != "SKIP") {
                eventBus.emit(ProactiveEventBus.Event.DaemonInsight(
                    title = "Thought of something",
                    body = insight,
                ))
                Log.d(TAG, "posted insight: ${insight.take(80)}")
            }
        }.onFailure { Log.w(TAG, "LLM insight: ${it.message}", it) }
    }

    companion object {
        private const val TAG = "DaemonWorker"
    }
}