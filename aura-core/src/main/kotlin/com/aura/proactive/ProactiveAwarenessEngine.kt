package com.aura.proactive

import android.util.Log
import com.aura.agent.ConversationStore
import com.aura.emotion.EmotionEngine
import com.aura.kg.KnowledgeGraphRepository
import com.aura.memory.MemoryDao
import com.aura.memory.MemoryEntity
import com.aura.tasks.TaskDao
import com.aura.tasks.TaskEntity
import com.aura.tools.CalendarReadTool
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Proactive awareness engine — runs periodic checks that surface
 * actionable insights to the user without being asked.
 *
 * Ported from Python Aura's `proactive/awareness.py` (8 checks); the ninth is
 * this codebase's own.
 *
 * 1. Staleness check — memories not accessed in 30 days
 * 2. Goal-blocker detection — tasks stuck in pending for >7 days
 * 3. Relationship gap detection — no conversation in 3+ days
 * 4. Deadline approaching — calendar events in next 24h
 * 5. Contradiction alert — KG has conflicting relationships
 * 6. Stress correlation — emotion engine shows high tension
 * 7. Pattern detection — conversation frequency changed significantly
 * 8. Priority shift — too many high-priority tasks pending
 * 9. Open question — Aura has something to ask and has not been able to
 *

 * All checks are heuristic, local, no LLM cost.
 */
