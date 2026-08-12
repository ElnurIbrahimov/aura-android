package com.aura.evolution

import java.util.UUID
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Deterministic, offline detectors that produce evolution candidates from
 * recorded evidence. These run entirely on the device; no model call is made
 * here. The outputs are [EvolutionCandidateEntity] rows for the patch-authoring
 * engine to consider.
 *
 * Dedup (D5): candidates are keyed on (domain, action, targetId). Re-detection
 * of the same key refreshes the existing PENDING row in place, is skipped
 * entirely while a resolved row is inside the [COOLDOWN_MS] window, and resets
 * the same row back to PENDING once the cooldown has passed. No unique
 * constraint — the invariant is enforced here against the indexed lookup.
 */
@Singleton
class EvolutionCandidateDetectors @Inject constructor(
    private val evidenceDao: EvolutionEvidenceDao,
    private val candidateDao: EvolutionCandidateDao,
    private val memoryStore: com.aura.memory.MemoryStore? = null,
) {

    /**
     * Skill domain: detect skills that have been invoked often but also
     * failed often (≥ 3 failures in the last 14 days) — candidate for a patch.
     */
    suspend fun detectSkillPatchCandidates(): List<EvolutionCandidateEntity> {
        val cutoff = System.currentTimeMillis() - 14L * DAY_MS
        val failures = evidenceDao.byKind(EvolutionDomain.SKILL.name, "skill_failed", 500)
            .filter { it.createdAt >= cutoff }
            .groupBy { it.sourceEntityId }
            .filter { it.value.size >= 3 }
        return failures.map { (skillId, events) ->
            val distinctErrors = events.mapNotNull { parsePayload(it.payloadJson)["errorCode"] }.distinct()
            EvolutionCandidateEntity(
                id = UUID.randomUUID().toString(),
                domain = EvolutionDomain.SKILL.name,
                action = EvolutionAction.PATCH_SKILL.name,
                targetId = skillId,
                score = (events.size / 10f).coerceIn(0.1f, 0.95f),
                rationale = "Skill failed ${events.size} times (${distinctErrors.take(3).joinToString(", ")})",
            )
        }
    }

    /**
     * Skill domain: detect repeated invocation of the same skill by the user
     * without failures (≥ 5 invocations in 7 days) — candidate for promotion
     * to a hand/automation.
     */
    suspend fun detectSkillPromotionCandidates(): List<EvolutionCandidateEntity> {
        val cutoff = System.currentTimeMillis() - 7L * DAY_MS
        val invoked = evidenceDao.byKind(EvolutionDomain.SKILL.name, "skill_invoked", 500)
            .filter { it.createdAt >= cutoff }
            .groupBy { it.sourceEntityId }
            .filter { it.value.size >= 5 }
        return invoked.map { (skillId, events) ->
            EvolutionCandidateEntity(
                id = UUID.randomUUID().toString(),
                domain = EvolutionDomain.SKILL.name,
                action = EvolutionAction.PROMOTE_TO_HAND.name,
                targetId = skillId,
                score = (events.size / 20f).coerceIn(0.1f, 0.9f),
                rationale = "Skill invoked ${events.size} times in 7 days",
            )
        }
    }

    /**
     * Memory domain: detect groups of memories that say the same thing.
     *
     * This used to fire on recall count — ten or more recalls in thirty days
     * made a memory a candidate to be merged away, so the memory Aura reached
     * for most often was the one it most wanted to dissolve, and a memory the
     * user had downvoted repeatedly was a *stronger* candidate than one they
     * never saw. Recall count measures usefulness, not redundancy.
     *
     * Redundancy is a question about content, so it is answered by content:
     * near-duplicate clusters within a single scope and category. The score
     * reports how alike the members are, since that is what the model is being
     * asked to confirm.
     */
    suspend fun detectMemoryConsolidationCandidates(): List<EvolutionCandidateEntity> {
        val store = memoryStore ?: return emptyList()
        val clusters = runCatching { store.findNearDuplicateClusters() }
            .onFailure { android.util.Log.w(TAG, "near-duplicate scan failed: ${it.message}", it) }
            .getOrDefault(emptyList())
        return clusters.mapNotNull { cluster ->
            // A stable key across runs: the same cluster must refresh its
            // candidate row rather than accumulate a new one every scan.
            val anchor = cluster.memories.minByOrNull { it.id } ?: return@mapNotNull null
            val ids = cluster.memories.map { it.id }.sorted()
            val closeness = ((cluster.meanSimilarity - SIMILARITY_FLOOR) / SIMILARITY_SPAN)
                .coerceIn(0f, 1f)
            EvolutionCandidateEntity(
                id = UUID.randomUUID().toString(),
                domain = EvolutionDomain.MEMORY.name,
                action = EvolutionAction.CONSOLIDATE_MEMORIES.name,
                targetId = anchor.id,
                argsJson = json.encodeToString(
                    MemoryClusterArgs.serializer(),
                    MemoryClusterArgs(ids),
                ),
                score = (SCORE_BASE + SCORE_RANGE * closeness + SIZE_BONUS * (ids.size - 2))
                    .coerceIn(0.1f, 0.95f),
                rationale = "${ids.size} memories say nearly the same thing " +
                    "(average similarity ${"%.2f".format(cluster.meanSimilarity)})",
            )
        }
    }

    /**
     * Run all detectors and persist candidates with dedup on
     * (domain, action, targetId). Returns only the rows that are actionable
     * this run (new, refreshed, or cooldown-expired resets) — skipped
     * cooldown rows are not returned so the coordinator never re-authors them.
     */
    suspend fun runAll(): List<EvolutionCandidateEntity> {
        val detected = detectSkillPatchCandidates() +
            detectSkillPromotionCandidates() +
            detectMemoryConsolidationCandidates()
        val now = System.currentTimeMillis()
        val out = mutableListOf<EvolutionCandidateEntity>()
        for (candidate in detected) {
            val existing = candidateDao.findByKey(candidate.domain, candidate.action, candidate.targetId)
            when {
                existing == null -> {
                    candidateDao.upsert(candidate)
                    out += candidate
                }
                existing.status == CandidateStatus.PENDING.name -> {
                    // Refresh in place: same row, fresher score/rationale.
                    val refreshed = existing.copy(
                        score = candidate.score,
                        rationale = candidate.rationale,
                        argsJson = candidate.argsJson,
                        evidenceIdsJson = candidate.evidenceIdsJson,
                        updatedAt = now,
                    )
                    candidateDao.upsert(refreshed)
                    out += refreshed
                }
                now - existing.updatedAt < COOLDOWN_MS -> {
                    // Resolved recently (rejected/promoted/applied) — skip.
                }
                else -> {
                    // Cooldown expired: reset the SAME row back to pending.
                    val reset = existing.copy(
                        status = CandidateStatus.PENDING.name,
                        score = candidate.score,
                        rationale = candidate.rationale,
                        argsJson = candidate.argsJson,
                        evidenceIdsJson = candidate.evidenceIdsJson,
                        reflectionResult = "",
                        updatedAt = now,
                    )
                    candidateDao.upsert(reset)
                    out += reset
                }
            }
        }
        return out
    }

    private fun parsePayload(json: kotlin.String): Map<kotlin.String, kotlin.String> = runCatching {
        Json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), json)
    }.onFailure { android.util.Log.w("EvolutionCandidateDetect", "payload parse failed: ${it.message}", it) }
        .getOrDefault(emptyMap())

    private companion object {
        const val TAG = "EvolutionDetectors"
        val json = Json { ignoreUnknownKeys = true }
        const val DAY_MS = 24L * 60L * 60L * 1000L

        /**
         * Similarity-to-score mapping for consolidation candidates.
         *
         * Calibrated against the coordinator's 0.7 authoring bar so a cluster
         * has to be genuinely alike — around 0.92 average cosine — before an
         * LLM call is spent on it. Below that it stays a recorded candidate
         * the user can see, which is the honest place for a weak signal.
         */
        const val SIMILARITY_FLOOR = 0.85f
        const val SIMILARITY_SPAN = 0.12f
        const val SCORE_BASE = 0.35f
        const val SCORE_RANGE = 0.6f
        const val SIZE_BONUS = 0.03f

        /** D5: resolved candidates are not re-created for 14 days. */
        const val COOLDOWN_MS = 14L * DAY_MS
    }
}
