package com.aura.backup

import android.content.Context
import com.aura.agent.ConversationDao
import com.aura.agent.ConversationEntity
import com.aura.agent.AgentDao
import com.aura.agent.AgentEntity
import com.aura.agent.StrategyBanditDao
import com.aura.agent.StrategyBanditEntity
import com.aura.agent.toBackup
import com.aura.agent.toEntity
import com.aura.data.UserPreferences
import com.aura.creative.CreativeProjectDao
import com.aura.creative.CreativeProjectEntity
import com.aura.documents.DocumentDao
import com.aura.documents.DocumentEntity
import com.aura.hands.Hand
import com.aura.hands.HandDao
import com.aura.hands.HandRun
import com.aura.hands.HandScheduler
import com.aura.kg.KnowledgeGraphDao
import com.aura.kg.EdgeEntity
import com.aura.kg.NodeEntity
import com.aura.memory.MemoryDao
import com.aura.memory.MemoryEditDao
import com.aura.memory.MemoryEditEntity
import com.aura.memory.MemoryEntity
import com.aura.profile.UserProfile
import com.aura.profile.UserProfileDao
import com.aura.profile.UserProfileEntity
import com.aura.providers.ProviderKeys
import com.aura.proactive.ProactiveEventDao
import com.aura.proactive.ProactiveEventEntity
import com.aura.tasks.ReminderDao
import com.aura.tasks.ReminderEntity
import com.aura.tasks.ReminderRecurrence
import com.aura.tasks.ReminderScheduler
import com.aura.tasks.TaskDao
import com.aura.tasks.TaskEntity
import com.aura.usage.UsageTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton



// ── Mappers: Entity → Backup ──

internal fun MemoryEntity.toBackup() = MemoryBackup(
    id = id,
    content = content,
    source = source,
    category = category,
    // Preserve scope across the backup roundtrip. Without this,
    // agent-private memories (scope = "agent:<id>") would silently
    // be restored with the default "general" scope and leak into
    // the General agent's recall on restore. See MEMORY_AUDIT A1.
    scope = scope,
    importance = importance,
    createdAt = createdAt,
    accessedAt = accessedAt,
    accessCount = accessCount,
    decayScore = decayScore,
    tags = tags,
    metadata = metadata,
    sourceConversationId = sourceConversationId,
    sourceTurnTimestamp = sourceTurnTimestamp,
    retiredAt = retiredAt,
    supersededBy = supersededBy,
    retiredReason = retiredReason,
)

internal fun MemoryBackup.toEntity() = MemoryEntity(
    id = id,
    content = content,
    source = source,
    category = category,
    // Same reasoning as toBackup above. The default "general" in
    // the backup data class (for old backups) means a pre-scope
    // restore shows memories as general — which matches the
    // behavior at the time the backup was written (no agent
    // scoping existed).
    scope = scope,
    importance = importance,
    // Embedding left null — caller rebuilds via Settings → Rebuild.
    embedding = null,
    createdAt = createdAt,
    accessedAt = accessedAt,
    accessCount = accessCount,
    decayScore = decayScore,
    tags = tags,
    metadata = metadata,
    sourceConversationId = sourceConversationId,
    sourceTurnTimestamp = sourceTurnTimestamp,
    embeddingModel = embeddingModel,
    embeddingVersion = embeddingVersion,
    retiredAt = retiredAt,
    supersededBy = supersededBy,
    retiredReason = retiredReason,
)

internal fun com.aura.curiosity.OpenQuestionEntity.toBackup() = OpenQuestionBackup(
    id = id,
    kind = kind,
    subjectKind = subjectKind,
    subjectId = subjectId,
    question = question,
    status = status,
    answerable = answerable,
    answerMemoryId = answerMemoryId,
    askedAt = askedAt,
    timesAsked = timesAsked,
    answeredAt = answeredAt,
    createdAt = createdAt,
)

