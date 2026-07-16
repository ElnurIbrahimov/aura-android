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
 * here. The outputs are [EvolutionCandidateEntity] rows for the reflection
 * engine to consider.
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
     * candidate to consolidate into a belief or merge duplicates.
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
     * Proactive domain: detect proactive events that were delivered but
     * repeatedly dismissed without action (≥ 3 dismissals in 14 days) —
     * candidate to disable or rewrite the rule/message.
     */
    suspend fun detectProactiveDismissalCandidates(): List<EvolutionCandidateEntity> {
        val cutoff = System.currentTimeMillis() - 14L * DAY_MS
        val dismissals = evidenceDao.byKind(EvolutionDomain.PROACTIVE.name, "proactive_dismissed", 500)
            .filter { it.createdAt >= cutoff }
            .groupBy { it.sourceEntityId }
            .filter { it.value.size >= 3 }
        return dismissals.map { (eventId, events) ->
            EvolutionCandidateEntity(
                id = UUID.randomUUID().toString(),
                domain = EvolutionDomain.PROACTIVE.name,
                action = EvolutionAction.REWRITE_RULE_MESSAGE.name,
                targetId = eventId,
                score = (events.size / 10f).coerceIn(0.1f, 0.9f),
                rationale = "Proactive event dismissed ${events.size} times in 14 days",
            )
        }
    }

    /**
     * Proactive domain: detect proactive events with high engagement
     * (≥ 3 actions in 7 days) — candidate to double down / add similar rule.
     */
    suspend fun detectProactiveEngagementCandidates(): List<EvolutionCandidateEntity> {
        val cutoff = System.currentTimeMillis() - 7L * DAY_MS
        val actions = evidenceDao.byKind(EvolutionDomain.PROACTIVE.name, "proactive_action_taken", 500)
            .filter { it.createdAt >= cutoff }
            .groupBy { it.sourceEntityId }
            .filter { it.value.size >= 3 }
        return actions.map { (eventId, events) ->
            EvolutionCandidateEntity(
                id = UUID.randomUUID().toString(),
                domain = EvolutionDomain.PROACTIVE.name,
                action = EvolutionAction.NEW_PROACTIVE_RULE.name,
                targetId = eventId,
                score = (events.size / 10f).coerceIn(0.1f, 0.9f),
                rationale = "Proactive event drove ${events.size} actions in 7 days",
            )
        }
    }

    /**
     * Run all detectors, persist candidates, and return the new/updated ones.
     */
    suspend fun runAll(): List<EvolutionCandidateEntity> {
        val all = detectSkillPatchCandidates() +
            detectSkillPromotionCandidates() +
            detectMemoryConsolidationCandidates() +
            detectProactiveDismissalCandidates() +
            detectProactiveEngagementCandidates()
        for (candidate in all) {
            candidateDao.upsert(candidate)
        }
        return all
    }

    private fun parsePayload(json: kotlin.String): Map<kotlin.String, kotlin.String> = runCatching {
        Json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), json)
    }.getOrDefault(emptyMap())

    private companion object {
        const val DAY_MS = 24L * 60L * 60L * 1000L
    }
}
