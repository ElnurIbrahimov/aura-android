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
            EvolutionAction.CONSOLIDATE_MEMORIES -> scoreConsolidation(proposal, resolvedAt, days)
        }
    }

    /**
     * CONSOLIDATE_MEMORIES: did the merged memory take over the recall load its
     * sources were carrying?
     *
     * This replaced a flat 0.7 named `consolidation_survived_Nd`, which read no
     * evidence at all — it scored "nobody rolled this back", and since rollback
     * was unreachable from the UI it scored "time passed". A consolidation that
     * quietly destroyed a fact the user relied on daily was indistinguishable
     * from one that worked.
     *
     * Recall is the right measure because it is the thing consolidation is
     * supposed to preserve: the same question should still find an answer. The
     * source ids come from the rollback snapshot, which is the only record of
     * what the apply actually touched.
     */
    private suspend fun scoreConsolidation(
        proposal: EvolutionProposalEntity,
        appliedAt: Long,
        days: Int,
    ): Outcome {
        val snapshot = runCatching {
            json.decodeFromString(ConsolidateMemoriesSnapshot.serializer(), proposal.rollbackSnapshotJson)
        }.getOrNull()
        val consolidatedId = snapshot?.consolidatedMemoryId?.takeIf { it.isNotBlank() }
            ?: return Outcome(0.5f, "consolidation_unverifiable", days)

        val spanDays = days.coerceAtMost(WINDOW_DAYS.toInt()).coerceAtLeast(1)
        val spanMs = spanDays * DAY_MS
        val before = snapshot.sources.sumOf { source ->
            recalls(source.id).count { it >= appliedAt - spanMs && it < appliedAt }
        }
        // No baseline means the sources were not being recalled either, so
        // there is nothing the merge could have lost. Neutral, not good.
        if (before == 0) return Outcome(0.5f, "consolidation_no_recall_baseline", days)

        val after = recalls(consolidatedId).count { it >= appliedAt && it < appliedAt + spanMs }
        return when {
            after >= before -> Outcome(0.9f, "consolidated_memory_carries_recall", days)
            after > 0 -> Outcome(0.6f, "consolidated_memory_recalled_less", days)
            else -> Outcome(0.2f, "consolidated_memory_never_recalled", days)
        }
    }

    private suspend fun recalls(memoryId: String): List<Long> = evidenceDao
        .forSource(memoryId, EVIDENCE_LIMIT)
        .filter { it.kind == "memory_recalled" }
        .map { it.createdAt }

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
     * Per-day evidence rate for [kind]/[targetId] in equal windows either side
     * of apply.
     *
     * Both windows are [days] long, capped at [WINDOW_DAYS]. They used to
     * differ: the before-rate always divided by 14 while the after-rate divided
     * by the elapsed days, so at the earliest scoring moment — day 7, when most
     * proposals are first scored — the same number of events read twice as high
     * after as before. Every outcome was biased toward "worse", and a patch
     * that changed nothing scored `failure_rate_worse`.
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
        val spanDays = days.coerceAtMost(WINDOW_DAYS.toInt()).coerceAtLeast(1)
        val spanMs = spanDays * DAY_MS
        val before = events.count { it.createdAt >= appliedAt - spanMs && it.createdAt < appliedAt }
        val after = events.count { it.createdAt >= appliedAt && it.createdAt < appliedAt + spanMs }
        return (before.toDouble() / spanDays) to (after.toDouble() / spanDays)
    }

    private companion object {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        const val DAY_MS = 24L * 60L * 60L * 1000L
        const val MIN_DAYS_AFTER_APPLY = 7
        const val WINDOW_DAYS = 14L
        const val EVIDENCE_LIMIT = 500
    }
}
