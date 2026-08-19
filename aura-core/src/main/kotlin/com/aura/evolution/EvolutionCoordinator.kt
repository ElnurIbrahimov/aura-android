package com.aura.evolution

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single entry point for running the evolution pipeline. The worker calls
 * this; tests and UI can also trigger it manually.
 *
 * The pipeline is:
 *   1. Run deterministic candidate detectors (deduped on domain/action/targetId).
 *   2. For pending high-confidence candidates in reflection-enabled domains,
 *      make ONE LLM call per candidate via [EvolutionPatchAuthor] that both
 *      reviews the candidate and authors the schema-validated patch.
 *   3. Promote approved candidates to proposals carrying the real patch.
 *   4. Auto-apply only where the domain opted in AND the safety guard allows
 *      it — SKILL never auto-applies, even against a stale DB flag (D4).
 *   5. Record deterministic outcomes for resolved proposals ≥7 days old.
 */
@Singleton
class EvolutionCoordinator @Inject constructor(
    private val detectors: EvolutionCandidateDetectors,
    private val metrics: EvolutionMetricsRecorder,
    private val patchAuthor: EvolutionPatchAuthor,
    private val proposalStore: EvolutionProposalStore,
    private val candidateDao: EvolutionCandidateDao,
    private val settingsDao: EvolutionSettingsDao,
    private val safetyGuard: EvolutionSafetyGuard,
    private val outcomeScorer: EvolutionOutcomeScorer? = null,
    private val applySaga: EvolutionApplySaga? = null,
) {
    suspend fun runAll(): RunResult {
        val start = System.currentTimeMillis()
        val candidates = detectors.runAll()
        val promoted = authorAndPromote(candidates)
        runCatching {
            recordPendingOutcomes()
        }.onFailure { android.util.Log.w(TAG, "outcome recording failed: ${it.message}", it) }
        val duration = System.currentTimeMillis() - start
        metrics.recordRun(candidates.size, duration)
        return RunResult(candidates.size, promoted, duration)
    }

    /**
     * Author patches for pending candidates with score above
     * [AUTHORING_SCORE_THRESHOLD] in domains where reflection is enabled.
     * One LLM call per candidate returns {decision, reason, patch}; approved
     * candidates become proposals that carry the validated patch.
     */
    private suspend fun authorAndPromote(candidates: List<EvolutionCandidateEntity>): Int {
        val settingsByDomain = settingsDao.all().associateBy { it.domain }
        // Fetch past outcomes per domain to suppress domains with consistently
        // low scores. A domain where recent outcomes average < 0.4 gets no
        // authoring slots — the system is telling us its proposals aren't helping.
        val domainOutcomes = mutableMapOf<String, Float>()
        for (domain in EvolutionDomain.entries) {
            val outcomes = runCatching { proposalStore.pastOutcomes(domain.name) }
                .onFailure { android.util.Log.w(TAG, "pastOutcomes fetch failed for ${domain.name}: ${it.message}", it) }
                .getOrDefault(emptyList())
            if (outcomes.isNotEmpty()) {
                domainOutcomes[domain.name] = outcomes.map { it.score }.average().toFloat()
            }
        }
        var promoted = 0
        // Cap the number of LLM calls per run to prevent unbounded cost when
        // many candidates accumulate. Each authoring call is one LLM call on
        // the user's configured EVOLUTION model.
        val pendingCount = candidates.count { it.status == CandidateStatus.PENDING.name }
        val pending = candidates.filter { it.status == CandidateStatus.PENDING.name }
            .take(MAX_AUTHORING_CALLS_PER_RUN)
        if (pending.size < pendingCount) {
            android.util.Log.i(TAG,
                "Capping authoring at $MAX_AUTHORING_CALLS_PER_RUN of $pendingCount pending candidates")
        }
        for (candidate in pending) {
            val settings = settingsByDomain[candidate.domain]
                ?: EvolutionSettingsEntity(domain = candidate.domain)
            // The master switch, checked before reflection. It is documented as one and
            // rendered as a Switch per domain in EvolutionInboxScreen, and until now it was
            // written, read back to draw itself, and consulted nowhere — a domain the user
            // had switched off still spent LLM calls and still promoted proposals.
            if (!settings.enabled) continue
            if (!settings.reflectionEnabled) continue
            if (candidate.score < AUTHORING_SCORE_THRESHOLD) continue
            // Skip candidates in domains where recent outcomes are consistently poor.
            val avgScore = domainOutcomes[candidate.domain]
            if (avgScore != null && avgScore < 0.4f) {
                android.util.Log.i(TAG, "Skipping candidate in domain ${candidate.domain}: avg outcome $avgScore < 0.4")
                continue
            }

            when (val result = patchAuthor.author(candidate)) {
                is EvolutionPatchAuthor.Result.Approved -> {
                    // The proposal carries the REAL authored patch, not the
                    // detector's empty argsJson.
                    val authored = candidate.copy(
                        argsJson = result.patchJson,
                        reflectionResult = result.reason,
                    )
                    val proposal = runCatching { proposalStore.fromCandidate(authored) }
                        .onFailure {
                            android.util.Log.w(TAG, "fromCandidate failed for ${candidate.id}: ${it.message}", it)
                            candidateDao.setStatus(candidate.id, CandidateStatus.REJECTED.name, "safety: ${it.message}")
                        }
                        .getOrNull() ?: continue
                    promoted++
                    maybeAutoApply(candidate, settings, proposal, result.reason)
                }
                is EvolutionPatchAuthor.Result.Rejected -> {
                    candidateDao.setStatus(candidate.id, CandidateStatus.REJECTED.name, "model: ${result.reason}")
                }
                is EvolutionPatchAuthor.Result.Inconclusive -> {
                    // The model replied but nothing usable could be read out of
                    // it. That is not a judgement about the candidate, so it
                    // must not resolve it — keep PENDING, same as a transport
                    // error. Previously this path returned Rejected, so one
                    // stray fence permanently discarded a candidate.
                    android.util.Log.i(TAG, "Inconclusive author for ${candidate.id}: ${result.reason}")
                    candidateDao.setStatus(
                        candidate.id,
                        CandidateStatus.PENDING.name,
                        "author_inconclusive: ${result.reason}",
                    )
                }
                is EvolutionPatchAuthor.Result.Error -> {
                    // Transport error — keep PENDING so a later run retries.
                    candidateDao.setStatus(candidate.id, CandidateStatus.PENDING.name, "author_error: ${result.code}")
                }
            }
        }
        return promoted
    }

    /**
     * D4: auto-apply requires BOTH the per-domain opt-in flag AND the safety
     * guard. The guard check is enforced here (not only at settings-write
     * time) so a stale/imported autoApplyApproved=true row can never
     * auto-apply a SKILL change.
     */
    private suspend fun maybeAutoApply(
        candidate: EvolutionCandidateEntity,
        settings: EvolutionSettingsEntity,
        proposal: EvolutionProposalEntity,
        reason: String,
    ) {
        val saga = applySaga ?: return
        if (!settings.enabled) return
        if (!settings.autoApplyApproved) return
        if (!safetyGuard.canAutoApply(candidate.domain)) {
            android.util.Log.i(TAG,
                "auto-apply blocked by safety guard for domain ${candidate.domain} (proposal ${proposal.id})")
            return
        }
        runCatching {
            val result = saga.apply(proposal)
            if (result is EvolutionApplySaga.ApplyResult.Ok) {
                candidateDao.setStatus(candidate.id, CandidateStatus.AUTO_APPLIED.name, "auto-applied: $reason")
                android.util.Log.i(TAG, "auto-applied proposal ${proposal.id} in domain ${candidate.domain}: ${result.summary}")
            } else {
                candidateDao.setStatus(candidate.id, CandidateStatus.PROMOTED.name, "auto-apply failed, pending review: $reason")
            }
        }.onFailure { android.util.Log.w(TAG, "auto-apply threw for ${proposal.id}: ${it.message}", it) }
    }

    /**
     * Record deterministic outcomes (D6) for resolved proposals that have no
     * outcome yet: rollbacks score 0.1 immediately; applied proposals are
     * scored from real evidence once they are ≥7 days past apply.
     */
    private suspend fun recordPendingOutcomes() {
        val scorer = outcomeScorer ?: return
        val unscored = proposalStore.unscoredResolved()
        if (unscored.isEmpty()) return
        android.util.Log.i(TAG, "Scoring outcomes for ${unscored.size} unscored proposals")
        for (proposal in unscored) {
            val outcome = runCatching { scorer.score(proposal) }
                .onFailure { android.util.Log.w(TAG, "scoring failed for ${proposal.id}: ${it.message}", it) }
                .getOrNull() ?: continue
            runCatching {
                proposalStore.recordOutcome(proposal.id, outcome.score, outcome.signal, outcome.daysAfter)
            }.onFailure { android.util.Log.w(TAG, "recordOutcome failed for ${proposal.id}: ${it.message}", it) }
        }
    }

    data class RunResult(
        val candidateCount: Int,
        val promotedCount: Int,
        val durationMs: kotlin.Long,
    )

    companion object {
        private const val TAG = "EvolutionCoordinator"

        /**
         * Score a candidate must reach before an LLM call is spent authoring a
         * patch for it. Public because the detectors calibrate their scores
         * against it — a bar that moves without them is a bar that silently
         * changes which candidates are ever seen.
         */
        const val AUTHORING_SCORE_THRESHOLD = 0.7f
        // Maximum LLM authoring calls per evolution run. Prevents cost
        // explosion when many candidates accumulate between runs.
        private const val MAX_AUTHORING_CALLS_PER_RUN = 10
    }
}
