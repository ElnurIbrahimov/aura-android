package com.aura.proactive

import android.util.Log
import com.aura.agent.ConversationStore
import com.aura.memory.MemoryDao
import com.aura.memory.MemoryEntity
import com.aura.tasks.TaskDao
import com.aura.tasks.TaskEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Proactive awareness engine — runs periodic checks that surface
 * actionable insights to the user without being asked.
 *
 * Ported from Python Aura's `proactive/awareness.py` (8 checks).
 * Android implementation includes 3 new checks on top of the existing
 * CalendarMonitor + MorningBrief + DaemonWorker:
 *
 * 1. **Staleness check** — memories not accessed in 30 days. Suggests
 *    reviewing or archiving forgotten memories.
 * 2. **Goal-blocker detection** — tasks stuck in "pending" for >7 days.
 *    Suggests breaking them down or cancelling.
 * 3. **Relationship gap detection** — no conversation in 3+ days.
 *    Suggests reaching out or starting a new conversation.
 *
 * Each check returns a [ProactiveFinding] that the DaemonWorker can
 * post as a proactive event via [ProactiveEventBus].
 *
 * All checks are heuristic, local, no LLM cost.
 */
@Singleton
class ProactiveAwarenessEngine @Inject constructor(
    private val memoryDao: MemoryDao,
    private val taskDao: TaskDao,
    private val conversationStore: ConversationStore,
) {
    data class ProactiveFinding(
        val type: String,
        val title: String,
        val message: String,
        val urgency: Float, // 0-1
        val actionRoute: String? = null,
    )

    /**
     * Run all checks and return findings sorted by urgency (descending).
     */
    suspend fun runAll(): List<ProactiveFinding> {
        val findings = mutableListOf<ProactiveFinding>()
        runCatching { findings.addAll(checkStaleMemories()) }
            .onFailure { Log.w("ProactiveAwareness", "staleness check failed: ${it.message}") }
        runCatching { findings.addAll(checkStuckTasks()) }
            .onFailure { Log.w("ProactiveAwareness", "goal-blocker check failed: ${it.message}") }
        runCatching { findings.addAll(checkRelationshipGap()) }
            .onFailure { Log.w("ProactiveAwareness", "relationship gap check failed: ${it.message}") }
        return findings.sortedByDescending { it.urgency }
    }

    /**
     * Check 1: Stale memories — not accessed in 30+ days.
     * These are memories the user has forgotten about. Surfacing them
     * lets the user decide whether to archive or refresh.
     */
    private suspend fun checkStaleMemories(): List<ProactiveFinding> {
        val now = System.currentTimeMillis()
        val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000
        val cutoff = now - thirtyDaysMs
        val recent = memoryDao.recent(500)
        val stale = recent.filter { it.accessCount == 0 && it.createdAt < cutoff && it.decayScore > 0f }
        if (stale.isEmpty()) return emptyList()
        return listOf(
            ProactiveFinding(
                type = "stale_memories",
                title = "${stale.size} memories haven't been used in 30+ days",
                message = "These memories are fading. Review them to keep what matters and let go of what doesn't.",
                urgency = 0.2f,
                actionRoute = "memory",
            ),
        )
    }

    /**
     * Check 2: Goal-blocker detection — tasks stuck in "pending" for >7 days.
     * These are goals the user set but hasn't acted on. They're either
     * blocked, too big, or no longer relevant.
     */
    private suspend fun checkStuckTasks(): List<ProactiveFinding> {
        val now = System.currentTimeMillis()
        val sevenDaysMs = 7L * 24 * 60 * 60 * 1000
        val cutoff = now - sevenDaysMs
        val tasks = taskDao.all()
        val stuck = tasks.filter { it.status == "pending" && it.createdAt < cutoff }
        if (stuck.isEmpty()) return emptyList()
        val oldest = stuck.minByOrNull { it.createdAt }
        val daysStuck = oldest?.let { ((now - it.createdAt) / (24 * 60 * 60 * 1000)).toInt() } ?: 0
        return listOf(
            ProactiveFinding(
                type = "stuck_tasks",
                title = "${stuck.size} task(s) stuck for ${daysStuck}+ days",
                message = "Task \"${oldest?.title?.take(60)}\" has been pending since $daysStuck days ago. " +
                    if (daysStuck > 14) "Consider cancelling or breaking it into smaller steps."
                    else "Want to break it down or reschedule?",
                urgency = if (daysStuck > 14) 0.7f else 0.4f,
                actionRoute = "tasks",
            ),
        )
    }

    /**
     * Check 3: Relationship gap — no conversation in 3+ days.
     * The user hasn't talked to Aura in a while. A gentle nudge
     * to reconnect, not a demand.
     */
    private suspend fun checkRelationshipGap(): List<ProactiveFinding> {
        val now = System.currentTimeMillis()
        val threeDaysMs = 3L * 24 * 60 * 60 * 1000
        val recent = conversationStore.recent(1)
        if (recent.isEmpty()) return emptyList()
        val lastConv = recent.first()
        val lastActivity = lastConv.updatedAt
        if (lastActivity > now - threeDaysMs) return emptyList()
        val daysSince = ((now - lastActivity) / (24 * 60 * 60 * 1000)).toInt()
        return listOf(
            ProactiveFinding(
                type = "relationship_gap",
                title = "It's been $daysSince days since we last talked",
                message = when {
                    daysSince > 7 -> "Long time no see! I've been thinking about what we discussed. Want to pick up where we left off?"
                    daysSince > 5 -> "It's been a few days. Anything on your mind?"
                    else -> "Haven't heard from you in a while. I'm here when you need me."
                },
                urgency = if (daysSince > 7) 0.5f else 0.3f,
                actionRoute = "chat",
            ),
        )
    }
}