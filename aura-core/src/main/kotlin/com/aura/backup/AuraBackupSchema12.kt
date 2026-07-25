package com.aura.backup

import kotlinx.serialization.Serializable

/**
 * Backup types added in schema v12. These cover durable user state that
 * was previously persisted in Room but silently dropped on backup/restore.
 *
 * Embeddings and file bytes are intentionally omitted — they are
 * model-specific or stored on disk and are rebuilt/re-linked after
 * restore.
 */

// ── Memory feedback (audit trail for memory ratings) ──

@Serializable
data class MemoryFeedbackBackup(
    val id: String,
    val memoryId: String,
    val kind: String,
    val note: String,
    val createdAt: Long,
)

internal fun com.aura.memory.MemoryFeedbackEntity.toBackup() = MemoryFeedbackBackup(
    id = id,
    memoryId = memoryId,
    kind = kind,
    note = note,
    createdAt = createdAt,
)

internal fun MemoryFeedbackBackup.toEntity() = com.aura.memory.MemoryFeedbackEntity(
    id = id,
    memoryId = memoryId,
    kind = kind,
    note = note,
    createdAt = createdAt,
)

// ── Document chunks (rebuilt after restore, but text + metadata is user data) ──

@Serializable
data class DocumentChunkBackup(
    val id: String,
    val documentId: String,
    val ordinal: Int,
    val charStart: Int,
    val charEnd: Int,
    val pageNumber: Int,
    val text: String,
    val contentHash: String,
    val embeddingModel: String? = null,
    val embeddingVersion: Int = 0,
    val embeddedAt: Long = 0L,
)

internal fun com.aura.documents.DocumentChunkEntity.toBackup() = DocumentChunkBackup(
    id = id,
    documentId = documentId,
    ordinal = ordinal,
    charStart = charStart,
    charEnd = charEnd,
    pageNumber = pageNumber,
    text = text,
    contentHash = contentHash,
    embeddingModel = embeddingModel,
    embeddingVersion = embeddingVersion,
    embeddedAt = embeddedAt,
)

internal fun DocumentChunkBackup.toEntity() = com.aura.documents.DocumentChunkEntity(
    id = id,
    documentId = documentId,
    ordinal = ordinal,
    charStart = charStart,
    charEnd = charEnd,
    pageNumber = pageNumber,
    text = text,
    contentHash = contentHash,
    embedding = null,
    embeddingModel = embeddingModel,
    embeddingVersion = embeddingVersion,
    embeddedAt = embeddedAt,
)

// ── Reference identities (creative project characters/locations/etc.) ──

@Serializable
data class ReferenceIdentityBackup(
    val id: String,
    val projectId: String,
    val identityType: String,
    val name: String,
    val attributesJson: String,
    val referenceArtifactIdsJson: String,
    val locked: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val agentScope: String = "general",
)

internal fun com.aura.taste.ReferenceIdentityEntity.toBackup() = ReferenceIdentityBackup(
    id = id,
    projectId = projectId,
    identityType = identityType,
    name = name,
    attributesJson = attributesJson,
    referenceArtifactIdsJson = referenceArtifactIdsJson,
    locked = locked,
    createdAt = createdAt,
    updatedAt = updatedAt,
    agentScope = agentScope,
)

internal fun ReferenceIdentityBackup.toEntity() = com.aura.taste.ReferenceIdentityEntity(
    id = id,
    projectId = projectId,
    identityType = identityType,
    name = name,
    attributesJson = attributesJson,
    referenceArtifactIdsJson = referenceArtifactIdsJson,
    locked = locked,
    createdAt = createdAt,
    updatedAt = updatedAt,
    agentScope = agentScope,
)

// ── Agent runs (durable user-visible executions) ──

@Serializable
data class AgentRunBackup(
    val id: String,
    val goalId: String,
    val status: String,
    val triggerType: String,
    val triggerPayload: String,
    val modelId: String,
    val specialistName: String? = null,
    val conversationId: String,
    val parentRunId: String? = null,
    val startedAt: Long,
    val updatedAt: Long,
    val finishedAt: Long? = null,
    val errorMessage: String,
    val metadata: String,
)

internal fun com.aura.agentrun.AgentRunEntity.toBackup() = AgentRunBackup(
    id = id,
    goalId = goalId,
    status = status,
    triggerType = triggerType,
    triggerPayload = triggerPayload,
    modelId = modelId,
    specialistName = specialistName,
    conversationId = conversationId,
    parentRunId = parentRunId,
    startedAt = startedAt,
    updatedAt = updatedAt,
    finishedAt = finishedAt,
    errorMessage = errorMessage,
    metadata = metadata,
)

internal fun AgentRunBackup.toEntity() = com.aura.agentrun.AgentRunEntity(
    id = id,
    goalId = goalId,
    status = status,
    triggerType = triggerType,
    triggerPayload = triggerPayload,
    modelId = modelId,
    specialistName = specialistName,
    conversationId = conversationId,
    parentRunId = parentRunId,
    startedAt = startedAt,
    updatedAt = updatedAt,
    finishedAt = finishedAt,
    errorMessage = errorMessage,
    metadata = metadata,
)

