package com.aura.evolution

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deterministic post-apply outcome scoring from real evidence (D6). Replaces
 * the flat 0.7 "survived without rollback" heuristic and the unread LLM
 * evaluators.
 *
 * A proposal is scored only once it is at least [MIN_DAYS_AFTER_APPLY] days
 * past apply (except rollback, which is an immediate strong negative). Scores
 * feed [EvolutionProposalStore.recordOutcome] and thus the coordinator's
 * domain-suppression loop.
 *
 * Legacy proposals whose patchJson is "{}" are tolerated: scoring never reads
 * the patch — only status, action, targetId, resolvedAt, and evidence.
 */
@Singleton
class EvolutionOutcomeScorer @Inject constructor(
    private val evidenceDao: EvolutionEvidenceDao,
) {
    data class Outcome(val score: Float, val signal: String, val daysAfter: Int)

    suspend fun score(
        proposal: EvolutionProposalEntity,
        now: Long = System.currentTimeMillis(),
    ): Outcome? {
        val resolvedAt = proposal.resolvedAt?.takeIf { it > 0 } ?: return null
        val days = ((now - resolvedAt) / DAY_MS).toInt()
        // A rollback is the strongest negative signal we have — score it
        // immediately, no waiting period.
        if (proposal.status == ProposalStatus.ROLLED_BACK.name) {
            return Outcome(0.1f, "rolled_back", days)
        }
        if (proposal.status != ProposalStatus.APPLIED.name) return null
        if (days < MIN_DAYS_AFTER_APPLY) return null

        val action = runCatching { EvolutionAction.valueOf(proposal.action) }.getOrNull()
            // Legacy row for a removed action: neutral, evidence can't be mapped.
            ?: return Outcome(0.5f, "legacy_action_${proposal.action.lowercase()}", days)

        return when (action) {
            EvolutionAction.PATCH_SKILL -> scoreFailureRateShift(proposal.targetId, resolvedAt, days)
            EvolutionAction.RETIRE_SKILL -> scoreRetiredSkill(proposal.targetId, resolvedAt, days)
            EvolutionAction.PROMOTE_TO_HAND -> scoreInvocationShift(proposal.targetId, resolvedAt, days)
            EvolutionAction.CONSOLIDATE_MEMORIES ->
                Outcome(0.7f, "consolidation_survived_${days}d", days)
        }
    }

    /** PATCH_SKILL: failure-rate before vs after apply → 0.9 / 0.7 / 0.3. */
    private suspend fun scoreFailureRateShift(targetId: String, appliedAt: Long, days: Int): Outcome {
        val (beforeRate, afterRate) = ratesAround("skill_failed", targetId, appliedAt, days)
        return when {
            afterRate < beforeRate -> Outcome(0.9f, "failure_rate_improved", days)
            afterRate > beforeRate -> Outcome(0.3f, "failure_rate_worse", days)
            else -> Outcome(0.7f, "failure_rate_unchanged", days)
        }
    }

    /**
     * RETIRE_SKILL: the skill is gone; any invocation evidence after apply
     * means it was re-created / still needed → the retirement was harmful.
     */
    private suspend fun scoreRetiredSkill(targetId: String, appliedAt: Long, days: Int): Outcome {
        val invokedAfter = evidenceDao
            .byKind(EvolutionDomain.SKILL.name, "skill_invoked", EVIDENCE_LIMIT)
            .count { it.sourceEntityId == targetId && it.createdAt >= appliedAt }
        return if (invokedAfter > 0) {
            Outcome(0.3f, "retired_skill_still_invoked", days)
        } else {
            Outcome(0.7f, "retired_skill_stayed_quiet", days)
        }
    }

    /**
     * PROMOTE_TO_HAND: if manual skill invocations dropped after the hand was
     * created, the automation took over → strong positive.
     */
    private suspend fun scoreInvocationShift(targetId: String, appliedAt: Long, days: Int): Outcome {
        val (beforeRate, afterRate) = ratesAround("skill_invoked", targetId, appliedAt, days)
        return if (afterRate < beforeRate) {
            Outcome(0.9f, "invocations_shifted_to_hand", days)
        } else {
            Outcome(0.7f, "invocation_rate_unchanged", days)
        }
    }

    /**
     * Per-day evidence rate for [kind]/[targetId] in the [WINDOW_DAYS] window
     * before apply vs the (up to [WINDOW_DAYS]) window after apply.
     */
    private suspend fun ratesAround(
        kind: String,
        targetId: String,
        appliedAt: Long,
        days: Int,
    ): Pair<Double, Double> {
        val events = evidenceDao
            .byKind(EvolutionDomain.SKILL.name, kind, EVIDENCE_LIMIT)
            .filter { it.sourceEntityId == targetId }
        val windowMs = WINDOW_DAYS * DAY_MS
        val before = events.count { it.createdAt >= appliedAt - windowMs && it.createdAt < appliedAt }
        val afterWindowDays = days.coerceAtMost(WINDOW_DAYS.toInt()).coerceAtLeast(1)
        val after = events.count {
            it.createdAt >= appliedAt && it.createdAt < appliedAt + afterWindowDays * DAY_MS
        }
        return (before.toDouble() / WINDOW_DAYS) to (after.toDouble() / afterWindowDays)
    }

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1000L
        const val MIN_DAYS_AFTER_APPLY = 7
        const val WINDOW_DAYS = 14L
        const val EVIDENCE_LIMIT = 500
    }
}
