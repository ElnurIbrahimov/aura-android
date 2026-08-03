package com.aura.agent.state

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Domain layer over [AgentStateDao], [AgentRelationshipDao], and
 * [AgentObservationDao]. Provides the API the Council uses to read
 * and mutate agent emotional/behavioural state.
 *
 * All state is persisted in Room (agent_state, agent_relationships,
 * agent_observations tables inside [com.aura.agent.AgentDatabase]).
 */
@Singleton
class AgentStateStore @Inject constructor(
    private val stateDao: AgentStateDao,
    private val relDao: AgentRelationshipDao,
    private val obsDao: AgentObservationDao,
) {
    private val mutex = Mutex()
    private val tag = "AgentStateStore"

    // ── State ──

    fun allStates(): Flow<List<AgentStateEntity>> = stateDao.all()

    suspend fun getState(agentId: kotlin.String): AgentStateEntity? =
        stateDao.getByAgent(agentId)

    suspend fun allStatesOnce(): List<AgentStateEntity> = stateDao.allOnce()

    /**
     * Ensure a state row exists for [agentId]. If missing, create one
     * with default values. Called during agent seeding.
     */
    suspend fun ensureState(agentId: kotlin.String) {
        if (stateDao.getByAgent(agentId) == null) {
            stateDao.upsert(AgentStateEntity(agentId = agentId))
        }
    }

    suspend fun setMoodEnergy(agentId: kotlin.String, mood: Float, energy: Float) {
        stateDao.updateMoodEnergy(agentId, mood.coerceIn(0f, 100f), energy.coerceIn(0f, 100f), System.currentTimeMillis())
    }

    suspend fun setGoal(agentId: kotlin.String, goal: kotlin.String) {
        stateDao.updateGoal(agentId, goal, System.currentTimeMillis())
    }

    suspend fun recordParticipation(agentId: kotlin.String) {
        stateDao.incrementParticipation(agentId, System.currentTimeMillis())
    }

    // ── Relationships ──

    suspend fun getRelationship(a: kotlin.String, b: kotlin.String): AgentRelationshipEntity? =
        relDao.between(a, b)

    suspend fun getRelationshipsFor(agentId: kotlin.String): List<AgentRelationshipEntity> =
        relDao.forAgent(agentId)

    /**
     * Record an interaction outcome between two agents and update
     * their affinity. Positive [affinityDelta] = collaboration,
     * negative = conflict.
     */
    suspend fun recordInteraction(
        a: kotlin.String,
        b: kotlin.String,
        affinityDelta: Float,
    ) = mutex.withLock {
        val existing = relDao.between(a, b)
        if (existing != null) {
            val newAffinity = (existing.affinity + affinityDelta).coerceIn(-100f, 100f)
            val newConflict = existing.conflictCount + if (affinityDelta < 0) 1 else 0
            val newCollab = existing.collaborationCount + if (affinityDelta > 0) 1 else 0
            relDao.upsert(existing.copy(
                affinity = newAffinity,
                conflictCount = newConflict,
                collaborationCount = newCollab,
                updatedAt = System.currentTimeMillis(),
            ))
        } else {
            relDao.upsert(AgentRelationshipEntity(
                agentAId = a,
                agentBId = b,
                affinity = affinityDelta.coerceIn(-100f, 100f),
                conflictCount = if (affinityDelta < 0) 1 else 0,
                collaborationCount = if (affinityDelta > 0) 1 else 0,
            ))
        }
        Unit
    }

    // ── Observations ──

    suspend fun addObservation(
        agentId: kotlin.String,
        targetType: kotlin.String,
        targetId: kotlin.String = "",
        content: kotlin.String,
        sentiment: Float = 0f,
        weight: Float = 0.5f,
    ) {
        obsDao.insert(AgentObservationEntity(
            agentId = agentId,
            targetType = targetType,
            targetId = targetId,
            content = content,
            sentiment = sentiment.coerceIn(-1f, 1f),
            weight = weight.coerceIn(0f, 1f),
        ))
    }

    suspend fun observationsForAgent(agentId: kotlin.String, limit: Int = 20): List<AgentObservationEntity> =
        obsDao.forAgent(agentId, limit)

    suspend fun unresolvedObservations(agentId: kotlin.String, limit: Int = 10): List<AgentObservationEntity> =
        obsDao.unresolvedForAgent(agentId, limit)

    suspend fun resolveObservation(id: Long) {
        obsDao.resolve(id)
    }

    suspend fun resolveAllForAgent(agentId: kotlin.String, targetType: kotlin.String) {
        obsDao.resolveAllForAgent(agentId, targetType)
    }

    // ── Bulk ──

    suspend fun deleteAll() {
        stateDao.deleteAll()
        relDao.deleteAll()
        obsDao.deleteAll()
    }
}