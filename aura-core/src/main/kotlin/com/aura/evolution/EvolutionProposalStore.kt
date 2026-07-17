package com.aura.evolution

import java.util.UUID
import javax.inject.Singleton
import javax.inject.Inject
import com.aura.evolution.EvolutionMetrics

/**
 * Creates and resolves evolution proposals. Keeps the candidate-to-proposal
 * lifecycle explicit and auditable.
 */
@Singleton
class EvolutionProposalStore @Inject constructor(
    private val proposalDao: EvolutionProposalDao,
    private val revisionDao: EvolutionRevisionDao,
    private val candidateDao: EvolutionCandidateDao,
    private val metrics: EvolutionMetrics,
    private val safetyGuard: EvolutionSafetyGuard,
) {
    /**
     * Create a proposal from a high-confidence candidate. The caller is
     * responsible for reflection / user approval before invoking apply.
     */
    suspend fun fromCandidate(candidate: EvolutionCandidateEntity): EvolutionProposalEntity {
        safetyGuard.validateProposal(candidate).getOrThrow()
        val proposal = EvolutionProposalEntity(
            id = UUID.randomUUID().toString(),
            domain = candidate.domain,
            action = candidate.action,
            targetId = candidate.targetId,
            title = "${candidate.action}: ${candidate.targetId}",
            summary = candidate.rationale,
            confidence = candidate.score,
            patchJson = candidate.argsJson,
            requiresApproval = true,
        )
        proposalDao.upsert(proposal)
        candidateDao.setStatus(candidate.id, CandidateStatus.PROMOTED.name, "promoted to proposal ${proposal.id}")
        return proposal
    }

    suspend fun getById(id: kotlin.String): EvolutionProposalEntity? = proposalDao.getById(id)

    suspend fun approve(id: kotlin.String) {
        proposalDao.setStatus(id, ProposalStatus.APPROVED.name, "approved by user")
    }

    suspend fun reject(id: kotlin.String, reason: kotlin.String = "") {
        proposalDao.setStatus(id, ProposalStatus.REJECTED.name, reason)
    }

    suspend fun markApplied(id: kotlin.String, note: kotlin.String = "") {
        proposalDao.resolve(id, ProposalStatus.APPLIED.name, note)
    }

    suspend fun markApplyFailed(id: kotlin.String, reason: kotlin.String) {
        proposalDao.setStatus(id, ProposalStatus.APPLY_FAILED.name, reason)
    }

    suspend fun recordRollbackSnapshot(id: kotlin.String, snapshotJson: kotlin.String) {
        val proposal = proposalDao.getById(id) ?: return
        proposalDao.upsert(proposal.copy(rollbackSnapshotJson = snapshotJson, updatedAt = System.currentTimeMillis()))
    }

    suspend fun recordRevision(
        proposalId: kotlin.String,
        targetId: kotlin.String,
        domain: EvolutionDomain,
        beforeCiphertext: kotlin.String?,
        afterCiphertext: kotlin.String?,
        summary: kotlin.String,
    ) {
        revisionDao.upsert(
            EvolutionRevisionEntity(
                id = UUID.randomUUID().toString(),
                proposalId = proposalId,
                targetId = targetId,
                domain = domain.name,
                snapshotCiphertext = afterCiphertext ?: "",
                metadataJson = summary,
            )
        )
    }
}
