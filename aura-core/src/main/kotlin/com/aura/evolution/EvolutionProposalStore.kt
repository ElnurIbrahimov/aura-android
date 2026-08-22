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

    /**
     * Post-apply evaluation feedback loop.
     *
     * After a proposal is applied, the system can record whether the
     * change was effective — did the user use the new skill? Was the
     * memory consolidation useful? Was the proactive rule helpful?
     *
     * The outcome is stored in the proposal's outcomeNote field as
     * a JSON string: {"score": 0.8, "signal": "skill_used_3x", "daysAfter": 7}
     *
     * This closes the evolution loop: proposals are created → evaluated →
     * approved → applied → outcome recorded → future proposals informed
     * by past outcomes.
     */
    suspend fun recordOutcome(
        id: kotlin.String,
        score: Float,           // 0.0 = harmful, 0.5 = neutral, 1.0 = helpful
        signal: kotlin.String,  // what evidence triggered the evaluation
        daysAfter: Int = 0,     // how many days after apply
    ) {
        val proposal = proposalDao.getById(id) ?: return
        val outcomeJson = """{"score":$score,"signal":"$signal","daysAfter":$daysAfter,"timestamp":${System.currentTimeMillis()}}"""
        proposalDao.upsert(proposal.copy(
            outcomeNote = outcomeJson,
            updatedAt = System.currentTimeMillis(),
        ))
    }

    /**
     * Read past outcomes for proposals in a given domain.
     * Used by the coordinator to inform future proposals —
     * domains with high outcome scores get more candidates,
     * domains with low scores get fewer.
     */
    suspend fun pastOutcomes(domain: kotlin.String): List<ProposalOutcome> {
        return proposalDao.byDomain(domain)
            .filter { it.status == ProposalStatus.APPLIED.name || it.status == ProposalStatus.ROLLED_BACK.name }
            .mapNotNull { proposal ->
                val note = proposal.outcomeNote
                if (note.isBlank() || !note.startsWith("{")) return@mapNotNull null
                val score = Regex("\"score\":([\\d.]+)").find(note)?.groupValues?.get(1)?.toFloatOrNull() ?: 0.5f
                val signal = Regex("\"signal\":\"([^\"]+)\"").find(note)?.groupValues?.get(1) ?: ""
                ProposalOutcome(proposal.id, score, signal, proposal.resolvedAt ?: 0L)
            }
    }

    /**
     * Find resolved (applied or rolled-back) proposals that have no outcome
     * recorded yet. Used by the coordinator's outcome-scoring loop: applied
     * proposals are scored from evidence ≥7d post-apply; rollbacks score 0.1.
     *
     * "No outcome yet" means [EvolutionProposalEntity.outcomeNote] does not
     * hold outcome JSON — apply/rollback leave a human-readable note there
     * (e.g. "patched skill x"), which recordOutcome later replaces with the
     * `{"score":…}` JSON blob that [pastOutcomes] parses.
     */
    suspend fun unscoredResolved(): List<EvolutionProposalEntity> {
        val all = mutableListOf<EvolutionProposalEntity>()
        for (domain in EvolutionDomain.entries) {
            all += proposalDao.byDomain(domain.name)
        }
        return all.filter {
            (it.status == ProposalStatus.APPLIED.name || it.status == ProposalStatus.ROLLED_BACK.name) &&
                !it.outcomeNote.trimStart().startsWith("{")
        }
    }

        data class ProposalOutcome(
        val proposalId: kotlin.String,
        val score: Float,
        val signal: kotlin.String,
        val resolvedAt: kotlin.Long,
    )

    suspend fun markApplyFailed(id: kotlin.String, reason: kotlin.String) {
        proposalDao.setStatus(id, ProposalStatus.APPLY_FAILED.name, reason)
    }

    suspend fun recordRollbackSnapshot(id: kotlin.String, snapshotJson: kotlin.String) {
        val proposal = proposalDao.getById(id) ?: return
        proposalDao.upsert(proposal.copy(rollbackSnapshotJson = snapshotJson, updatedAt = System.currentTimeMillis()))
    }
}
