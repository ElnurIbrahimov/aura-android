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
 * Ported from Python Aura's `proactive/awareness.py` (8 checks).
 *
 * 1. Staleness check — memories not accessed in 30 days
 * 2. Goal-blocker detection — tasks stuck in pending for >7 days
 * 3. Relationship gap detection — no conversation in 3+ days
 * 4. Deadline approaching — calendar events in next 24h
 * 5. Contradiction alert — KG has conflicting relationships
 * 6. Stress correlation — emotion engine shows high tension
 * 7. Pattern detection — conversation frequency changed significantly
 * 8. Priority shift — too many high-priority tasks pending
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
) {
    data class ProactiveFinding(
        val type: kotlin.String,
        val title: kotlin.String,
        val message: kotlin.String,
        val urgency: Float, // 0-1
        val actionRoute: kotlin.String? = null,
    )

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
        return findings.sortedByDescending { it.urgency }
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
            actionRoute = "memory",
        ))
    }

    private suspend fun checkStuckTasks(): List<ProactiveFinding> {
        val now = System.currentTimeMillis()
        val sevenDaysMs = 7L * 24 * 60 * 60 * 1000
        val cutoff = now - sevenDaysMs
        val tasks = taskDao.all()
        val stuck = tasks.filter { it.status == "pending" && it.createdAt < cutoff }
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
            actionRoute = "tasks",
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
            actionRoute = "chat",
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
            actionRoute = "calendar",
        ))
    }

    /** Check 5: Contradiction alert — KG has conflicting relationships */
    private suspend fun checkContradictions(): List<ProactiveFinding> {
        val repo = kgRepository ?: return emptyList()
        val nodes = repo.recent(100)
        val contradictions = mutableListOf<kotlin.String>()
        for (node in nodes) {
            val neighbors = repo.getNeighbors(node.id)
            val allEdges = neighbors.incoming + neighbors.outgoing
            val byTarget = allEdges.groupBy { it.targetId }
            for ((_, rels) in byTarget) {
                if (rels.size > 1 && rels.map { it.type }.distinct().size > 1) {
                    contradictions.add(node.label)
                }
            }
        }
        if (contradictions.isEmpty()) return emptyList()
        return listOf(ProactiveFinding(
            type = "contradiction_alert",
            title = "${contradictions.size} conflicting relationship(s) in knowledge graph",
            message = contradictions.joinToString("; "),
            urgency = 0.5f,
            actionRoute = "graph",
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
            actionRoute = "chat",
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
            actionRoute = "tasks",
        ))
    }
}