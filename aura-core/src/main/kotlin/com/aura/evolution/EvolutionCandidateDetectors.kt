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
     * Memory domain: detect memories with many recalls (≥ 10 in 30 days) —
     * candidate to consolidate with related memories.
     */
    suspend fun detectMemoryConsolidationCandidates(): List<EvolutionCandidateEntity> {
        val cutoff = System.currentTimeMillis() - 30L * DAY_MS
        val recalls = evidenceDao.byKind(EvolutionDomain.MEMORY.name, "memory_recalled", 1000)
            .filter { it.createdAt >= cutoff }
            .groupBy { it.sourceEntityId }
            .filter { it.value.size >= 10 }
        return recalls.map { (memoryId, events) ->
            EvolutionCandidateEntity(
                id = UUID.randomUUID().toString(),
                domain = EvolutionDomain.MEMORY.name,
                action = EvolutionAction.CONSOLIDATE_MEMORIES.name,
                targetId = memoryId,
                score = (events.size / 30f).coerceIn(0.1f, 0.9f),
                rationale = "Memory recalled ${events.size} times in 30 days",
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
        const val DAY_MS = 24L * 60L * 60L * 1000L

        /** D5: resolved candidates are not re-created for 14 days. */
        const val COOLDOWN_MS = 14L * DAY_MS
    }
}