@Serializable
data class GoalBackup(
    val id: String,
    val agentRunId: String,
    val description: String,
    val doneCriteria: String,
    val successEvaluation: String,
    val isAchieved: Boolean,
    val achievedAt: Long? = null,
)

internal fun com.aura.agentrun.GoalEntity.toBackup() = GoalBackup(
    id = id,
    agentRunId = agentRunId,
    description = description,
    doneCriteria = doneCriteria,
    successEvaluation = successEvaluation,
    isAchieved = isAchieved,
    achievedAt = achievedAt,
)

internal fun GoalBackup.toEntity() = com.aura.agentrun.GoalEntity(
    id = id,
    agentRunId = agentRunId,
    description = description,
    doneCriteria = doneCriteria,
    successEvaluation = successEvaluation,
    isAchieved = isAchieved,
    achievedAt = achievedAt,
)

@Serializable
data class StepBackup(
    val id: String,
    val agentRunId: String,
    val parentStepId: String? = null,
    val toolName: String,
    val toolArgs: String,
    val status: String,
    val dependsOn: String,
    val result: String,
    val errorMessage: String,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val postconditionResult: String,
    val position: Int,
)

internal fun com.aura.agentrun.StepEntity.toBackup() = StepBackup(
    id = id,
    agentRunId = agentRunId,
    parentStepId = parentStepId,
    toolName = toolName,
    toolArgs = toolArgs,
    status = status,
    dependsOn = dependsOn,
    result = result,
    errorMessage = errorMessage,
    startedAt = startedAt,
    finishedAt = finishedAt,
    postconditionResult = postconditionResult,
    position = position,
)

internal fun StepBackup.toEntity() = com.aura.agentrun.StepEntity(
    id = id,
    agentRunId = agentRunId,
    parentStepId = parentStepId,
    toolName = toolName,
    toolArgs = toolArgs,
    status = status,
    dependsOn = dependsOn,
    result = result,
    errorMessage = errorMessage,
    startedAt = startedAt,
    finishedAt = finishedAt,
    postconditionResult = postconditionResult,
    position = position,
)

@Serializable
data class AgentEventBackup(
    val id: String,
    val agentRunId: String,
    val stepId: String? = null,
    val parentEventId: String? = null,
    val timestamp: Long,
    val type: String,
    val toolName: String? = null,
    val redactedPayload: String,
    val durationMs: Long,
    val success: Boolean,
    val errorCode: String? = null,
)

internal fun com.aura.agentrun.AgentEventEntity.toBackup() = AgentEventBackup(
    id = id,
    agentRunId = agentRunId,
    stepId = stepId,
    parentEventId = parentEventId,
    timestamp = timestamp,
    type = type,
    toolName = toolName,
    redactedPayload = redactedPayload,
    durationMs = durationMs,
    success = success,
    errorCode = errorCode,
)

internal fun AgentEventBackup.toEntity() = com.aura.agentrun.AgentEventEntity(
    id = id,
    agentRunId = agentRunId,
    stepId = stepId,
    parentEventId = parentEventId,
    timestamp = timestamp,
    type = type,
    toolName = toolName,
    redactedPayload = redactedPayload,
    durationMs = durationMs,
    success = success,
    errorCode = errorCode,
)

@Serializable
data class ApprovalRequestBackup(
    val id: String,
    val agentRunId: String,
    val stepId: String,
    val toolName: String,
    val rationale: String,
    val status: String,
    val decisionAt: Long? = null,
    val denyReason: String,
    val expiresAt: Long,
)

internal fun com.aura.agentrun.ApprovalRequestEntity.toBackup() = ApprovalRequestBackup(
    id = id,
    agentRunId = agentRunId,
    stepId = stepId,
    toolName = toolName,
    rationale = rationale,
    status = status,
    decisionAt = decisionAt,
    denyReason = denyReason,
    expiresAt = expiresAt,
)

internal fun ApprovalRequestBackup.toEntity() = com.aura.agentrun.ApprovalRequestEntity(
    id = id,
    agentRunId = agentRunId,
    stepId = stepId,
    toolName = toolName,
    rationale = rationale,
    status = status,
    decisionAt = decisionAt,
    denyReason = denyReason,
    expiresAt = expiresAt,
)

@Serializable
data class RunCheckpointBackup(
    val id: String,
    val agentRunId: String,
    val stateJson: String,
    val createdAt: Long,
)

internal fun com.aura.agentrun.RunCheckpointEntity.toBackup() = RunCheckpointBackup(
    id = id,
    agentRunId = agentRunId,
    stateJson = stateJson,
    createdAt = createdAt,
)

internal fun RunCheckpointBackup.toEntity() = com.aura.agentrun.RunCheckpointEntity(
    id = id,
    agentRunId = agentRunId,
    stateJson = stateJson,
    createdAt = createdAt,
)
