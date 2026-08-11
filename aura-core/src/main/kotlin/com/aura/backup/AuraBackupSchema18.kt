package com.aura.backup

import kotlinx.serialization.Serializable

/**
 * Backup types added in schema v18 — per-tool policy and the five
 * consciousness stores.
 *
 * These are the settings and the state that a user would describe as "how it
 * knows me", and none of them survived an export. Tool policy is the whole
 * user-facing tool-permission surface: disable a tool, require biometric
 * confirmation on another, and a restore handed all of it back at its
 * defaults, silently re-enabling things the user had turned off. The five
 * consciousness stores are worse, because their loss is invisible rather than
 * merely unnoticed: [com.aura.consciousness.TheoryOfMind] says nothing at all
 * until `commStyle.sampleCount` reaches three, so a restore that dropped the
 * counter produced an install that had every memory and had forgotten how the
 * user talks, with no way to tell that anything was missing.
 *
 * All five land together. They were deferred as a group once already — backing
 * up one of them would have set the precedent that the other four were
 * optional, which is how the affinity score and the drive timestamps ended up
 * being the only durable state with no backup path at all.
 *
 * ## What is deliberately not here
 *
 * The affinity score is exported **raw**, before decay. Exporting the decayed
 * value and restoring it lets `AffinityTracker.applyDecay` charge the same
 * elapsed days twice, so every roundtrip would quietly cost the user standing
 * they had earned.
 *
 * Enum-valued fields cross the wire as their `name` and are mapped back by a
 * lookup that tolerates an unknown value, so a backup written by a build that
 * added a fifth `DriveType` or a new `ConfirmationLevel` restores the rest of
 * its content instead of failing the whole import on one string.
 */

// ── Tool policy ──

@Serializable
data class ToolPolicyBackup(
    val toolName: String,
    val enabled: Boolean = true,
    /** [com.aura.agent.policy.ConfirmationLevel] by name. */
    val confirmation: String = "NONE",
    val costCeiling: Double = 0.0,
    val allowedScopes: List<String> = emptyList(),
    val requireApprovalPerRun: Boolean = false,
    val approvalExpiryMs: Long = 0L,
)

internal fun com.aura.agent.policy.ToolPolicy.toBackup() = ToolPolicyBackup(
    toolName = toolName,
    enabled = enabled,
    confirmation = confirmation.name,
    costCeiling = costCeiling,
    allowedScopes = allowedScopes,
    requireApprovalPerRun = requireApprovalPerRun,
    approvalExpiryMs = approvalExpiryMs,
)

internal fun ToolPolicyBackup.toPolicy() = com.aura.agent.policy.ToolPolicy(
    toolName = toolName,
    enabled = enabled,
    confirmation = com.aura.agent.policy.ConfirmationLevel.entries
        .firstOrNull { it.name == confirmation }
        ?: com.aura.agent.policy.ConfirmationLevel.NONE,
    costCeiling = costCeiling,
    allowedScopes = allowedScopes,
    requireApprovalPerRun = requireApprovalPerRun,
    approvalExpiryMs = approvalExpiryMs,
)

// ── Consciousness envelope ──

/**
 * The five consciousness stores as one field on [AuraBackup].
 *
 * Nullable members throughout: a build that has not wired one of the five, or
 * a backup written before it existed, restores the other four rather than
 * failing.
 */
@Serializable
data class ConsciousnessBackup(
    val narrative: NarrativeBackup? = null,
    val drives: List<DriveBackup> = emptyList(),
    val userModel: UserModelBackup? = null,
    val emotion: EmotionBackup? = null,
    val affinity: AffinityBackup? = null,
)

// ── NarrativeSelf ──

@Serializable
data class NarrativeBackup(
    val coreIdentity: String = "",
    val recentGrowth: String = "",
    val activeConcerns: List<String> = emptyList(),
    val unresolvedQuestions: List<String> = emptyList(),
    val relationshipState: String = "",
    val identityAnchors: List<String> = emptyList(),
    val lastUpdated: Long = 0L,
    val version: Int = 1,
)

internal fun com.aura.consciousness.NarrativeState.toBackup() = NarrativeBackup(
    coreIdentity = coreIdentity,
    recentGrowth = recentGrowth,
    activeConcerns = activeConcerns,
    unresolvedQuestions = unresolvedQuestions,
    relationshipState = relationshipState,
    identityAnchors = identityAnchors,
    lastUpdated = lastUpdated,
    version = version,
)

internal fun NarrativeBackup.toState() = com.aura.consciousness.NarrativeState(
    coreIdentity = coreIdentity,
    recentGrowth = recentGrowth,
    activeConcerns = activeConcerns,
    unresolvedQuestions = unresolvedQuestions,
    relationshipState = relationshipState,
    identityAnchors = identityAnchors,
    lastUpdated = lastUpdated,
    version = version,
)

// ── IntrinsicMotivation ──

@Serializable
data class DriveBackup(
    /** [com.aura.consciousness.IntrinsicMotivation.DriveType] by name. */
    val drive: String,
    val intensity: Float = 0.3f,
    val satisfaction: Float = 0.7f,
    val lastSatisfiedAt: Long = 0L,
    val triggers: List<String> = emptyList(),
)

internal fun com.aura.consciousness.IntrinsicMotivation.DriveState.toBackup() = DriveBackup(
    drive = drive.name,
    intensity = intensity,
    satisfaction = satisfaction,
    lastSatisfiedAt = lastSatisfiedAt,
    triggers = triggers,
)

