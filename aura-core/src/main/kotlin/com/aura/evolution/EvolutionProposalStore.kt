package com.aura.evolution

import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates and resolves evolution proposals. Keeps the candidate-to-proposal
 * lifecycle explicit and auditable.
 */
@Singleton
class EvolutionProposalStore @Inject constructor(
    private val proposalDao: EvolutionProposalDao,
    private val revisionDao: EvolutionRevisionDao,
    private val candidateDao: EvolutionCandidateDao,
) {
    /**
     * Create a proposal from a high-confidence candidate. The caller is
     * responsible for reflection / user approval before invoking apply.
     */
    suspend fun fromCandidate(candidate: EvolutionCandidateEntity): EvolutionProposalEntity {
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
