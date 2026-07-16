package com.aura.evolution

import kotlinx.serialization.json.Json
import javax.inject.Singleton
import javax.inject.Inject

/**
 * Reverts an applied evolution proposal. It restores the plaintext snapshot
 * stored in [EvolutionProposalEntity.rollbackSnapshotJson] when available,
 * and deletes artifacts that were created by the proposal.
 */
@Singleton
class EvolutionRollbackManager @Inject constructor(
    private val proposalDao: EvolutionProposalDao,
    private val revisionDao: EvolutionRevisionDao,
    private val metrics: EvolutionMetrics,
    private val skillsStore: com.aura.skills.SkillsStore? = null,
    private val memoryStore: com.aura.memory.MemoryStore? = null,
    private val proactiveEventDao: com.aura.proactive.ProactiveEventDao? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun rollback(proposalId: kotlin.String): RollbackResult {
        val proposal = proposalDao.getById(proposalId)
            ?: return RollbackResult.Error("proposal not found")
        if (proposal.status != ProposalStatus.APPLIED.name) {
            return RollbackResult.Error("proposal is ${proposal.status}, not applied")
        }
        val conflict = proposalDao.open()
            .filter { it.domain == proposal.domain && it.targetId == proposal.targetId && it.id != proposalId && it.status == ProposalStatus.APPLIED.name }
            .maxByOrNull { it.updatedAt }
        if (conflict != null) {
            return RollbackResult.Conflict(
                message = "newer applied proposal ${conflict.id} conflicts with rollback",
                newerProposalId = conflict.id,
            )
        }
        val artifactResult = restoreArtifact(proposal)
        proposalDao.resolve(proposalId, ProposalStatus.ROLLED_BACK.name, "rolled back")
        metrics.record("proposal.rolled_back")
        return artifactResult
    }

    suspend fun forceRollback(proposalId: kotlin.String): RollbackResult {
        val proposal = proposalDao.getById(proposalId)
            ?: return RollbackResult.Error("proposal not found")
        if (proposal.status != ProposalStatus.APPLIED.name) {
            return RollbackResult.Error("proposal is ${proposal.status}, not applied")
        }
        val artifactResult = restoreArtifact(proposal)
        proposalDao.resolve(proposalId, ProposalStatus.ROLLED_BACK.name, "force-rolled back")
        metrics.record("proposal.force_rolled_back")
        return artifactResult
    }

    private suspend fun restoreArtifact(proposal: EvolutionProposalEntity): RollbackResult {
        val action = runCatching { EvolutionAction.valueOf(proposal.action) }.getOrNull()
            ?: return RollbackResult.Error("unknown action ${proposal.action}")
        return when (action) {
            EvolutionAction.CREATE_SKILL -> {
                skillsStore?.remove(proposal.targetId)
                RollbackResult.Ok("removed created skill")
            }
            EvolutionAction.PATCH_SKILL, EvolutionAction.REWRITE_SKILL -> {
                val snapshot = proposal.rollbackSnapshotJson.takeIf { it.isNotBlank() && it != "{}" }
                    ?: return RollbackResult.Error("no rollback snapshot")
                val skill = runCatching { json.decodeFromString<com.aura.skills.Skill>(snapshot) }.getOrNull()
                    ?: return RollbackResult.Error("snapshot is not a valid Skill")
                skillsStore?.update(skill) ?: return RollbackResult.Error("SkillsStore not available")
                RollbackResult.Ok("restored skill ${skill.name}")
            }
            EvolutionAction.RETIRE_SKILL -> {
                val snapshot = proposal.rollbackSnapshotJson.takeIf { it.isNotBlank() && it != "{}" }
                    ?: return RollbackResult.Error("no rollback snapshot")
                val skill = runCatching { json.decodeFromString<com.aura.skills.Skill>(snapshot) }.getOrNull()
                    ?: return RollbackResult.Error("snapshot is not a valid Skill")
                skillsStore?.add(skill) ?: return RollbackResult.Error("SkillsStore not available")
                RollbackResult.Ok("restored retired skill ${skill.name}")
            }
            EvolutionAction.UPDATE_MEMORY_CATEGORY -> {
                val snapshot = proposal.rollbackSnapshotJson.takeIf { it.isNotBlank() && it != "{}" }
                    ?: return RollbackResult.Error("no rollback snapshot")
                val mem = runCatching { json.decodeFromString<com.aura.memory.MemoryEntity>(snapshot) }.getOrNull()
                    ?: return RollbackResult.Error("snapshot is not a valid MemoryEntity")
                memoryStore?.update(mem.id, mem.content, mem.category, mem.importance, mem.tags)
                    ?: return RollbackResult.Error("MemoryStore not available")
                RollbackResult.Ok("restored memory category")
            }
            EvolutionAction.FORGET_MEMORY -> {
                val snapshot = proposal.rollbackSnapshotJson.takeIf { it.isNotBlank() && it != "{}" }
                if (snapshot != null) {
                    val mem = runCatching { json.decodeFromString<com.aura.memory.MemoryEntity>(snapshot) }.getOrNull()
                    if (mem != null) memoryStore?.restore(mem)
                }
                RollbackResult.Ok("restored forgotten memory")
            }
            EvolutionAction.NEW_PROACTIVE_RULE -> {
                proactiveEventDao?.deleteByCorrelationTag("evolution:${proposal.id}")
                RollbackResult.Ok("removed created proactive rule")
            }
            else -> RollbackResult.Error("rollback not implemented for $action")
        }
    }

    sealed interface RollbackResult {
        data class Ok(val summary: kotlin.String) : RollbackResult
        data class Error(val message: kotlin.String) : RollbackResult
        data class Conflict(val message: kotlin.String, val newerProposalId: kotlin.String) : RollbackResult
    }
}
