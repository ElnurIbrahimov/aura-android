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

    /**
     * The agent asked for a skill by a name that does not exist.
     *
     * Recorded separately from [onSkillFailed] because it is not a skill
     * failing — no skill ran. It used to be logged as `skill_failed` against
     * the literal id `"_unknown_"`, which was the only skill failure Aura ever
     * recorded, so the one signal feeding the PATCH_SKILL detector accumulated
     * under an id that resolves to nothing. Every candidate it could raise
     * named a skill that could not be fetched, patched, or even displayed.
     *
     * Its own kind, so the evidence stays (a repeatedly-requested name that
     * doesn't exist is worth knowing about) without pretending to be about a
     * skill that does.
     */
    suspend fun onSkillLookupMissed(
        requestedName: kotlin.String,
        runId: kotlin.String? = null,
        conversationId: kotlin.String? = null,
        turnTimestamp: kotlin.Long? = null,
    ) = recorder.record(
        domain = EvolutionDomain.SKILL,
        kind = "skill_lookup_missed",
        sourceEntityId = requestedName,
        runId = runId,
        conversationId = conversationId,
        turnTimestamp = turnTimestamp,
        summary = "no skill named '$requestedName'",
        payload = mapOf("requestedName" to requestedName),
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

    suspend fun onSkillAdded(skillId: kotlin.String) = recorder.record(
        domain = EvolutionDomain.SKILL,
        kind = "skill_added",
        sourceEntityId = skillId,
    )

    suspend fun onSkillRemoved(skillId: kotlin.String) = recorder.record(
        domain = EvolutionDomain.SKILL,
        kind = "skill_removed",
        sourceEntityId = skillId,
    )

    suspend fun onProactiveSnoozed(
        eventId: kotlin.String,
        runId: kotlin.String? = null,
        conversationId: kotlin.String? = null,
        turnTimestamp: kotlin.Long? = null,
    ) = recorder.record(
        domain = EvolutionDomain.PROACTIVE,
        kind = "proactive_snoozed",
        sourceEntityId = eventId,
        runId = runId,
        conversationId = conversationId,
        turnTimestamp = turnTimestamp,
        summary = "user snoozed proactive event",
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

    suspend fun onMemoryFeedback(
        memoryId: kotlin.String,
        helpful: Boolean,
        note: kotlin.String? = null,
    ) = recorder.record(
        domain = EvolutionDomain.MEMORY,
        kind = if (helpful) "memory_helpful" else "memory_not_helpful",
        sourceEntityId = memoryId,
        summary = note ?: "user rated memory ${if (helpful) "helpful" else "unhelpful"}",
        payload = mapOf("helpful" to helpful.toString()).let { base ->
            if (note == null) base else base + ("note" to note)
        },
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
