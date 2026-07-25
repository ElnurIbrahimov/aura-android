package com.aura.backup

import kotlinx.serialization.Serializable

/**
 * Backup types added in schema v13 — the last durable entities that were
 * persisted in Room but silently dropped on backup/restore.
 *
 * Before v13, 8 of the 48 Room entities had no backup class. A user who
 * exported, wiped, and restored lost the creative dependency graph,
 * continuity issues, "what if" simulations, the entire evolution evidence
 * trail, their responses to proactive suggestions, and model-routing
 * outcomes — with no error and no indication of what had gone.
 *
 * Seven of the eight are covered here.
 *
 * ## The deliberate omission
 *
 * [com.aura.creative.CreativeGenerationJobEntity] is **not** backed up.
 * It is in-flight execution state: a queued or running generation job
 * holds a `providerOperationId` for a call already in progress against a
 * specific provider account. Restoring `status = "running"` with
 * `progress = 40` onto a different install produces a job that will never
 * advance, because nothing is polling that operation any more — a
 * permanently stuck row that looks like real work. Terminal rows
 * (succeeded/failed) carry no information the resulting artifacts don't
 * already have. Jobs are correctly transient.
 *
 * As elsewhere in the backup format, embeddings and file bytes are omitted
 * — they are model-specific or live on disk and are rebuilt or re-linked
 * after restore.
 */

// ── Creative: artifact dependency graph ──

@Serializable
data class ArtifactDependencyBackup(
    val id: String,
    val sourceArtifactId: String,
    val targetArtifactId: String,
    val relation: String,
    val invalidationPolicy: String = "mark_review",
    val createdAt: Long = 0L,
)

internal fun com.aura.creative.ArtifactDependencyEntity.toBackup() = ArtifactDependencyBackup(
    id = id,
    sourceArtifactId = sourceArtifactId,
    targetArtifactId = targetArtifactId,
    relation = relation,
    invalidationPolicy = invalidationPolicy,
    createdAt = createdAt,
)

internal fun ArtifactDependencyBackup.toEntity() = com.aura.creative.ArtifactDependencyEntity(
    id = id,
    sourceArtifactId = sourceArtifactId,
    targetArtifactId = targetArtifactId,
    relation = relation,
    invalidationPolicy = invalidationPolicy,
    createdAt = createdAt,
)

// ── Creative: continuity issues ──

@Serializable
data class ContinuityIssueBackup(
    val id: String,
    val projectId: String,
    val branchId: String,
    val artifactId: String? = null,
    val category: String,
    val severity: String,
    val message: String,
    val evidenceFactIdsJson: String = "[]",
    val suggestedPatchJson: String = "{}",
    val status: String = "open",
    val createdAt: Long = 0L,
    val resolvedAt: Long? = null,
    val resolvedBy: String = "",
)

internal fun com.aura.creative.ContinuityIssueEntity.toBackup() = ContinuityIssueBackup(
    id = id,
    projectId = projectId,
    branchId = branchId,
    artifactId = artifactId,
    category = category,
    severity = severity,
    message = message,
    evidenceFactIdsJson = evidenceFactIdsJson,
    suggestedPatchJson = suggestedPatchJson,
    status = status,
    createdAt = createdAt,
    resolvedAt = resolvedAt,
    resolvedBy = resolvedBy,
)

internal fun ContinuityIssueBackup.toEntity() = com.aura.creative.ContinuityIssueEntity(
    id = id,
    projectId = projectId,
    branchId = branchId,
    artifactId = artifactId,
    category = category,
    severity = severity,
    message = message,
    evidenceFactIdsJson = evidenceFactIdsJson,
    suggestedPatchJson = suggestedPatchJson,
    status = status,
    createdAt = createdAt,
    resolvedAt = resolvedAt,
    resolvedBy = resolvedBy,
)

// ── Creative: simulations ──

@Serializable
data class CreativeSimulationBackup(
    val id: String,
    val projectId: String,
    val branchId: String,
    val premise: String,
    val assumptionsJson: String = "[]",
    val narrative: String = "",
    val stateDeltaJson: String = "[]",
    val causalGraphJson: String = "[]",
    val confidence: Float = 1.0f,
    val contradictionsJson: String = "[]",
    val createdAt: Long = 0L,
    val canonizedAt: Long = 0L,
    val canonizedFactIdsJson: String = "[]",
)

internal fun com.aura.creative.CreativeSimulationEntity.toBackup() = CreativeSimulationBackup(
    id = id,
    projectId = projectId,
    branchId = branchId,
    premise = premise,
    assumptionsJson = assumptionsJson,
    narrative = narrative,
    stateDeltaJson = stateDeltaJson,
    causalGraphJson = causalGraphJson,
    confidence = confidence,
    contradictionsJson = contradictionsJson,
    createdAt = createdAt,
    canonizedAt = canonizedAt,
    canonizedFactIdsJson = canonizedFactIdsJson,
)