/**
 * Null when [drive] names a [com.aura.consciousness.IntrinsicMotivation.DriveType]
 * this build does not have. Dropping the row is right: `IntrinsicMotivation.restore`
 * merges over the defaults, so an unknown drive has nowhere to go and a known
 * one that is missing keeps its default.
 */
internal fun DriveBackup.toStateOrNull(): com.aura.consciousness.IntrinsicMotivation.DriveState? {
    val type = com.aura.consciousness.IntrinsicMotivation.DriveType.entries
        .firstOrNull { it.name == drive } ?: return null
    return com.aura.consciousness.IntrinsicMotivation.DriveState(
        drive = type,
        intensity = intensity,
        satisfaction = satisfaction,
        lastSatisfiedAt = lastSatisfiedAt,
        triggers = triggers,
    )
}

// ── TheoryOfMind ──

@Serializable
data class TopicKnowledgeBackup(
    val topic: String = "",
    val level: Float = 0.5f,
    val confidence: Float = 0.3f,
    val interactions: Int = 0,
    val lastSeen: Long = 0L,
    val signals: List<String> = emptyList(),
)

@Serializable
data class EmotionalStateBackup(
    val valence: Float = 0f,
    val arousal: Float = 0f,
    val engagement: Float = 0.5f,
    val frustration: Float = 0f,
    val confidence: Float = 0.3f,
)

@Serializable
data class CommStyleBackup(
    val verbosity: Float = 0.5f,
    val formality: Float = 0.5f,
    val technicalDepth: Float = 0.5f,
    val avgMessageLength: Float = 0f,
    /**
     * The field the whole class turns on: `TheoryOfMind.toPrompt` returns an
     * empty string below three, so losing this counter on restore makes every
     * other field here inert.
     */
    val sampleCount: Int = 0,
)

@Serializable
data class UserModelBackup(
    val topics: Map<String, TopicKnowledgeBackup> = emptyMap(),
    val emotionalState: EmotionalStateBackup = EmotionalStateBackup(),
    val commStyle: CommStyleBackup = CommStyleBackup(),
    val lastInteractionAt: Long = 0L,
)

internal fun com.aura.consciousness.TheoryOfMind.UserModel.toBackup() = UserModelBackup(
    topics = topics.mapValues { (_, t) ->
        TopicKnowledgeBackup(
            topic = t.topic,
            level = t.level,
            confidence = t.confidence,
            interactions = t.interactions,
            lastSeen = t.lastSeen,
            signals = t.signals,
        )
    },
    emotionalState = EmotionalStateBackup(
        valence = emotionalState.valence,
        arousal = emotionalState.arousal,
        engagement = emotionalState.engagement,
        frustration = emotionalState.frustration,
        confidence = emotionalState.confidence,
    ),
    commStyle = CommStyleBackup(
        verbosity = commStyle.verbosity,
        formality = commStyle.formality,
        technicalDepth = commStyle.technicalDepth,
        avgMessageLength = commStyle.avgMessageLength,
        sampleCount = commStyle.sampleCount,
    ),
    lastInteractionAt = lastInteractionAt,
)

internal fun UserModelBackup.toModel() = com.aura.consciousness.TheoryOfMind.UserModel(
    topics = topics.mapValues { (_, t) ->
        com.aura.consciousness.TheoryOfMind.TopicKnowledge(
            topic = t.topic,
            level = t.level,
            confidence = t.confidence,
            interactions = t.interactions,
            lastSeen = t.lastSeen,
            signals = t.signals,
        )
    },
    emotionalState = com.aura.consciousness.TheoryOfMind.EmotionalState(
        valence = emotionalState.valence,
        arousal = emotionalState.arousal,
        engagement = emotionalState.engagement,
        frustration = emotionalState.frustration,
        confidence = emotionalState.confidence,
    ),
    commStyle = com.aura.consciousness.TheoryOfMind.CommStyle(
        verbosity = commStyle.verbosity,
        formality = commStyle.formality,
        technicalDepth = commStyle.technicalDepth,
        avgMessageLength = commStyle.avgMessageLength,
        sampleCount = commStyle.sampleCount,
    ),
    lastInteractionAt = lastInteractionAt,
)

// ── EmotionEngine ──

@Serializable
data class EmotionBackup(
    val tension: Float = 0.3f,
    val connection: Float = 0.5f,
    val energy: Float = 0.4f,
    val focus: Float = 0.3f,
    val updatedAt: Long = 0L,
)

internal fun com.aura.emotion.EmotionEngine.EmotionSnapshot.toBackup() = EmotionBackup(
    tension = tension,
    connection = connection,
    energy = energy,
    focus = focus,
    updatedAt = updatedAt,
)

internal fun EmotionBackup.toSnapshot() = com.aura.emotion.EmotionEngine.EmotionSnapshot(
    tension = tension,
    connection = connection,
    energy = energy,
    focus = focus,
    updatedAt = updatedAt,
)

// ── AffinityTracker ──

/**
 * The affinity score exactly as stored, plus the timestamp decay is measured
 * from. Both are needed and neither is derivable from the other — see
 * `AffinityTracker.exportRaw` for why the decayed value must not be what
 * travels.
 */
@Serializable
data class AffinityBackup(
    val score: Float = 0f,
    val lastInteractionAt: Long = 0L,
)