@Singleton
class ProactiveAwarenessEngine @Inject constructor(
    private val memoryDao: MemoryDao,
    private val taskDao: TaskDao,
    private val conversationStore: ConversationStore,
    private val calendarReadTool: CalendarReadTool? = null,
    private val kgRepository: KnowledgeGraphRepository? = null,
    private val emotionEngine: EmotionEngine? = null,
    private val openQuestionDao: com.aura.curiosity.OpenQuestionDao? = null,
) {
    data class ProactiveFinding(
        /**
         * The finding's kind, drawn from [ProactiveFindingType.wire]. It stays a
         * `String` because it travels through `MotivationAccumulator.source` and
         * into a `TEXT` column, but every value here must have an entry in
         * [ProactiveFindingType] — `ProactiveFindingTypeCoverageTest` fails the
         * build otherwise. That registration is what lets [SalienceFilter] match
         * a finding against the events already recorded.
         */
        val type: kotlin.String,
        val title: kotlin.String,
        val message: kotlin.String,
        val urgency: Float, // 0-1

        /**
         * What this finding is *about*, so the outcome can be checked later.
         *
         * A finding carries rendered prose — "Task \"Fix the roof\" has been
         * pending since 19 days ago" — and a sentence cannot be re-queried.
         * These three fields are what let a checker ask, days afterwards,
         * whether the thing actually changed.
         *
         * Defaulted, so a check with no observable subject constructs exactly
         * as before and is recorded as unobservable rather than as a failure.
         */
        val subjectKind: kotlin.String = ProactiveOutcomeEntity.SUBJECT_NONE,
        val subjectIds: List<kotlin.String> = emptyList(),
        val baselineJson: kotlin.String = "{}",
    ) {
        /**
         * What tapping this does, derived from [type] rather than carried.
         *
         * It used to be a string set at each of the eight construction sites,
         * read only as a relevance weight, and never navigated to — so two of
         * the eight were wrong without anyone noticing: `"graph"` matched no
         * route, and `"calendar"` had no screen at all. Deriving it means there
         * is exactly one mapping, and it lives beside the persisted wire value,
         * which is the only key a tap on a history card actually has.
         */
        val action: ProactiveAction
            get() = ProactiveFindingType.from(type)?.action ?: ProactiveAction.None

        /** Kept for the relevance weight in [SalienceFilter]. */
        val actionRoute: kotlin.String?
            get() = when (val a = action) {
                is ProactiveAction.Navigate -> a.route
                is ProactiveAction.OpenChat -> "chat"
                ProactiveAction.OpenCalendarApp -> "calendar"
                ProactiveAction.None -> null
            }
    }

    private companion object {
        /**
         * Ids kept per finding. Enough to check the predicate, few enough that
         * a row stays small — the checker only needs to know whether *any* of
         * them moved, not to enumerate the whole set.
         */
        const val MAX_SUBJECT_IDS = 10

        /**
         * How long a question waits, unseen, before it is worth a nudge.
         *
         * Three days: long enough that the user genuinely has not opened chat,
         * short enough that the answer still relates to why the question came
         * up.
         */
        const val UNASKED_GRACE_MS = 3L * 24 * 60 * 60 * 1000
    }

    suspend fun runAll(): List<ProactiveFinding> {
        val findings = mutableListOf<ProactiveFinding>()
        runCatching { findings.addAll(checkStaleMemories()) }
            .onFailure { Log.w("ProactiveAwareness", "staleness check failed: ${it.message}", it) }
        runCatching { findings.addAll(checkStuckTasks()) }
            .onFailure { Log.w("ProactiveAwareness", "goal-blocker check failed: ${it.message}", it) }
        runCatching { findings.addAll(checkRelationshipGap()) }
            .onFailure { Log.w("ProactiveAwareness", "relationship gap check failed: ${it.message}", it) }
        runCatching { findings.addAll(checkDeadlineApproaching()) }
            .onFailure { Log.w("ProactiveAwareness", "deadline check failed: ${it.message}", it) }
        runCatching { findings.addAll(checkContradictions()) }
            .onFailure { Log.w("ProactiveAwareness", "contradiction check failed: ${it.message}", it) }
        runCatching { findings.addAll(checkStressCorrelation()) }
            .onFailure { Log.w("ProactiveAwareness", "stress check failed: ${it.message}", it) }
        runCatching { findings.addAll(checkConversationPattern()) }
            .onFailure { Log.w("ProactiveAwareness", "pattern check failed: ${it.message}", it) }
        runCatching { findings.addAll(checkPriorityShift()) }
            .onFailure { Log.w("ProactiveAwareness", "priority check failed: ${it.message}", it) }
        runCatching { findings.addAll(checkOpenQuestion()) }
            .onFailure { Log.w("ProactiveAwareness", "open question check failed: ${it.message}", it) }
        return findings.sortedByDescending { it.urgency }
    }

    /**
     * Aura has a question and the user has not been in chat to see it.
     *
     * Gated on `timesAsked == 0`, not on age alone. If the card has been shown
     * and the question is still open, the user has seen it and chosen not to
     * answer — and a notification about something they have already declined in
     * place is nagging. This fires only for the case a notification actually
     * adds something: the question exists and has never been in front of them.
     *
     * The only check whose suggestion serves Aura rather than the user, which
     * is why it is also the one with the lowest urgency in the list.
     */
    private suspend fun checkOpenQuestion(): List<ProactiveFinding> {
        val dao = openQuestionDao ?: return emptyList()
        val question = dao.current() ?: return emptyList()
        if (question.timesAsked > 0) return emptyList()
        if (System.currentTimeMillis() - question.createdAt < UNASKED_GRACE_MS) return emptyList()
        return listOf(
            ProactiveFinding(
                type = ProactiveFindingType.OPEN_QUESTION.wire,
                title = "Aura has a question",
                message = question.question,
                urgency = 0.3f,
                subjectKind = ProactiveOutcomeEntity.SUBJECT_QUESTION,
                subjectIds = listOf(question.id),
            ),
        )
    }

    private suspend fun checkStaleMemories(): List<ProactiveFinding> {
        val now = System.currentTimeMillis()
        val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000
        val cutoff = now - thirtyDaysMs
        val recent = memoryDao.recent(500)
        val stale = recent.filter { it.accessCount == 0 && it.createdAt < cutoff && it.decayScore > 0f }
        if (stale.isEmpty()) return emptyList()
        return listOf(ProactiveFinding(
            type = "stale_memories",
            title = "${stale.size} memories haven't been used in 30+ days",
            message = "These memories are fading. Review them to keep what matters and let go of what doesn't.",
            urgency = 0.2f,
            subjectKind = ProactiveOutcomeEntity.SUBJECT_MEMORY_SET,
            // `accessCount == 0` is a precondition of being in this list, so
            // any of them reading above zero later is unambiguous evidence the
            // user went and looked. `decayScore` would not be: the decay pass
            // moves it on its own.
            subjectIds = stale.take(MAX_SUBJECT_IDS).map { it.id },
            baselineJson = """{"count":${stale.size}}""",
        ))
    }

    private suspend fun checkStuckTasks(): List<ProactiveFinding> {
        val now = System.currentTimeMillis()
        val sevenDaysMs = 7L * 24 * 60 * 60 * 1000
        val cutoff = now - sevenDaysMs
        val tasks = taskDao.all()
        val stuck = tasks.filter {
            it.status == "pending" &&
                it.createdAt < cutoff &&
                // A task that has already gone quiet has been judged: the
                // evidence says it stopped mattering. Nagging about it here
                // would have the system contradict itself in the same breath —
                // dropping it from the list and then pushing a notification
                // about how long it has been on the list. Quiet means quiet.
                !com.aura.tasks.TaskSalience.isQuiet(it.salience) &&
                // Rows the trigger engine parks in the tasks table are content
                // hashes for watched URLs, not tasks anyone wrote down.
                !it.description.startsWith(com.aura.tasks.TaskDecayPass.TRIGGER_HASH_PREFIX)
        }
        if (stuck.isEmpty()) return emptyList()
        val oldest = stuck.minByOrNull { it.createdAt }
        val daysStuck = oldest?.let { ((now - it.createdAt) / (24 * 60 * 60 * 1000)).toInt() } ?: 0
        return listOf(ProactiveFinding(
            type = "stuck_tasks",
            title = "${stuck.size} task(s) stuck for ${daysStuck}+ days",
            message = "Task \"${oldest?.title?.take(60)}\" has been pending since $daysStuck days ago. " +
                if (daysStuck > 14) "Consider cancelling or breaking it into smaller steps."
                else "Want to break it down or reschedule?",
            urgency = if (daysStuck > 14) 0.7f else 0.4f,
            subjectKind = ProactiveOutcomeEntity.SUBJECT_TASK,
            subjectIds = listOfNotNull(oldest?.id),
            // deferCount and lastTouchedAt, not salience: TaskDecayPass moves
            // salience ~9% over a 72h horizon by itself, so a task near the
            // quiet threshold would cross it unaided and be scored as a success
            // this suggestion had nothing to do with.
            baselineJson = """{"deferCount":${oldest?.deferCount ?: 0},""" +
                """"lastTouchedAt":${oldest?.lastTouchedAt ?: 0},"stuckCount":${stuck.size}}""",
        ))
    }

    private suspend fun checkRelationshipGap(): List<ProactiveFinding> {
        val now = System.currentTimeMillis()
        val threeDaysMs = 3L * 24 * 60 * 60 * 1000
        val recent = conversationStore.recent(1)
        if (recent.isEmpty()) return emptyList()
        val lastConv = recent.first()
        val lastActivity = lastConv.updatedAt
        if (lastActivity > now - threeDaysMs) return emptyList()
        val daysSince = ((now - lastActivity) / (24 * 60 * 60 * 1000)).toInt()
        return listOf(ProactiveFinding(
            type = "relationship_gap",
            title = "It's been $daysSince days since we last talked",
            message = when {
                daysSince > 7 -> "Long time no see! I've been thinking about what we discussed. Want to pick up where we left off?"
                daysSince > 5 -> "It's been a few days. Anything on your mind?"
                else -> "Haven't heard from you in a while. I'm here when you need me."
            },
            urgency = if (daysSince > 7) 0.5f else 0.3f,
            subjectKind = ProactiveOutcomeEntity.SUBJECT_CONVERSATION,
            baselineJson = """{"lastActivityAt":$lastActivity}""",
        ))
    }

    /** Check 4: Deadline approaching — calendar events in next 24h */
    private suspend fun checkDeadlineApproaching(): List<ProactiveFinding> {
        val events = calendarReadTool?.readTodaysEvents().orEmpty()
        if (events.isEmpty()) return emptyList()
        return listOf(ProactiveFinding(
            type = "deadline_approaching",
            title = "${events.size} event(s) today",
            message = events.joinToString(", "),
            urgency = 0.6f,
        ))
    }

    /** Check 5: Contradiction alert — KG has conflicting relationships */
    private suspend fun checkContradictions(): List<ProactiveFinding> {
        val repo = kgRepository ?: return emptyList()
        val nodes = repo.recent(100)
        val contradictions = mutableListOf<kotlin.String>()
        // The ids were being thrown away and only the labels kept, which left
        // the finding unable to say which nodes it meant. Not a new check —
        // the same check no longer discarding its own subject.
        val conflictingIds = mutableListOf<kotlin.String>()
        for (node in nodes) {
            val neighbors = repo.getNeighbors(node.id)
            val allEdges = neighbors.incoming + neighbors.outgoing
            val byTarget = allEdges.groupBy { it.targetId }
            for ((_, rels) in byTarget) {
                if (rels.size > 1 && rels.map { it.type }.distinct().size > 1) {
                    contradictions.add(node.label)
                    conflictingIds.add(node.id)
                }
            }
        }
        if (contradictions.isEmpty()) return emptyList()
        return listOf(ProactiveFinding(
            type = "contradiction_alert",
            title = "${contradictions.size} conflicting relationship(s) in knowledge graph",
            message = contradictions.joinToString("; "),
            urgency = 0.5f,
            subjectKind = ProactiveOutcomeEntity.SUBJECT_KG_NODE_SET,
            subjectIds = conflictingIds.distinct().take(MAX_SUBJECT_IDS),
            baselineJson = """{"count":${contradictions.size}}""",
        ))
    }

    /** Check 6: Stress correlation — emotion engine shows high tension */
    private suspend fun checkStressCorrelation(): List<ProactiveFinding> {
        val snapshot = emotionEngine?.snapshot() ?: return emptyList()
        if (snapshot.tension < 0.7f) return emptyList()
        return listOf(ProactiveFinding(
            type = "stress_correlation",
            title = "You seem tense lately",
            message = "Your tension has been high. Want to take a break or talk through what's on your mind?",
            urgency = 0.5f,
        ))
    }

    /** Check 7: Pattern detection — conversation frequency changed significantly */
    private suspend fun checkConversationPattern(): List<ProactiveFinding> {
        val now = System.currentTimeMillis()
        val weekAgo = now - 7L * 24 * 60 * 60 * 1000
        val twoWeeksAgo = now - 14L * 24 * 60 * 60 * 1000
        val recent = conversationStore.recent(100)
        val thisWeek = recent.count { it.updatedAt > weekAgo }
        val lastWeek = recent.count { it.updatedAt in twoWeeksAgo..weekAgo }
        if (lastWeek == 0 && thisWeek == 0) return emptyList()
        if (lastWeek == 0 && thisWeek > 0) return listOf(ProactiveFinding(
            type = "pattern_alert",
            title = "You're talking more than usual",
            message = "$thisWeek conversations this week vs $lastWeek last week. What's on your mind?",
            urgency = 0.2f,
        ))
        if (lastWeek > 0) {
            val ratio = thisWeek.toFloat() / lastWeek
            if (ratio < 0.3f) return listOf(ProactiveFinding(
                type = "pattern_alert",
                title = "You've been quiet this week",
                message = "$thisWeek conversations this week vs $lastWeek last week. Everything okay?",
                urgency = 0.3f,
            ))
        }
        return emptyList()
    }

    /** Check 8: Priority shift — too many high-priority tasks pending */
    private suspend fun checkPriorityShift(): List<ProactiveFinding> {
        val tasks = taskDao.all()
        val highPriority = tasks.filter { it.priority >= 2 && it.status == "pending" }
        if (highPriority.size <= 5) return emptyList()
        return listOf(ProactiveFinding(
            type = "priority_shift",
            title = "${highPriority.size} high-priority tasks pending",
            message = "You have a lot of high-priority tasks. Want to review priorities?",
            urgency = 0.5f,
            subjectKind = ProactiveOutcomeEntity.SUBJECT_TASK_SET,
            baselineJson = """{"highPriorityCount":${highPriority.size}}""",
        ))
    }
}