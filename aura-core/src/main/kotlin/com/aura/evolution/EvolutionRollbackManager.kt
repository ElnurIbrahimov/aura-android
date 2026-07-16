package com.aura.evolution

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reverts an applied proposal by restoring the latest revision's
 * before-ciphertext. Detects conflicts: if a newer proposal for the same
 * domain/target is already APPLIED, the rollback needs explicit resolution.
 */
@Singleton
class EvolutionRollbackManager @Inject constructor(
    private val proposalDao: EvolutionProposalDao,
    private val revisionDao: EvolutionRevisionDao,
) {
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
        val revisions = revisionDao.history(proposal.domain, proposal.targetId, limit = 1)
        val latest = revisions.firstOrNull()
            ?: return RollbackResult.Error("no revision to restore")
        if (latest.snapshotCiphertext.isNullOrBlank()) {
            return RollbackResult.Error("no before-ciphertext captured")
        }
        proposalDao.resolve(proposalId, ProposalStatus.ROLLED_BACK.name, "rolled back to revision ${latest.id}")
        return RollbackResult.Ok(latest.snapshotCiphertext)
    }

    suspend fun forceRollback(proposalId: kotlin.String): RollbackResult {
        // Skips conflict check; used after user confirmation.
        val proposal = proposalDao.getById(proposalId)
            ?: return RollbackResult.Error("proposal not found")
        if (proposal.status != ProposalStatus.APPLIED.name) {
            return RollbackResult.Error("proposal is ${proposal.status}, not applied")
        }
        val revisions = revisionDao.history(proposal.domain, proposal.targetId, limit = 1)
        val latest = revisions.firstOrNull()
            ?: return RollbackResult.Error("no revision to restore")
        if (latest.snapshotCiphertext.isNullOrBlank()) {
            return RollbackResult.Error("no before-ciphertext captured")
        }
        proposalDao.resolve(proposalId, ProposalStatus.ROLLED_BACK.name, "force-rolled back to revision ${latest.id}")
        return RollbackResult.Ok(latest.snapshotCiphertext)
    }

    sealed interface RollbackResult {
        data class Ok(val beforeCiphertext: kotlin.String) : RollbackResult
        data class Error(val message: kotlin.String) : RollbackResult
        data class Conflict(val message: kotlin.String, val newerProposalId: kotlin.String) : RollbackResult
    }
}