internal fun CreativeSimulationBackup.toEntity() = com.aura.creative.CreativeSimulationEntity(
    id = id,
    projectId = projectId,
    branchId = branchId,
    premise = premise,
    assumptionsJson = assumptionsJson,
    narrative = narrative,
    stateDeltaJson = stateDeltaJson,
    causalGraphJson = causalGraphJson,
    confidence = confidence,
    contradictionsJson = contradictionsJson,
    createdAt = createdAt,
    canonizedAt = canonizedAt,
    canonizedFactIdsJson = canonizedFactIdsJson,
)

// ── Evolution: evidence trail ──

@Serializable
data class EvolutionEvidenceBackup(
    val id: String,
    val domain: String,
    val kind: String,
    val sourceEntityId: String,
    val runId: String? = null,
    val conversationId: String? = null,
    val turnTimestamp: Long? = null,
    val summary: String = "",
    val payloadJson: String = "{}",
    val beforeCiphertext: String? = null,
    val afterCiphertext: String? = null,
    val createdAt: Long = 0L,
)

internal fun com.aura.evolution.EvolutionEvidenceEntity.toBackup() = EvolutionEvidenceBackup(
    id = id,
    domain = domain,
    kind = kind,
    sourceEntityId = sourceEntityId,
    runId = runId,
    conversationId = conversationId,
    turnTimestamp = turnTimestamp,
    summary = summary,
    payloadJson = payloadJson,
    beforeCiphertext = beforeCiphertext,
    afterCiphertext = afterCiphertext,
    createdAt = createdAt,
)

internal fun EvolutionEvidenceBackup.toEntity() = com.aura.evolution.EvolutionEvidenceEntity(
    id = id,
    domain = domain,
    kind = kind,
    sourceEntityId = sourceEntityId,
    runId = runId,
    conversationId = conversationId,
    turnTimestamp = turnTimestamp,
    summary = summary,
    payloadJson = payloadJson,
    beforeCiphertext = beforeCiphertext,
    afterCiphertext = afterCiphertext,
    createdAt = createdAt,
)

// ── Evolution: candidates ──

@Serializable
data class EvolutionCandidateBackup(
    val id: String,
    val domain: String,
    val action: String,
    val targetId: String,
    val argsJson: String = "{}",
    val rationale: String = "",
    val score: Float = 0.0f,
    val evidenceIdsJson: String = "[]",
    val status: String,
    val reflectionResult: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

internal fun com.aura.evolution.EvolutionCandidateEntity.toBackup() = EvolutionCandidateBackup(
    id = id,
    domain = domain,
    action = action,
    targetId = targetId,
    argsJson = argsJson,
    rationale = rationale,
    score = score,
    evidenceIdsJson = evidenceIdsJson,
    status = status,
    reflectionResult = reflectionResult,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun EvolutionCandidateBackup.toEntity() = com.aura.evolution.EvolutionCandidateEntity(
    id = id,
    domain = domain,
    action = action,
    targetId = targetId,
    argsJson = argsJson,
    rationale = rationale,
    score = score,
    evidenceIdsJson = evidenceIdsJson,
    status = status,
    reflectionResult = reflectionResult,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

// ── Proactive: user responses to suggestions ──

@Serializable
data class ProactiveInteractionBackup(
    val id: Long,
    val eventId: Long,
    val action: String,
    val feedback: String = "",
    val timestamp: Long = 0L,
)

internal fun com.aura.proactive.ProactiveInteractionEntity.toBackup() = ProactiveInteractionBackup(
    id = id,
    eventId = eventId,
    action = action,
    feedback = feedback,
    timestamp = timestamp,
)

internal fun ProactiveInteractionBackup.toEntity() = com.aura.proactive.ProactiveInteractionEntity(
    id = id,
    eventId = eventId,
    action = action,
    feedback = feedback,
    timestamp = timestamp,
)

// ── Taste: model routing outcomes ──

@Serializable
data class RoutingOutcomeBackup(
    val id: String,
    val modelRole: String,
    val modelId: String,
    val success: Boolean,
    val latencyMs: Long = 0L,
    val costClass: String = "unknown",
    val outcomeType: String = "user_accepted",
    val createdAt: Long = 0L,
    val agentScope: String = "general",
)

internal fun com.aura.taste.RoutingOutcomeEntity.toBackup() = RoutingOutcomeBackup(
    id = id,
    modelRole = modelRole,
    modelId = modelId,
    success = success,
    latencyMs = latencyMs,
    costClass = costClass,
    outcomeType = outcomeType,
    createdAt = createdAt,
    agentScope = agentScope,
)

internal fun RoutingOutcomeBackup.toEntity() = com.aura.taste.RoutingOutcomeEntity(
    id = id,
    modelRole = modelRole,
    modelId = modelId,
    success = success,
    latencyMs = latencyMs,
    costClass = costClass,
    outcomeType = outcomeType,
    createdAt = createdAt,
    agentScope = agentScope,
)
