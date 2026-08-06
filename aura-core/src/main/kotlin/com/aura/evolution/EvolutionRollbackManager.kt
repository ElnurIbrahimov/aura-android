package com.aura.evolution

import javax.inject.Singleton
import javax.inject.Inject

/**
 * Reverts an applied evolution proposal by decoding the TYPED rollback
 * snapshot recorded by [EvolutionApplySaga] (D7) and performing the exact
 * inverse of the apply:
 *
 * - PATCH_SKILL: restore the snapshotted pre-patch [com.aura.skills.Skill].
 * - RETIRE_SKILL: re-add the snapshotted skill.
 * - PROMOTE_TO_HAND: delete the created hand by its recorded id.
 * - CONSOLIDATE_MEMORIES: forget the consolidated memory and restore every
 *   snapshotted source entity.
 */
@Singleton
class EvolutionRollbackManager @Inject constructor(
    private val proposalDao: EvolutionProposalDao,
    private val revisionDao: EvolutionRevisionDao,
    private val metrics: EvolutionMetrics,
    private val skillsStore: com.aura.skills.SkillsStore? = null,
    private val memoryStore: com.aura.memory.MemoryStore? = null,
    private val handRepository: com.aura.hands.HandRepository? = null,
) {
    private val json = EvolutionPatchJson.json

    private companion object {
        private const val TAG = "EvolutionRollback"
    }

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
        val action = runCatching { EvolutionAction.valueOf(proposal.action) }
            .onFailure { android.util.Log.w(TAG, "rollback: parse action failed: ${it.message}", it) }
            .getOrNull()
            ?: return RollbackResult.Error("unknown action ${proposal.action}")
        val snapshot = proposal.rollbackSnapshotJson.takeIf { it.isNotBlank() && it != "{}" }
            ?: return RollbackResult.Error("no rollback snapshot")
        return when (action) {
            EvolutionAction.PATCH_SKILL -> {
                val skill = decodeSkill(snapshot)
                    ?: return RollbackResult.Error("snapshot is not a valid Skill")
                skillsStore?.update(skill) ?: return RollbackResult.Error("SkillsStore not available")
                RollbackResult.Ok("restored skill ${skill.name}")
            }
            EvolutionAction.RETIRE_SKILL -> {
                val skill = decodeSkill(snapshot)
                    ?: return RollbackResult.Error("snapshot is not a valid Skill")
                skillsStore?.add(skill) ?: return RollbackResult.Error("SkillsStore not available")
                RollbackResult.Ok("restored retired skill ${skill.name}")
            }
            EvolutionAction.PROMOTE_TO_HAND -> {
                val repo = handRepository ?: return RollbackResult.Error("HandRepository not available")
                val snap = runCatching { json.decodeFromString<PromoteToHandSnapshot>(snapshot) }
                    .onFailure { android.util.Log.w(TAG, "rollback: decode PromoteToHandSnapshot failed: ${it.message}", it) }
                    .getOrNull()
                    ?: return RollbackResult.Error("snapshot is not a valid PromoteToHandSnapshot")
                repo.deleteById(snap.handId)
                RollbackResult.Ok("removed hand '${snap.handName}' created by this proposal")
            }
            EvolutionAction.CONSOLIDATE_MEMORIES -> {
                val store = memoryStore ?: return RollbackResult.Error("MemoryStore not available")
                val snap = runCatching { json.decodeFromString<ConsolidateMemoriesSnapshot>(snapshot) }
                    .onFailure { android.util.Log.w(TAG, "rollback: decode ConsolidateMemoriesSnapshot failed: ${it.message}", it) }
                    .getOrNull()
                    ?: return RollbackResult.Error("snapshot is not a valid ConsolidateMemoriesSnapshot")
                store.forget(snap.consolidatedMemoryId)
                for (source in snap.sources) {
                    store.restore(source)
                }
                RollbackResult.Ok("removed consolidated memory and restored ${snap.sources.size} source memories")
            }
        }
    }

    private fun decodeSkill(snapshot: kotlin.String): com.aura.skills.Skill? =
        runCatching { json.decodeFromString<com.aura.skills.Skill>(snapshot) }
            .onFailure { android.util.Log.w(TAG, "rollback: decode Skill snapshot failed: ${it.message}", it) }
            .getOrNull()

    sealed interface RollbackResult {
        data class Ok(val summary: kotlin.String) : RollbackResult
        data class Error(val message: kotlin.String) : RollbackResult
        data class Conflict(val message: kotlin.String, val newerProposalId: kotlin.String) : RollbackResult
    }
}
