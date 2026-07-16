package com.aura.evolution

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reverts an applied proposal by restoring the latest revision's
 * before-ciphertext. The actual restore is delegated to the domain-specific
 * store; this manager orchestrates the proposal state transition.
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
        val revisions = revisionDao.history(proposal.domain, proposal.targetId, limit = 1)
        val latest = revisions.firstOrNull()
            ?: return RollbackResult.Error("no revision to restore")
        if (latest.snapshotCiphertext.isNullOrBlank()) {
            return RollbackResult.Error("no before-ciphertext captured")
        }
        // Domain-specific restore happens in caller; here we mark the proposal rolled back.
        proposalDao.resolve(proposalId, ProposalStatus.ROLLED_BACK.name, "rolled back to revision ${latest.id}")
        return RollbackResult.Ok(latest.snapshotCiphertext)
    }

    sealed interface RollbackResult {
        data class Ok(val beforeCiphertext: kotlin.String) : RollbackResult
        data class Error(val message: kotlin.String) : RollbackResult
    }
}
