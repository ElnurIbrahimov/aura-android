package com.aura.evolution

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin facade that the agentic loop, skill store, and proactive workers call
 * to leave evolution evidence. This keeps evolution concerns decoupled from
 * the calling modules.
 */
@Singleton
class EvolutionHooks @Inject constructor(
    private val recorder: EvolutionEvidenceRecorder,
) {
    // --- Skill signals ---

    suspend fun onSkillInvoked(
        skillId: kotlin.String,
        runId: kotlin.String? = null,
        conversationId: kotlin.String? = null,
        turnTimestamp: kotlin.Long? = null,
    ) = recorder.record(
        domain = EvolutionDomain.SKILL,
        kind = "skill_invoked",
        sourceEntityId = skillId,
        runId = runId,
        conversationId = conversationId,
        turnTimestamp = turnTimestamp,
    )

    suspend fun onSkillFailed(
        skillId: kotlin.String,
        errorCode: kotlin.String,
        runId: kotlin.String? = null,
        conversationId: kotlin.String? = null,
        turnTimestamp: kotlin.Long? = null,
    ) = recorder.record(
        domain = EvolutionDomain.SKILL,
        kind = "skill_failed",
        sourceEntityId = skillId,
        runId = runId,
        conversationId = conversationId,
        turnTimestamp = turnTimestamp,
        summary = "skill failed: $errorCode",
        payload = mapOf("errorCode" to errorCode),
    )

    suspend fun onSkillEdited(
        skillId: kotlin.String,
        beforeCiphertext: kotlin.String? = null,
        afterCiphertext: kotlin.String? = null,
    ) = recorder.record(
        domain = EvolutionDomain.SKILL,
        kind = "skill_edited",
        sourceEntityId = skillId,
        summary = "user edited skill",
        beforeCiphertext = beforeCiphertext,
        afterCiphertext = afterCiphertext,
    )

    // --- Memory signals ---

    suspend fun onMemoryStored(
        memoryId: kotlin.String,
        category: kotlin.String,
        runId: kotlin.String? = null,
        conversationId: kotlin.String? = null,
        turnTimestamp: kotlin.Long? = null,
    ) = recorder.record(
        domain = EvolutionDomain.MEMORY,
        kind = "memory_stored",
        sourceEntityId = memoryId,
        runId = runId,
        conversationId = conversationId,
        turnTimestamp = turnTimestamp,
        payload = mapOf("category" to category),
    )

    suspend fun onMemoryRecalled(
        memoryId: kotlin.String,
        query: kotlin.String,
        rank: Int,
        runId: kotlin.String? = null,
        conversationId: kotlin.String? = null,
        turnTimestamp: kotlin.Long? = null,
    ) = recorder.record(
        domain = EvolutionDomain.MEMORY,
        kind = "memory_recalled",
        sourceEntityId = memoryId,
        runId = runId,
        conversationId = conversationId,
        turnTimestamp = turnTimestamp,
        summary = "recalled at rank $rank",
        payload = mapOf("rank" to rank.toString()),
    )

    suspend fun onMemoryForgotten(memoryId: kotlin.String) = recorder.record(
        domain = EvolutionDomain.MEMORY,
        kind = "memory_forgotten",
        sourceEntityId = memoryId,
        summary = "user deleted memory",
    )

    // --- Proactive signals ---

    suspend fun onProactiveDelivered(
        eventId: kotlin.String,
        eventType: kotlin.String,
        runId: kotlin.String? = null,
    ) = recorder.record(
        domain = EvolutionDomain.PROACTIVE,
        kind = "proactive_delivered",
        sourceEntityId = eventId,
        runId = runId,
        payload = mapOf("eventType" to eventType),
    )

    suspend fun onProactiveOpened(
        eventId: kotlin.String,
        eventType: kotlin.String,
    ) = recorder.record(
        domain = EvolutionDomain.PROACTIVE,
        kind = "proactive_opened",
        sourceEntityId = eventId,
        payload = mapOf("eventType" to eventType),
    )

    suspend fun onProactiveActionTaken(
        eventId: kotlin.String,
        action: kotlin.String,
    ) = recorder.record(
        domain = EvolutionDomain.PROACTIVE,
        kind = "proactive_action_taken",
        sourceEntityId = eventId,
        payload = mapOf("action" to action),
    )

    suspend fun onProactiveDismissed(
        eventId: kotlin.String,
        dismissalKind: kotlin.String,
    ) = recorder.record(
        domain = EvolutionDomain.PROACTIVE,
        kind = "proactive_dismissed",
        sourceEntityId = eventId,
        payload = mapOf("kind" to dismissalKind),
    )
}
