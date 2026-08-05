package com.aura.agent.council

import com.aura.agent.state.AgentStateStore
import javax.inject.Inject
import javax.inject.Singleton
import android.util.Log

/**
 * Manages agent mood decay and recovery over time.
 *
 * Rules:
 * - Mood decays by 2 per hour of active use (participation in councils)
 * - Mood recovers by 5 per hour during idle (no participation)
 * - Energy decays by 3 per hour of active use
 * - Energy recovers by 8 per hour during idle
 * - When mood < 20: agent is "burned out" — abstains more, produces cynical stances
 * - When energy < 20: agent refuses to participate
 *
 * Called by CouncilOrchestrator before selecting agents and after
 * each session to update state.
 */
@Singleton
class AgentMoodEngine @Inject constructor(
    private val stateStore: AgentStateStore,
) {
    companion object {
        private const val MOOD_DECAY_PER_HOUR = 2f
        private const val MOOD_RECOVERY_PER_HOUR = 5f
        private const val ENERGY_DECAY_PER_HOUR = 3f
        private const val ENERGY_RECOVERY_PER_HOUR = 8f
        private const val BURNOUT_THRESHOLD = 20f
        private const val REFUSAL_THRESHOLD = 20f
    }

    /**
     * Apply time-based decay/recovery to an agent based on how long
     * since it was last active.
     *
     * @param agentId The agent to update
     * @param now Current timestamp (for testing)
     */
    suspend fun applyTimeDecay(agentId: kotlin.String, now: Long = System.currentTimeMillis()) {
        val state = stateStore.getState(agentId) ?: return
        val elapsedMs = now - state.lastActiveAt
        if (elapsedMs <= 0) return

        val elapsedHours = elapsedMs / (3600_000L).toFloat()
        if (elapsedHours < 0.01f) return // less than ~36s — skip

        // If agent was active recently, decay. If idle, recover.
        // We use lastActiveAt: if it was less than 1 hour ago, the agent
        // is considered "active" and decays. Otherwise it recovers.
        val isActive = elapsedHours < 1f
        val moodDelta = if (isActive) -MOOD_DECAY_PER_HOUR * elapsedHours else MOOD_RECOVERY_PER_HOUR * elapsedHours
        val energyDelta = if (isActive) -ENERGY_DECAY_PER_HOUR * elapsedHours else ENERGY_RECOVERY_PER_HOUR * elapsedHours

        val newMood = (state.mood + moodDelta).coerceIn(0f, 100f)
        val newEnergy = (state.energy + energyDelta).coerceIn(0f, 100f)

        stateStore.setMoodEnergy(agentId, newMood, newEnergy)
    }

    /**
     * Apply decay to all agents. Called at the start of each council session.
     */
    suspend fun decayAll(agentIds: List<kotlin.String>, now: Long = System.currentTimeMillis()) {
        agentIds.forEach { id ->
            runCatching { applyTimeDecay(id, now) }
                .onFailure { android.util.Log.w("AgentMoodEngine", "decay $id: ${it.message}", it) }
        }
    }

    /**
     * Whether an agent is too burned out to participate.
     * Energy below threshold = refuses to participate.
     */
    suspend fun canParticipate(agentId: kotlin.String): Boolean {
        val state = stateStore.getState(agentId) ?: return true
        return state.energy >= REFUSAL_THRESHOLD
    }

    /**
     * Whether an agent is in burnout (cynical, abstains more).
     * Mood below threshold = burned out.
     */
    suspend fun isBurnedOut(agentId: kotlin.String): Boolean {
        val state = stateStore.getState(agentId) ?: return false
        return state.mood < BURNOUT_THRESHOLD
    }

    /**
     * Filter agents who can participate (not exhausted).
     * Returns the subset of [agentIds] with energy above threshold.
     */
    suspend fun filterAvailable(agentIds: List<kotlin.String>): List<kotlin.String> {
        return agentIds.filter { id ->
            runCatching { canParticipate(id) }.onFailure { Log.w("AgentMoodEngine", "runCatching failed: ${it.message}", it) }.getOrDefault(true)
        }
    }
}