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
    /**
     * The durable delivery path.
     *
     * [ProactiveEventBus] is `replay = 0`, so an emit from this worker — which
     * runs in a process that usually has no live collector — is dropped and
     * never persisted. Every insight this class produced was disappearing that
     * way. `ProactiveEvents.record` inserts to Room first and re-emits
     * afterwards, which is why it is the only path used below.
     */
    private val proactiveEvents: ProactiveEvents,
    private val outcomeDao: ProactiveOutcomeDao? = null,
    private val proactiveNotifier: ProactiveNotifier? = null,
    private val calendarReadTool: CalendarReadTool,
    private val memoryStore: MemoryStore,
    private val taskDao: TaskDao,
    // New proactive components
    private val awarenessEngine: ProactiveAwarenessEngine? = null,
    private val agentPresence: com.aura.consciousness.AgentPresence? = null,
    private val proactiveMessageStore: ProactiveMessageStore? = null,
    private val motivationAccumulator: MotivationAccumulator? = null,
    private val salienceFilter: SalienceFilter? = null,
    private val adaptiveTimingEngine: AdaptiveTimingEngine? = null,
    private val idleTimePreparationEngine: IdleTimePreparationEngine? = null,
    private val selfServeResearcher: com.aura.curiosity.SelfServeResearcher? = null,
    private val situationReader: com.aura.situation.SituationReader? = null,
    private val recorder: com.aura.health.WorkerRunRecorder? = null,
    private val proactiveMessageLibrary: ProactiveMessageLibrary? = null,
    // Council — overnight agent society debates
    private val councilOrchestrator: com.aura.agent.council.CouncilOrchestrator? = null,
) : CoroutineWorker(appContext, params) {

    // if/else, not `recorder?.record(...) ?: runPass()`.
    //
    // record() returns null on two paths, and on both it has already written
    // the row and swallowed the exception: when the block throws, and when it
    // catches BackgroundBudgetExhausted. The elvis form therefore ran the
    // entire pass a second time on exactly the failure path — for this worker
    // that is the awareness sweep, the council, the research call and every
    // finding it posts, all repeated.
    //
    // It also defeated the spend cap it was meant to cooperate with: budget
    // exhausted, record() swallows and returns null, the elvis re-runs, the
    // budget throws again — and that second throw is outside record(), so it
    // escaped as an uncaught failure instead of the skip the cap was designed
    // to produce. BackupWorker documents the same trap.
    //
    // success() on the null path rather than retry(): the failed run is already
    // recorded and visible in Diagnostics, the next scheduled run is an hour
    // away, and a budget ceiling that only resets at midnight must not have
    // retry attempts burned against it.
    override suspend fun doWork(): Result {
        if (recorder == null) return runPass()
        return recorder.record("DaemonWorker") { runPass() to lastOutcome } ?: Result.success()
    }

    private var lastOutcome: com.aura.health.WorkerRunRecorder.Result =
        com.aura.health.WorkerRunRecorder.Result.ok("")

    private suspend fun runPass(): Result {
        val daemonEnabled = userPreferences.daemonEnabled.first()
        if (!daemonEnabled) {
            lastOutcome = com.aura.health.WorkerRunRecorder.Result.skipped("the daemon is switched off")
            return Result.success()
        }

        var posted = 0
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
            // The continuous value, not the verdict. The old code computed a
            // receptivity score and then threw it away for a two-valued
            // 0.7/0.3 branch, which discarded exactly the resolution the
            // engine exists to provide.
            val receptivity = adaptiveTimingEngine?.receptivityNow() ?: AdaptiveTimingEngine.NEUTRAL
            // The learned hourly signal is a statistic about this hour across
            // weeks; the situation is a fact about this minute. Keep the first,
            // let the second veto — an hour that usually works is still the
            // wrong moment if the user is in a meeting right now.
            val situation = runCatching { situationReader?.get() }
                .onFailure { Log.w(TAG, "situation read failed; not vetoing", it) }
                .getOrNull()
            val badMoment = situation != null && !situation.interruptible
            if (badMoment) Log.i(TAG, "holding findings: ${situation?.blockedBecause}")
            val isGoodTime = !badMoment && receptivity >= AdaptiveTimingEngine.GOOD_TIME_THRESHOLD
            for (finding in salient) {
                if (badMoment) break
                if (motivationAccumulator != null) {
                    val message = MotivationAccumulator.PotentialMessage(
                        content = finding.message,
                        source = finding.type,
                        relevanceToUser = if (finding.actionRoute != null) 0.8f else 0.4f,
                        timeSinceSimilar = 0.7f,
                        emotionalUrgency = finding.urgency,
                        curiosityDrive = 0.3f,
                        userReceptivity = receptivity,
                    )
                    val score = runCatching { motivationAccumulator!!.evaluate(message) }
                        .onFailure { Log.w(TAG, "motivation: ${it.message}", it) }
                        .getOrNull()
                    if (score?.shouldDeliver == true) {
                        postFinding(finding)
                        posted++
                    }
                } else {
                    // No motivation scoring — just post if timing is good
                    if (isGoodTime) {
                        postFinding(finding)
                        posted++
                    }
                }
            }

            // 5. (was: curiosity scan) — replaced by com.aura.curiosity.
            //    CuriosityStore, which runs on the nightly dream cycle. The
            //    scanner that used to run here rebuilt its targets from
            //    scratch every daemon pass with no record of what it had
            //    already asked, and its four question templates each read
            //    "'$node.label'" — which in Kotlin interpolates the whole
            //    NodeEntity and then appends a literal ".label", so every
            //    question it ever posted was a dumped data class.

            // 5b. Self-serve research — answer one of Aura's own questions
            //     without spending the single open-question slot on something
            //     the user would only have to look up themselves. Capped at one
            //     a day inside the researcher.
            runCatching { selfServeResearcher?.research() }
                .onFailure { Log.w(TAG, "self-serve research: ${it.message}", it) }

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
            // getOrDefault(false), not true: `UserPreferences.councilEnabled`
            // defaults to false precisely because a council session is the most
            // expensive thing the daemon can do, and a transient DataStore read
            // failure must not be the thing that opts a user in. This only
            // differs on a read failure, which is rare — but the failure it
            // guards against is silently spending money.
            val councilEnabled = runCatching { userPreferences.councilEnabled.first() }
                .onFailure { Log.w("DaemonWorker", "councilEnabled read failed: ${it.message}", it) }.getOrDefault(false)
            val councilActivityLevel = runCatching { userPreferences.councilActivityLevel.first() }
                .onFailure { Log.w("DaemonWorker", "councilActivityLevel read failed: ${it.message}", it) }.getOrDefault(3)
            if (councilEnabled) {
                runCatching {
                councilOrchestrator?.let { orchestrator ->
                    if (salient.isNotEmpty()) {
                        val councilContext = buildString {
                            val calEvents = runCatching {
                                calendarReadTool.readTodaysEvents()
                            }.onFailure { Log.w("DaemonWorker", "runCatching failed: ${it.message}", it) }.getOrDefault(emptyList())
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
                            val due = runCatching { taskDao.dueInRange(today, tomorrow) }.onFailure { Log.w("DaemonWorker", "runCatching failed: ${it.message}", it) }.getOrDefault(emptyList())
                            if (due.isNotEmpty()) {
                                append("Tasks due: ${due.joinToString("; ") { it.title }}\n")
                            }
                        }
                        val results = orchestrator.runFromFindings(salient, councilContext, maxFindings = councilActivityLevel)
                        for (result in results) {
                            if (result.quorumReached && result.proposal != null) {
                                val body = when (result.proposal) {
                                    is com.aura.agent.council.Intervention.Schedule -> result.proposal.description
                                    is com.aura.agent.council.Intervention.Message -> result.proposal.draftBody.take(200)
                                    is com.aura.agent.council.Intervention.Reminder -> result.proposal.rationale
                                    is com.aura.agent.council.Intervention.SelfCare -> result.proposal.rationale
                                    is com.aura.agent.council.Intervention.Memory -> result.proposal.connection
                                }
                                proactiveEvents.record(ProactiveEventBus.Event.DaemonInsight(
                                    title = "Council: ${result.proposal.summary}",
                                    body = body,
                                ))
                            }
                        }
                    }
                }
            }.onFailure { Log.w(TAG, "council: ${it.message}", it) }
            }

            lastOutcome = when {
                findings.isEmpty() ->
                    com.aura.health.WorkerRunRecorder.Result.skipped("nothing to notice")
                badMoment ->
                    com.aura.health.WorkerRunRecorder.Result.skipped(
                        "held ${salient.size} suggestion(s): ${situation?.blockedBecause}",
                    )
                salient.isEmpty() ->
                    com.aura.health.WorkerRunRecorder.Result.skipped(
                        "${findings.size} finding(s), none salient enough",
                    )
                else -> com.aura.health.WorkerRunRecorder.Result.ok(
                    "${findings.size} finding(s), ${salient.size} salient, $posted posted",
                )
            }
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "daemon failed: ${e.message}")
            lastOutcome = com.aura.health.WorkerRunRecorder.Result(
                com.aura.health.WorkerRunEntity.OUTCOME_FAILED,
                e.message ?: e::class.java.simpleName,
            )
            Result.success()
        }
    }

    /**
     * Surface a finding. `findingType` is the whole point: without it the row
     * lands as an anonymous "DaemonInsight" and [SalienceFilter] has nothing to
     * recognise it by on the next cycle, so the same stale-memories nudge
     * scores as brand new every fifteen minutes forever.
     *
     * Note this is reached only when [MotivationAccumulator] returns
     * `shouldDeliver` (or when there is no accumulator at all), so a finding
     * that scores below the motivation bar leaves no row and stays novel.
     */
    private suspend fun postFinding(finding: ProactiveAwarenessEngine.ProactiveFinding) {
        val now = System.currentTimeMillis()
        val eventId = proactiveEvents.record(ProactiveEventBus.Event.DaemonInsight(
            title = finding.title,
            body = finding.message,
            findingType = finding.type,
        ))
        if (eventId <= 0L) return

        // Record what this suggestion was about, so the outcome pass can ask
        // later whether it actually helped. Three of the eight finding types
        // have no observable outcome and are written as such rather than being
        // given a checker that measures something adjacent and calls it
        // success — see ProactiveOutcomePass.horizonFor.
        val type = ProactiveFindingType.from(finding.type) ?: return
        val horizon = ProactiveOutcomePass.horizonFor(type)

        // The in-app record above always happens; this is the conditional part.
        // A category only interrupts once the evidence says suggestions of its
        // kind actually lead somewhere, and only in hours where they have.
        val notified = runCatching { proactiveNotifier?.maybeNotify(finding, now) ?: false }
            .onFailure { Log.w(TAG, "notify check failed: ${it.message}", it) }
            .getOrDefault(false)
        runCatching {
            outcomeDao?.insert(
                ProactiveOutcomeEntity(
                    eventId = eventId,
                    findingType = finding.type,
                    subjectKind = finding.subjectKind,
                    subjectIds = finding.subjectIds.joinToString(
                        prefix = "[", postfix = "]", separator = ",",
                    ) { "\"" + it + "\"" },
                    baselineJson = finding.baselineJson,
                    surface = if (notified) {
                        ProactiveOutcomeEntity.SURFACE_NOTIFICATION
                    } else {
                        ProactiveOutcomeEntity.SURFACE_CARD
                    },
                    postedAt = now,
                    dueAt = if (horizon > 0L) now + horizon else 0L,
                    outcome = if (horizon > 0L) {
                        ProactiveOutcomeEntity.OUTCOME_PENDING
                    } else {
                        ProactiveOutcomeEntity.OUTCOME_UNOBSERVABLE
                    },
                    outcomeReason = if (horizon > 0L) "" else UNOBSERVABLE_REASON,
                ),
            )
        }.onFailure { Log.w(TAG, "recording outcome row failed: ${it.message}", it) }
    }

    private suspend fun generateLlmInsight() {
        val backgroundModel = runCatching { userPreferences.backgroundModel.first() }
            .onFailure { Log.w("DaemonWorker", "runCatching failed: ${it.message}", it) }.getOrNull() ?: return
        if (backgroundModel.isNullOrBlank()) return

        val calendarContext = runCatching {
            val events = calendarReadTool.readTodaysEvents()
            if (events.isNotEmpty()) "Today's calendar: ${events.joinToString("; ")}"
            else ""
        }.onFailure { Log.w("DaemonWorker", "runCatching failed: ${it.message}", it) }.getOrDefault("")

        val memoryContext = runCatching {
            val decayed = memoryStore.decayedBelow(0.4f, 5)
            if (decayed.isNotEmpty()) "Fading memories: ${decayed.joinToString("; ") { it.content.take(80) }}"
            else ""
        }.onFailure { Log.w("DaemonWorker", "runCatching failed: ${it.message}", it) }.getOrDefault("")

        val taskContext = runCatching {
            val today = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
            val tomorrow = today + 24L * 60 * 60 * 1000
            val due = taskDao.dueInRange(today, tomorrow)
            if (due.isNotEmpty()) "Tasks due today: ${due.joinToString("; ") { it.title }}"
            else ""
        }.onFailure { Log.w("DaemonWorker", "runCatching failed: ${it.message}", it) }.getOrDefault("")

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
            val chunks = providerRegistry.chat(backgroundModel, messages, com.aura.providers.ChatOptions(attended = false)).toList()
            val insight = chunks.joinToString("") { it.text ?: "" }.trim()
            if (insight.isNotBlank() && insight != "SKIP") {
                proactiveEvents.record(ProactiveEventBus.Event.DaemonInsight(
                    title = "Thought of something",
                    body = insight,
                ))
                Log.d(TAG, "posted insight: ${insight.take(80)}")
            }
        }.onFailure { Log.w(TAG, "LLM insight: ${it.message}", it) }
    }

    companion object {
        private const val TAG = "DaemonWorker"

        /** Said in the UI, so it explains rather than labels. */
        const val UNOBSERVABLE_REASON =
            "Aura can raise this but cannot see what you did about it, so it is never counted for or against."

    }
}