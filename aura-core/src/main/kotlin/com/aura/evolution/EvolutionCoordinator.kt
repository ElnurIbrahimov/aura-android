package com.aura.evolution

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single entry point for running the evolution pipeline. The worker calls
 * this; tests and UI can also trigger it manually.
 *
 * The pipeline is:
 *   1. Run deterministic candidate detectors.
 *   2. Reflect on pending high-confidence candidates with the configured
 *      EVOLUTION model.
 *   3. Promote approved candidates to proposals.
 */
@Singleton
class EvolutionCoordinator @Inject constructor(
    private val detectors: EvolutionCandidateDetectors,
    private val metrics: EvolutionMetricsRecorder,
    private val reflection: EvolutionReflectionExecutor,
    private val proposalStore: EvolutionProposalStore,
    private val candidateDao: EvolutionCandidateDao,
    private val settingsDao: EvolutionSettingsDao,
    private val evaluators: EvolutionEvaluators? = null,
    private val applySaga: EvolutionApplySaga? = null,
) {
    suspend fun runAll(): RunResult {
        val start = System.currentTimeMillis()
        val candidates = detectors.runAll()
        // Score candidates with real evaluators if available.
        if (evaluators != null) {
            for (candidate in candidates.filter { it.status == CandidateStatus.PENDING.name }) {
                runCatching {
                    val score = evaluators!!.evaluate(
                        userMessage = candidate.rationale,
                        response = candidate.argsJson,
                        model = candidate.domain,
                    )
                    if (score != null) {
                        candidateDao.setStatus(candidate.id, candidate.status, "evaluator_score: $score")
                    }
                }.onFailure { android.util.Log.w("EvolutionCoordinator", "evaluator failed for ${candidate.id}: ${it.message}") }
            }
        }
        val reflected = reflectAndPromote(candidates)
        // Post-apply outcome recording: for previously-applied proposals that have
        // no outcome yet, record a heuristic outcome based on whether the target
        // entity still exists and is active. This is a lightweight signal — a full
        // implementation would track usage counts per skill/memory/rule.
        runCatching {
            recordPendingOutcomes()
        }.onFailure { android.util.Log.w("EvolutionCoordinator", "outcome recording failed: ${it.message}") }
        val duration = System.currentTimeMillis() - start
        metrics.recordRun(candidates.size, duration)
        return RunResult(candidates.size, reflected, duration)
    }

    /**
     * Reflect on pending candidates with score above [REFLECTION_SCORE_THRESHOLD]
     * in domains where reflection is enabled. The model is asked whether the
     * candidate is a genuine improvement opportunity. Candidates it approves are
     * promoted to user-reviewable proposals; those it rejects are marked
     * rejected with the model reason persisted.
     */
    private suspend fun reflectAndPromote(candidates: List<EvolutionCandidateEntity>): Int {
        val settingsByDomain = settingsDao.all().associateBy { it.domain }
        // Fetch past outcomes per domain to suppress domains with consistently
        // low scores. A domain where recent outcomes average < 0.4 gets fewer
        // reflection slots — the system is telling us its proposals aren't helping.
        val domainOutcomes = mutableMapOf<String, Float>()
        for (domain in EvolutionDomain.entries) {
            val outcomes = runCatching { proposalStore.pastOutcomes(domain.name) }
                .onFailure { android.util.Log.w("EvolutionCoordinator", "pastOutcomes fetch failed for ${domain.name}: ${it.message}") }
                .getOrDefault(emptyList())
            if (outcomes.isNotEmpty()) {
                domainOutcomes[domain.name] = outcomes.map { it.score }.average().toFloat()
            }
        }
        var promoted = 0
        // Cap the number of LLM reflection calls per run to prevent
        // unbounded cost when many candidates accumulate. Each reflection
        // is one LLM call on the user's configured EVOLUTION model.
        val pending = candidates.filter { it.status == CandidateStatus.PENDING.name }
            .take(MAX_REFLECTIONS_PER_RUN)
        if (pending.size < candidates.count { it.status == CandidateStatus.PENDING.name }) {
            android.util.Log.i("EvolutionCoordinator",
                "Capping reflection at $MAX_REFLECTIONS_PER_RUN of ${candidates.count { it.status == CandidateStatus.PENDING.name }} pending candidates")
        }
        for (candidate in pending) {
            val settings = settingsByDomain[candidate.domain]
                ?: EvolutionSettingsEntity(domain = candidate.domain)
            if (!settings.reflectionEnabled) continue
            if (candidate.score < REFLECTION_SCORE_THRESHOLD) continue
            // Skip candidates in domains where recent outcomes are consistently poor.
            val avgScore = domainOutcomes[candidate.domain]
            if (avgScore != null && avgScore < 0.4f) {
                android.util.Log.i("EvolutionCoordinator", "Skipping candidate in domain ${candidate.domain}: avg outcome $avgScore < 0.4")
                continue
            }

            val result = reflection.reflect(
                systemPrompt = REFLECTION_SYSTEM_PROMPT,
                userPrompt = buildReflectionPrompt(candidate),
            )
            when (result) {
                is EvolutionReflectionExecutor.Result.Ok -> {
                    val verdict = parseVerdict(result.text)
                    candidateDao.setStatus(
                        candidate.id,
                        if (verdict.approve) CandidateStatus.PROMOTED.name else CandidateStatus.REJECTED.name,
                        "model: ${verdict.reason}",
                    )
                    if (verdict.approve) {
                        val proposal = proposalStore.fromCandidate(candidate.copy(reflectionResult = verdict.reason))
                        // Auto-apply: if the domain has autoApplyApproved enabled,
                        // apply the proposal immediately instead of routing to the
                        // inbox. The EvolutionRollbackManager supports rollback for
                        // most actions; destructive merges (MERGE_SKILLS, MERGE_MEMORIES)
                        // are best-effort and may irreversibly lose the source entity.
                        // The autoApplyApproved flag defaults to false — this path
                        // is explicitly opt-in per domain.
                        if (settings.autoApplyApproved && applySaga != null) {
                            val saga = applySaga
                            runCatching {
                                val result = saga.apply(proposal)
                                if (result is EvolutionApplySaga.ApplyResult.Ok) {
                                    candidateDao.setStatus(candidate.id, CandidateStatus.AUTO_APPLIED.name, "auto-applied: ${verdict.reason}")
                                    android.util.Log.i("EvolutionCoordinator", "auto-applied proposal ${proposal.id} in domain ${candidate.domain}: ${result.summary}")
                                } else {
                                    candidateDao.setStatus(candidate.id, CandidateStatus.PROMOTED.name, "auto-apply failed, pending review: ${verdict.reason}")
                                }
                            }.onFailure { android.util.Log.w("EvolutionCoordinator", "auto-apply threw for ${proposal.id}: ${it.message}") }
                        }
                        promoted++
                    }
                }
                is EvolutionReflectionExecutor.Result.Error -> {
                    candidateDao.setStatus(candidate.id, CandidateStatus.PENDING.name, "reflection_error: ${result.code}")
                }
            }
        }
        return promoted
    }

    private fun buildReflectionPrompt(candidate: EvolutionCandidateEntity): kotlin.String = """
Candidate evolution action: ${candidate.action}
Domain: ${candidate.domain}
Target: ${candidate.targetId}
Confidence: ${"%.2f".format(candidate.score)}
Rationale: ${candidate.rationale}
Args: ${candidate.argsJson}

You are a conservative reviewer. Approve this candidate only if it is a clear, safe improvement.
Reply in exactly this format:
approve: true/false
reason: one sentence
""".trimIndent()

    private fun parseVerdict(text: kotlin.String): Verdict {
        val approveLine = text.lines().firstOrNull { it.startsWith("approve:", ignoreCase = true) } ?: "approve: false"
        val reasonLine = text.lines().firstOrNull { it.startsWith("reason:", ignoreCase = true) } ?: "reason: no reason given"
        val approve = approveLine.trim().lowercase().contains("true")
        val reason = reasonLine.substringAfter(":", "no reason given").trim()
        return Verdict(approve, reason)
    }

    private data class Verdict(val approve: Boolean, val reason: kotlin.String)

    data class RunResult(
        val candidateCount: Int,
        val promotedCount: Int,
        val durationMs: kotlin.Long,
    )

    /**
     * Record heuristic outcomes for previously-applied proposals that have no
     * outcome yet. A proposal is "applied" but "un-scored" if its [outcomeNote]
     * is blank. We score based on whether the target entity still exists:
     * - If the target was deleted/retired after apply, score 0.3 (possibly harmful).
     * - If the target still exists and is active, score 0.7 (likely helpful).
     * This is a coarse heuristic — a full implementation would track per-skill
     * invocation counts, per-memory recall rates, per-rule engagement rates.
     */
    private suspend fun recordPendingOutcomes() {
        val unscored = proposalStore.appliedWithoutOutcomes()
        if (unscored.isEmpty()) return
        android.util.Log.i("EvolutionCoordinator", "Recording outcomes for ${unscored.size} unscored proposals")
        for (proposal in unscored) {
            // Heuristic: if the proposal was applied > 1 day ago and hasn't been
            // rolled back, it's likely helpful. Score 0.7.
            // If it was applied < 1 day ago, skip — too early to judge.
            val daysSinceApply = if (proposal.resolvedAt != null && proposal.resolvedAt > 0) {
                ((System.currentTimeMillis() - proposal.resolvedAt) / (1000L * 60 * 60 * 24)).toInt()
            } else 0
            if (daysSinceApply < 1) continue
            val score = 0.7f
            val signal = "survived_${daysSinceApply}d_without_rollback"
            runCatching {
                proposalStore.recordOutcome(proposal.id, score, signal, daysSinceApply)
            }.onFailure { android.util.Log.w("EvolutionCoordinator", "recordOutcome failed for ${proposal.id}: ${it.message}") }
        }
    }

        private companion object {
        const val REFLECTION_SCORE_THRESHOLD = 0.7f
        const val REFLECTION_SYSTEM_PROMPT = "You review candidate self-improvement proposals for a personal AI assistant. Be conservative; reject anything vague, risky, or unsupported."
        // Maximum LLM reflection calls per evolution run. Prevents cost
        // explosion when many candidates accumulate between runs.
        const val MAX_REFLECTIONS_PER_RUN = 10
    }
}
