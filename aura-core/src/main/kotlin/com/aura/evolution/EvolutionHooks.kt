package com.aura.evolution

import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin facade that the agentic loop, skill store, and proactive workers call
 * to leave evolution evidence. This keeps evolution concerns decoupled from
 * the calling modules.
 *
 * **Gated on `evolutionEnabled`, which defaults to off.** Nothing here consulted
 * any preference before, so a feature nobody had switched on was writing a
 * five-index row for every memory stored *and every memory returned by every
 * recall* — `MemoryStore` calls [onMemoryRecalled] once per result, from both
 * recall branches. At five results over fifty turns a day that is tens of
 * thousands of indexed rows a year, written on the user's critical path, into a
 * table whose only readers are detectors that never run.
 *
 * The flag is cached rather than read per call: [onMemoryRecalled] fires inside
 * a loop, and a DataStore read per result would cost more than the row it
 * prevents. [GATE_TTL_MS] bounds how long a toggle takes to take effect, which
 * for background telemetry is the right trade — evidence is not a setting the
 * user watches for a response.
 */
@Singleton
class EvolutionHooks @Inject constructor(
    private val recorder: EvolutionEvidenceRecorder,
    private val userPreferences: com.aura.data.UserPreferences? = null,
) {
    @Volatile
    private var gateCachedAt: Long = 0L

    @Volatile
    private var gateOpen: Boolean = false

    /**
     * Whether evidence should be recorded at all.
     *
     * Fails **closed** when the preference cannot be read, and open only when
     * no [userPreferences] was supplied — the shape every manual construction
     * in tests uses, which must keep recording so their assertions still mean
     * something.
     */
    private suspend fun enabled(): Boolean {
        val prefs = userPreferences ?: return true
        val now = System.currentTimeMillis()
        if (now - gateCachedAt < GATE_TTL_MS) return gateOpen
        val value = runCatching { prefs.evolutionEnabled.first() }
            .onFailure { android.util.Log.w("EvolutionHooks", "evolution gate read failed", it) }
            .getOrDefault(false)
        gateOpen = value
        gateCachedAt = now
        return value
    }

    /**
     * The one place evidence reaches the recorder, so the gate cannot be
     * forgotten by a hook added later. Mirrors
     * [EvolutionEvidenceRecorder.record] exactly.
     */
    private suspend fun record(
        domain: EvolutionDomain,
        kind: kotlin.String,
        sourceEntityId: kotlin.String,
        runId: kotlin.String? = null,
        conversationId: kotlin.String? = null,
        turnTimestamp: kotlin.Long? = null,
        summary: kotlin.String = "",
        payload: Map<kotlin.String, kotlin.String> = emptyMap(),
        beforeCiphertext: kotlin.String? = null,
        afterCiphertext: kotlin.String? = null,
    ) {
        if (!enabled()) return
        recorder.record(
            domain = domain,
            kind = kind,
            sourceEntityId = sourceEntityId,
            runId = runId,
            conversationId = conversationId,
            turnTimestamp = turnTimestamp,
            summary = summary,
            payload = payload,
            beforeCiphertext = beforeCiphertext,
            afterCiphertext = afterCiphertext,
        )
    }

    // --- Skill signals ---

    suspend fun onSkillInvoked(
        skillId: kotlin.String,
        runId: kotlin.String? = null,
        conversationId: kotlin.String? = null,
        turnTimestamp: kotlin.Long? = null,
    ) = record(
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
    ) = record(
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
    ) = record(
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
    ) = record(
        domain = EvolutionDomain.SKILL,
        kind = "skill_edited",
        sourceEntityId = skillId,
        summary = "user edited skill",
        beforeCiphertext = beforeCiphertext,
        afterCiphertext = afterCiphertext,
    )

    suspend fun onSkillAdded(skillId: kotlin.String) = record(
        domain = EvolutionDomain.SKILL,
        kind = "skill_added",
        sourceEntityId = skillId,
    )

    suspend fun onSkillRemoved(skillId: kotlin.String) = record(
        domain = EvolutionDomain.SKILL,
        kind = "skill_removed",
        sourceEntityId = skillId,
    )

    suspend fun onProactiveSnoozed(
        eventId: kotlin.String,
        runId: kotlin.String? = null,
        conversationId: kotlin.String? = null,
        turnTimestamp: kotlin.Long? = null,
    ) = record(
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
    ) = record(
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
    ) = record(
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
    ) = record(
        domain = EvolutionDomain.MEMORY,
        kind = if (helpful) "memory_helpful" else "memory_not_helpful",
        sourceEntityId = memoryId,
        summary = note ?: "user rated memory ${if (helpful) "helpful" else "unhelpful"}",
        payload = mapOf("helpful" to helpful.toString()).let { base ->
            if (note == null) base else base + ("note" to note)
        },
    )

    suspend fun onMemoryForgotten(memoryId: kotlin.String) = record(
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
    ) = record(
        domain = EvolutionDomain.PROACTIVE,
        kind = "proactive_delivered",
        sourceEntityId = eventId,
        runId = runId,
        payload = mapOf("eventType" to eventType),
    )

    suspend fun onProactiveOpened(
        eventId: kotlin.String,
        eventType: kotlin.String,
    ) = record(
        domain = EvolutionDomain.PROACTIVE,
        kind = "proactive_opened",
        sourceEntityId = eventId,
        payload = mapOf("eventType" to eventType),
    )

    suspend fun onProactiveActionTaken(
        eventId: kotlin.String,
        action: kotlin.String,
    ) = record(
        domain = EvolutionDomain.PROACTIVE,
        kind = "proactive_action_taken",
        sourceEntityId = eventId,
        payload = mapOf("action" to action),
    )

    suspend fun onProactiveDismissed(
        eventId: kotlin.String,
        dismissalKind: kotlin.String,
    ) = record(
        domain = EvolutionDomain.PROACTIVE,
        kind = "proactive_dismissed",
        sourceEntityId = eventId,
        payload = mapOf("kind" to dismissalKind),
    )

    companion object {
        /**
         * How long a cached `evolutionEnabled` read stays good.
         *
         * A minute, because the cost of being wrong is one minute of evidence
         * either recorded or skipped, and the cost of not caching is a DataStore
         * read inside the recall loop.
         */
        const val GATE_TTL_MS: Long = 60_000L
    }
}