internal fun OpenQuestionBackup.toEntity() = com.aura.curiosity.OpenQuestionEntity(
    id = id,
    kind = kind,
    subjectKind = subjectKind,
    subjectId = subjectId,
    question = question,
    status = status,
    answerable = answerable,
    answerMemoryId = answerMemoryId,
    askedAt = askedAt,
    timesAsked = timesAsked,
    answeredAt = answeredAt,
    createdAt = createdAt,
)

internal fun com.aura.memory.CorrectionEntity.toBackup() = CorrectionBackup(
    id = id,
    targetKind = targetKind,
    targetId = targetId,
    kind = kind,
    replacementId = replacementId,
    note = note,
    queryText = queryText,
    sourceConversationId = sourceConversationId,
    sourceTurnTimestamp = sourceTurnTimestamp,
    propagatedJson = propagatedJson,
    createdAt = createdAt,
    undoneAt = undoneAt,
)

internal fun CorrectionBackup.toEntity() = com.aura.memory.CorrectionEntity(
    id = id,
    targetKind = targetKind,
    targetId = targetId,
    kind = kind,
    replacementId = replacementId,
    note = note,
    queryText = queryText,
    // Recomputable from queryText; a missing vector stops the demotion
    // matching rather than making it match the wrong questions.
    queryEmbedding = null,
    sourceConversationId = sourceConversationId,
    sourceTurnTimestamp = sourceTurnTimestamp,
    propagatedJson = propagatedJson,
    createdAt = createdAt,
    undoneAt = undoneAt,
)

internal fun MemoryEditEntity.toBackup() = MemoryEditBackup(
    id, memoryId, oldContent, newContent, oldCategory, newCategory, editedAt, editedBy,
)

internal fun MemoryEditBackup.toEntity() = MemoryEditEntity(
    id, memoryId, oldContent, newContent, oldCategory, newCategory, editedAt, editedBy,
)

internal fun DocumentEntity.toBackup() = DocumentBackup(
    id, name, mimeType, sourceUri, importedAt, characterCount, chunkCount,
)

internal fun DocumentBackup.toEntity() = DocumentEntity(
    id, name, mimeType, sourceUri, importedAt, characterCount, chunkCount,
)

internal fun CreativeProjectEntity.toBackup() = CreativeProjectBackup(
    id = id,
    name = name,
    description = description,
    genre = genre,
    tone = tone,
    worldJson = worldJson,
    templateId = templateId,
    metadataJson = metadataJson,
    turnCount = turnCount,
    lastSessionEnded = lastSessionEnded,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun CreativeProjectBackup.toEntity() = CreativeProjectEntity(
    id = id,
    name = name,
    description = description,
    genre = genre,
    tone = tone,
    worldJson = worldJson,
    templateId = templateId,
    metadataJson = metadataJson,
    turnCount = turnCount,
    lastSessionEnded = lastSessionEnded,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun ConversationEntity.toBackup() = ConversationBackup(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    systemPrompt = systemPrompt,
    model = model,
    metadataJson = metadataJson,
    turnsJson = turnsJson,
    contextSummary = contextSummary,
    summaryThroughTurn = summaryThroughTurn,
    agentId = agentId,
    // Preserve the soft-delete tombstone across the backup roundtrip.
    // The ConversationStore.delete() / restore() lifecycle depends on
    // this field; if it were dropped here, every restore would bring
    // soft-deleted conversations back as visible rows.
    deletedAt = deletedAt,
)

internal fun ConversationBackup.toEntity() = ConversationEntity(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    systemPrompt = systemPrompt,
    model = model,
    metadataJson = metadataJson,
    turnsJson = turnsJson,
    contextSummary = contextSummary,
    summaryThroughTurn = summaryThroughTurn,
    agentId = agentId,
    // Same reasoning as toBackup above. The default `null` in the
    // backup data class (for old backups) means a pre-soft-delete
    // restore shows everything as visible, which matches the behavior
    // at the time the backup was written.
    deletedAt = deletedAt,
)

internal fun NodeEntity.toBackup() = NodeBackup(
    id = id,
    label = label,
    type = type,
    properties = properties,
    confidence = confidence,
    sourceTurnId = sourceTurnId,
    sourceConversationId = sourceConversationId,
    sourceTurnTimestamp = sourceTurnTimestamp,
    createdAt = createdAt,
    updatedAt = updatedAt,
    accessCount = accessCount,
    lastAccessed = lastAccessed,
)

internal fun NodeBackup.toEntity() = NodeEntity(
    id = id,
    label = label,
    type = type,
    properties = properties,
    confidence = confidence,
    sourceTurnId = sourceTurnId,
    sourceConversationId = sourceConversationId,
    sourceTurnTimestamp = sourceTurnTimestamp,
    createdAt = createdAt,
    updatedAt = updatedAt,
    accessCount = accessCount,
    lastAccessed = lastAccessed,
)

internal fun EdgeEntity.toBackup() = EdgeBackup(
    id = id,
    type = type,
    sourceId = sourceId,
    targetId = targetId,
    weight = weight,
    properties = properties,
    confidence = confidence,
    sourceTurnId = sourceTurnId,
    sourceConversationId = sourceConversationId,
    sourceTurnTimestamp = sourceTurnTimestamp,
    createdAt = createdAt,
    lastReinforced = lastReinforced,
)

internal fun EdgeBackup.toEntity() = EdgeEntity(
    id = id,
    type = type,
    sourceId = sourceId,
    targetId = targetId,
    weight = weight,
    properties = properties,
    confidence = confidence,
    sourceTurnId = sourceTurnId,
    sourceConversationId = sourceConversationId,
    sourceTurnTimestamp = sourceTurnTimestamp,
    createdAt = createdAt,
    lastReinforced = lastReinforced,
)

internal fun Hand.toBackup() = HandBackup(
    id = id,
    name = name,
    triggerPhrase = triggerPhrase,
    steps = steps,
    enabled = enabled,
    createdAt = createdAt,
    variables = variables,
    conditions = conditions,
    scheduleType = scheduleType,
    scheduleHour = scheduleHour,
    scheduleMinute = scheduleMinute,
    scheduleDayOfWeek = scheduleDayOfWeek,
    updatedAt = updatedAt,
)

internal fun HandBackup.toEntity() = Hand(
    id = id,
    name = name,
    triggerPhrase = triggerPhrase,
    steps = steps,
    variables = variables,
    conditions = conditions,
    scheduleType = scheduleType,
    scheduleHour = scheduleHour,
    scheduleMinute = scheduleMinute,
    scheduleDayOfWeek = scheduleDayOfWeek,
    enabled = enabled,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun HandRun.toBackup() = HandRunBackup(
    id, handId, handName, trigger, status, startedAt, finishedAt, output, failedStep, variablesJson,
)

internal fun HandRunBackup.toEntity() = HandRun(
    id, handId, handName, trigger, status, startedAt, finishedAt, output, failedStep, variablesJson,
)

internal fun TaskEntity.toBackup() = TaskBackup(
    id = id, title = title, description = description,
    createdAt = createdAt, dueAt = dueAt, completedAt = completedAt,
    status = status, priority = priority, tags = tags,
    recurrence = recurrence,
    salience = salience, lastTouchedAt = lastTouchedAt,
    deferCount = deferCount, quietSince = quietSince,
)

internal fun TaskBackup.toEntity() = TaskEntity(
    id = id, title = title, description = description,
    createdAt = createdAt, dueAt = dueAt, completedAt = completedAt,
    status = status, priority = priority, tags = tags,
    recurrence = recurrence,
    salience = salience, lastTouchedAt = lastTouchedAt,
    deferCount = deferCount, quietSince = quietSince,
)

internal fun ReminderEntity.toBackup() = ReminderBackup(
    id, message, triggerAt, createdAt, taskId, recurrence, status, firedAt,
)

internal fun ReminderBackup.toEntity() = ReminderEntity(
    id = id,
    workId = "",
    message = message,
    triggerAt = triggerAt,
    createdAt = createdAt,
    taskId = taskId,
    recurrence = recurrence,
    status = status,
    firedAt = firedAt,
)

internal fun ProactiveEventEntity.toBackup() = ProactiveEventBackup(
    id, eventType, title, body, timestamp, payload, correlationTag,
)

internal fun ProactiveEventBackup.toEntity() = ProactiveEventEntity(
    id, eventType, title, body, timestamp, payload, correlationTag,
)

internal fun UserProfileEntity.toBackup() = UserProfileBackup(
    name = name,
    traitsJson = traitsJson,
    preferencesJson = preferencesJson,
    factsJson = factsJson,
    lastUpdated = lastUpdated,
    agentScope = agentScope,
)

internal fun UserProfileBackup.toEntity() = UserProfileEntity(
    id = 1,
    agentScope = agentScope,
    name = name,
    traitsJson = traitsJson,
    preferencesJson = preferencesJson,
    factsJson = factsJson,
    lastUpdated = lastUpdated,
)
internal fun EvolutionProposalBackup.toEntity() = com.aura.evolution.EvolutionProposalEntity(
    id = id,
    domain = domain,
    action = action,
    targetId = targetId,
    title = title,
    description = description,
    summary = summary,
    patchJson = patchJson,
    status = status,
    requiresApproval = requiresApproval,
    autoApply = autoApply,
    confidence = confidence,
    evidenceIdsJson = evidenceIdsJson,
    candidateIdsJson = candidateIdsJson,
    applySagaJson = applySagaJson,
    rollbackSnapshotJson = rollbackSnapshotJson,
    outcomeNote = outcomeNote,
    createdAt = createdAt,
    updatedAt = updatedAt,
    resolvedAt = resolvedAt,
)

internal fun EvolutionSettingsBackup.toEntity() = com.aura.evolution.EvolutionSettingsEntity(
    domain = domain,
    enabled = enabled,
    updatedAt = updatedAt,
)

internal fun EvolutionRevisionBackup.toEntity() = com.aura.evolution.EvolutionRevisionEntity(
    id = id,
    domain = domain,
    targetId = targetId,
    proposalId = proposalId,
    summary = summary,
    snapshotCiphertext = snapshotCiphertext,
    metadataJson = metadataJson,
    createdAt = createdAt,
)

// ── Schema v10: World model mappers (toBackup)

internal fun com.aura.world.BeliefEntity.toBackup() = BeliefBackup(
    id = id, subject = subject, predicate = predicate, valueJson = valueJson,
    confidence = confidence, validFrom = validFrom, validTo = validTo,
    status = status, supersededBy = supersededBy, privacyClass = privacyClass,
    agentScope = agentScope,
    createdAt = createdAt, updatedAt = updatedAt, lastVerifiedAt = lastVerifiedAt,
)

internal fun com.aura.world.EvidenceEntity.toBackup() = EvidenceBackup(
    id = id, beliefId = beliefId, source = source, summary = summary,
    detailJson = detailJson, timestamp = timestamp, confidence = confidence,
    agentScope = agentScope,
)

internal fun com.aura.world.WorldEventEntity.toBackup() = WorldEventBackup(
    id = id, eventType = eventType, source = source, summary = summary,
    payloadJson = payloadJson, timestamp = timestamp, consumed = consumed,
    agentScope = agentScope,
)

internal fun com.aura.world.OpportunityEntity.toBackup() = OpportunityBackup(
    id = id, title = title, description = description, kind = kind,
    benefit = benefit, urgency = urgency, confidence = confidence,
    costEstimateJson = costEstimateJson, evidenceJson = evidenceJson,
    suggestedActionJson = suggestedActionJson, status = status,
    createdAt = createdAt, resolvedAt = resolvedAt, snoozeUntil = snoozeUntil,
    agentScope = agentScope,
)

// ── Schema v10: World model mappers (toEntity)
// Until v0.30.x, these backup types were serialized in snapshot() but
// never read in restore() — silent data loss on every restore. These
// toEntity() functions close the loop.

internal fun BeliefBackup.toEntity() = com.aura.world.BeliefEntity(
    id = id, subject = subject, predicate = predicate, valueJson = valueJson,
    confidence = confidence, validFrom = validFrom, validTo = validTo,
    status = status, supersededBy = supersededBy, privacyClass = privacyClass,
    agentScope = agentScope,
    createdAt = createdAt, updatedAt = updatedAt, lastVerifiedAt = lastVerifiedAt,
)

internal fun EvidenceBackup.toEntity() = com.aura.world.EvidenceEntity(
    id = id, beliefId = beliefId, source = source, summary = summary,
    detailJson = detailJson, timestamp = timestamp, confidence = confidence,
    agentScope = agentScope,
)

internal fun WorldEventBackup.toEntity() = com.aura.world.WorldEventEntity(
    id = id, eventType = eventType, source = source, summary = summary,
    payloadJson = payloadJson, timestamp = timestamp, consumed = consumed,
    agentScope = agentScope,
)

internal fun OpportunityBackup.toEntity() = com.aura.world.OpportunityEntity(
    id = id, title = title, description = description, kind = kind,
    benefit = benefit, urgency = urgency, confidence = confidence,
    costEstimateJson = costEstimateJson, evidenceJson = evidenceJson,
    suggestedActionJson = suggestedActionJson, status = status,
    createdAt = createdAt, resolvedAt = resolvedAt, snoozeUntil = snoozeUntil,
    agentScope = agentScope,
)

// ── Schema v10: Creative artifact mappers (toBackup)

internal fun com.aura.creative.CreativeArtifactEntity.toBackup() = CreativeArtifactBackup(
    id = id, projectId = projectId, branchId = branchId, kind = kind,
    title = title, currentRevisionId = currentRevisionId, previewText = previewText,
    mimeType = mimeType, storageUri = storageUri, contentHash = contentHash,
    status = status, metadataJson = metadataJson, createdAt = createdAt, updatedAt = updatedAt,
)

internal fun com.aura.creative.CanonFactEntity.toBackup() = CanonFactBackup(
    id = id, projectId = projectId, branchId = branchId, subjectType = subjectType,
    subjectId = subjectId, predicate = predicate, valueJson = valueJson,
    validFrom = validFrom, validTo = validTo, confidence = confidence,
    sourceRevisionId = sourceRevisionId, status = status,
    createdAt = createdAt, updatedAt = updatedAt,
)

// ── Schema v10: Creative artifact mappers (toEntity)
// Until v0.30.x, CreativeArtifactBackup and CanonFactBackup were
// written to JSON but never read back. Closing the loop now.

internal fun CreativeArtifactBackup.toEntity() = com.aura.creative.CreativeArtifactEntity(
    id = id, projectId = projectId, branchId = branchId, kind = kind,
    title = title, currentRevisionId = currentRevisionId, previewText = previewText,
    mimeType = mimeType, storageUri = storageUri, contentHash = contentHash,
    status = status, metadataJson = metadataJson, createdAt = createdAt, updatedAt = updatedAt,
)

// CreativeRevisionEntity ↔ CreativeRevisionBackup (schema extended in
// v0.30.x to match the entity's full field set; old backups remain
// forward-compatible because every new field has a default value).
internal fun com.aura.creative.CreativeRevisionEntity.toBackup() = CreativeRevisionBackup(
    id = id, artifactId = artifactId, branchId = branchId,
    parentRevisionId = parentRevisionId, contentText = contentText,
    storageUri = storageUri, contentHash = contentHash,
    authorKind = authorKind, providerPrefix = providerPrefix,
    modelId = modelId, prompt = prompt, settingsJson = settingsJson,
    createdAt = createdAt,
)

internal fun CreativeRevisionBackup.toEntity() = com.aura.creative.CreativeRevisionEntity(
    id = id, artifactId = artifactId, branchId = branchId,
    parentRevisionId = parentRevisionId, contentText = contentText,
    storageUri = storageUri, contentHash = contentHash,
    authorKind = authorKind, providerPrefix = providerPrefix,
    modelId = modelId, prompt = prompt, settingsJson = settingsJson,
    createdAt = createdAt,
)

internal fun com.aura.creative.CreativeBranchEntity.toBackup() = CreativeBranchBackup(
    id = id, projectId = projectId, name = name,
    baseRevisionId = baseRevisionId, headRevisionId = headRevisionId,
    status = status, createdAt = createdAt, updatedAt = updatedAt,
)

internal fun CreativeBranchBackup.toEntity() = com.aura.creative.CreativeBranchEntity(
    id = id, projectId = projectId, name = name,
    baseRevisionId = baseRevisionId, headRevisionId = headRevisionId,
    status = status, createdAt = createdAt, updatedAt = updatedAt,
)

internal fun CanonFactBackup.toEntity() = com.aura.creative.CanonFactEntity(
    id = id, projectId = projectId, branchId = branchId, subjectType = subjectType,
    subjectId = subjectId, predicate = predicate, valueJson = valueJson,
    validFrom = validFrom, validTo = validTo, confidence = confidence,
    sourceRevisionId = sourceRevisionId, status = status,
    createdAt = createdAt, updatedAt = updatedAt,
)

// ── Schema v10: Taste mappers (toBackup)

internal fun com.aura.taste.PreferenceSignalEntity.toBackup() = PreferenceSignalBackup(
    id = id, projectId = projectId, signalType = signalType, category = category,
    artifactId = artifactId, attributesJson = attributesJson,
    weight = weight, createdAt = createdAt, agentScope = agentScope,
)

internal fun com.aura.taste.StyleProfileEntity.toBackup() = StyleProfileBackup(
    id = id, projectId = projectId, attributesJson = attributesJson,
    signalCount = signalCount, createdAt = createdAt, updatedAt = updatedAt,
    agentScope = agentScope,
)

// ── Schema v10: Taste mappers (toEntity)

internal fun PreferenceSignalBackup.toEntity() = com.aura.taste.PreferenceSignalEntity(
    id = id, projectId = projectId, signalType = signalType, category = category,
    artifactId = artifactId, attributesJson = attributesJson,
    weight = weight, createdAt = createdAt, agentScope = agentScope,
)

internal fun StyleProfileBackup.toEntity() = com.aura.taste.StyleProfileEntity(
    id = id, projectId = projectId, attributesJson = attributesJson,
    signalCount = signalCount, createdAt = createdAt, updatedAt = updatedAt,
    agentScope = agentScope,
)

// ── Schema v11: Dream database mappers ──

internal fun com.aura.dream.DreamSummaryEntity.toBackup() = DreamSummaryBackup(
    id = id, clusterId = clusterId, compressedText = compressedText,
    sourceMemoryIds = sourceMemoryIds, dominantTags = dominantTags,
    sourceCount = sourceCount, modelUsed = modelUsed, createdAt = createdAt,
)

internal fun DreamSummaryBackup.toEntity() = com.aura.dream.DreamSummaryEntity(
    id = id, clusterId = clusterId, compressedText = compressedText,
    sourceMemoryIds = sourceMemoryIds, dominantTags = dominantTags,
    sourceCount = sourceCount, modelUsed = modelUsed, createdAt = createdAt,
)

internal fun com.aura.dream.RoutineEntity.toBackup() = RoutineBackup(
    id = id, signature = signature, displayLabel = displayLabel,
    occurrenceCount = occurrenceCount, distinctConversations = distinctConversations,
    sourceConversationIds = sourceConversationIds, firstSeenAt = firstSeenAt,
    lastSeenAt = lastSeenAt, description = description,
    createdAt = createdAt, updatedAt = updatedAt,
)

internal fun RoutineBackup.toEntity() = com.aura.dream.RoutineEntity(
    id = id, signature = signature, displayLabel = displayLabel,
    occurrenceCount = occurrenceCount, distinctConversations = distinctConversations,
    sourceConversationIds = sourceConversationIds, firstSeenAt = firstSeenAt,
    lastSeenAt = lastSeenAt, description = description,
    createdAt = createdAt, updatedAt = updatedAt,
)

internal fun com.aura.dream.ContradictionEntity.toBackup() = ContradictionBackup(
    id = id, olderSummaryId = olderSummaryId, newerSummaryId = newerSummaryId,
    olderText = olderText, newerText = newerText, triggerPhrase = triggerPhrase,
    confidence = confidence, status = status, createdAt = createdAt, resolvedAt = resolvedAt,
    olderBeliefId = olderBeliefId, newerBeliefId = newerBeliefId,
)

internal fun ContradictionBackup.toEntity() = com.aura.dream.ContradictionEntity(
    id = id, olderSummaryId = olderSummaryId, newerSummaryId = newerSummaryId,
    olderText = olderText, newerText = newerText, triggerPhrase = triggerPhrase,
    confidence = confidence, status = status, createdAt = createdAt, resolvedAt = resolvedAt,
    olderBeliefId = olderBeliefId, newerBeliefId = newerBeliefId,
)

internal fun com.aura.dream.KgEdgeProposalEntity.toBackup() = KgEdgeProposalBackup(
    id = id, fromNodeId = fromNodeId, toNodeId = toNodeId,
    fromLabel = fromLabel, toLabel = toLabel, similarity = similarity,
    proposedEdge = proposedEdge, status = status, createdAt = createdAt, decidedAt = decidedAt,
)

internal fun KgEdgeProposalBackup.toEntity() = com.aura.dream.KgEdgeProposalEntity(
    id = id, fromNodeId = fromNodeId, toNodeId = toNodeId,
    fromLabel = fromLabel, toLabel = toLabel, similarity = similarity,
    proposedEdge = proposedEdge, status = status, createdAt = createdAt, decidedAt = decidedAt,
)
// ── Schema v19: living worlds ──

internal fun com.aura.creative.livingworld.LivingWorldEntity.toBackup() = LivingWorldBackup(
    id = id, projectId = projectId, branchId = branchId, rootSeed = rootSeed,
    branchSalt = branchSalt, parentWorldId = parentWorldId, forkedAtTick = forkedAtTick,
    worldEpochMs = worldEpochMs, currentTick = currentTick, stateJson = stateJson,
    status = status, createdAt = createdAt, updatedAt = updatedAt,
)

internal fun LivingWorldBackup.toEntity() = com.aura.creative.livingworld.LivingWorldEntity(
    id = id, projectId = projectId, branchId = branchId, rootSeed = rootSeed,
    branchSalt = branchSalt, parentWorldId = parentWorldId, forkedAtTick = forkedAtTick,
    worldEpochMs = worldEpochMs, currentTick = currentTick, stateJson = stateJson,
    status = status, createdAt = createdAt, updatedAt = updatedAt,
)

internal fun com.aura.creative.livingworld.LivingEventEntity.toBackup() = LivingEventBackup(
    id = id, worldId = worldId, branchId = branchId, tickIndex = tickIndex, seq = seq,
    kind = kind, actorId = actorId, targetId = targetId, ruleId = ruleId,
    magnitudeMilli = magnitudeMilli, summary = summary, notability = notability,
    narration = narration, narratedAt = narratedAt, createdAt = createdAt,
)

internal fun LivingEventBackup.toEntity() = com.aura.creative.livingworld.LivingEventEntity(
    id = id, worldId = worldId, branchId = branchId, tickIndex = tickIndex, seq = seq,
    kind = kind, actorId = actorId, targetId = targetId, ruleId = ruleId,
    magnitudeMilli = magnitudeMilli, summary = summary, notability = notability,
    narration = narration, narratedAt = narratedAt, createdAt = createdAt,
)

// ── Schema v21: proactive outcomes ──

internal fun com.aura.proactive.ProactiveOutcomeEntity.toBackup() = ProactiveOutcomeBackup(
    id = id, eventId = eventId, findingType = findingType, subjectKind = subjectKind,
    subjectIds = subjectIds, baselineJson = baselineJson, surface = surface,
    postedAt = postedAt, dueAt = dueAt, outcome = outcome, outcomeAt = outcomeAt,
    outcomeReason = outcomeReason,
)

internal fun ProactiveOutcomeBackup.toEntity() = com.aura.proactive.ProactiveOutcomeEntity(
    id = id, eventId = eventId, findingType = findingType, subjectKind = subjectKind,
    subjectIds = subjectIds, baselineJson = baselineJson, surface = surface,
    postedAt = postedAt, dueAt = dueAt, outcome = outcome, outcomeAt = outcomeAt,
    outcomeReason = outcomeReason,
)
