package com.aura.backup

import com.aura.agent.AgentEntity
import com.aura.usage.UsageSnapshot
import kotlinx.serialization.Serializable

/**
 * Top-level shape of an Aura data export. The file is JSON-encoded with
 * kotlinx.serialization. Schema is versioned: a future Aura build that
 * reads a [SCHEMA_VERSION] it doesn't recognize should refuse (or
 * warn) rather than silently dropping fields.
 *
 * The export deliberately omits API keys, OAuth tokens, and the
 * secure DataStore. Those live in [com.aura.security.SecureDataStore]
 * (encrypted with the Android Keystore) and would be useless on a
 * different device anyway — the user has to re-paste keys after a
 * fresh install. Including them would also create a security risk
 * (the JSON file would have plaintext keys sitting in a backup).
 *
 * Embeddings are also omitted. They are model-specific, take ~1.5 KB
 * per row, and are rebuilt on the next embedding pass. The
 * `memoryRebuildEmbeddings` action in Settings handles the rebuild
 * after a restore.
 */
@Serializable
data class AuraBackup(
    val schemaVersion: Int = SCHEMA_VERSION,
    val exportedAt: Long,
    val exportedBy: String = "aura-android",
    val appVersionName: String,
    val memories: List<MemoryBackup> = emptyList(),
    val memoryEdits: List<MemoryEditBackup> = emptyList(),
    val documents: List<DocumentBackup> = emptyList(),
    val creativeProjects: List<CreativeProjectBackup> = emptyList(),
    val conversations: List<ConversationBackup> = emptyList(),
    val knowledgeGraph: KnowledgeGraphBackup = KnowledgeGraphBackup(),
    val hands: List<HandBackup> = emptyList(),
    val handRuns: List<HandRunBackup> = emptyList(),
    val tasks: List<TaskBackup> = emptyList(),
    val reminders: List<ReminderBackup> = emptyList(),
    val proactiveEvents: List<ProactiveEventBackup> = emptyList(),
    val userProfile: UserProfileBackup? = null,
    val preferences: PreferencesBackup = PreferencesBackup(),
    val usage: UsageSnapshot = UsageSnapshot(),
    val evolutionProposals: List<EvolutionProposalBackup> = emptyList(),
    val evolutionSettings: List<EvolutionSettingsBackup> = emptyList(),
    val evolutionRevisions: List<EvolutionRevisionBackup> = emptyList(),
    val agents: List<AgentBackup> = emptyList(),
    // Schema v10: world model + creative artifacts + taste.
    val beliefs: List<BeliefBackup> = emptyList(),
    val evidence: List<EvidenceBackup> = emptyList(),
    val worldEvents: List<WorldEventBackup> = emptyList(),
    val opportunities: List<OpportunityBackup> = emptyList(),
    val creativeArtifacts: List<CreativeArtifactBackup> = emptyList(),
    val creativeRevisions: List<CreativeRevisionBackup> = emptyList(),
    val creativeBranches: List<CreativeBranchBackup> = emptyList(),
    val canonFacts: List<CanonFactBackup> = emptyList(),
    val preferenceSignals: List<PreferenceSignalBackup> = emptyList(),
    val styleProfiles: List<StyleProfileBackup> = emptyList(),
    // Schema v11: dream database — dream summaries, routines, contradictions, KG edge proposals.
    val dreamSummaries: List<DreamSummaryBackup> = emptyList(),
    val routines: List<RoutineBackup> = emptyList(),
    val contradictions: List<ContradictionBackup> = emptyList(),
    val kgEdgeProposals: List<KgEdgeProposalBackup> = emptyList(),
    // Schema v12: durable state that was persisted in Room but dropped on backup/restore.
    val memoryFeedback: List<MemoryFeedbackBackup> = emptyList(),
    val documentChunks: List<DocumentChunkBackup> = emptyList(),
    val referenceIdentities: List<ReferenceIdentityBackup> = emptyList(),
    val agentRuns: List<AgentRunBackup> = emptyList(),
    val agentGoals: List<GoalBackup> = emptyList(),
    val agentSteps: List<StepBackup> = emptyList(),
    val agentEvents: List<AgentEventBackup> = emptyList(),
    val agentApprovals: List<ApprovalRequestBackup> = emptyList(),
    val runCheckpoints: List<RunCheckpointBackup> = emptyList(),
) {
    companion object {
        const val SCHEMA_VERSION = 12
    }
}

@Serializable
data class AgentBackup(
    val id: String,
    val name: String,
    val icon: String,
    val description: String,
    val identity: String,
    val toolsAllowed: String,
    val preferredModel: String? = null,
    val memoryScope: String = "shared",
    val personalityJson: String = "{}",
    val isBuiltin: Boolean = false,
    val isDefault: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val color: Int = 0,
)

fun AgentEntity.toBackup() = AgentBackup(
    id = id,
    name = name,
    icon = icon,
    description = description,
    identity = identity,
    toolsAllowed = toolsAllowed,
    preferredModel = preferredModel,
    memoryScope = memoryScope,
    personalityJson = personalityJson,
    isBuiltin = isBuiltin,
    isDefault = isDefault,
    createdAt = createdAt,
    updatedAt = updatedAt,
    color = color,
)

fun AgentBackup.toEntity() = AgentEntity(
    id = id,
    name = name,
    icon = icon,
    description = description,
    identity = identity,
    toolsAllowed = toolsAllowed,
    preferredModel = preferredModel,
    memoryScope = memoryScope,
    personalityJson = personalityJson,
    isBuiltin = isBuiltin,
    isDefault = isDefault,
    createdAt = createdAt,
    updatedAt = updatedAt,
    color = color,
)

@Serializable
data class MemoryBackup(
    val id: String,
    val content: String,
    val source: String,
    val category: String,
    /**
     * Memory scope — "general" (shared across all agents), or
     * "agent:<id>" (private to a single agent). Until this field
     * was added, every restore dropped scope on the floor and all
     * memories became "general", which meant agent-private memories
     * leaked into the General agent's recall scope on every backup
     * roundtrip. The default "general" keeps old backups
     * forward-compatible — they restore as the original general
     * scope they had at backup-write time.
     */
    val scope: String = "general",
    val importance: Float,
    val createdAt: Long,
    val accessedAt: Long,
    val accessCount: Int,
    val decayScore: Float,
    val tags: String,
    val metadata: String,
    val sourceConversationId: String = "",
    val sourceTurnTimestamp: Long = 0L,
)

@Serializable
data class MemoryEditBackup(
    val id: Long,
    val memoryId: String,
    val oldContent: String,
    val newContent: String,
    val oldCategory: String,
    val newCategory: String,
    val editedAt: Long,
    val editedBy: String,
)

@Serializable
data class DocumentBackup(
    val id: String,
    val name: String,
    val mimeType: String,
    val sourceUri: String,
    val importedAt: Long,
    val characterCount: Int,
    val chunkCount: Int,
)

@Serializable
data class CreativeProjectBackup(
    val id: String,
    val name: String,
    val description: String,
    val genre: String,
    val tone: String,
    val worldJson: String,
    val templateId: String,
    val metadataJson: String,
    val turnCount: Int,
    val lastSessionEnded: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class ConversationBackup(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val systemPrompt: String?,
    val model: String?,
    val metadataJson: String,
    val turnsJson: String,
    val contextSummary: String = "",
    val summaryThroughTurn: Int = 0,
    val agentId: String? = null,
    /**
     * Soft-delete tombstone (epoch-ms when deleted, null = visible).
     * Without this field, a backup→restore roundtrip would silently
     * resurrect soft-deleted conversations. The default `null` keeps
     * backups written before the soft-delete migration (schema <10)
     * forward-compatible — they restore as visible rows, which is the
     * original behavior at the time they were written.
     */
    val deletedAt: Long? = null,
)

@Serializable
data class KnowledgeGraphBackup(
    val nodes: List<NodeBackup> = emptyList(),
    val edges: List<EdgeBackup> = emptyList(),
)

@Serializable
data class NodeBackup(
    val id: String,
    val label: String,
    val type: String,
    val properties: String,
    val confidence: Float,
    val sourceTurnId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val accessCount: Int,
    val lastAccessed: Long,
    val sourceConversationId: String = "",
    val sourceTurnTimestamp: Long = 0L,
)

@Serializable
data class EdgeBackup(
    val id: String,
    val type: String,
    val sourceId: String,
    val targetId: String,
    val weight: Float,
    val properties: String,
    val confidence: Float,
    val sourceTurnId: String,
    val createdAt: Long,
    val lastReinforced: Long,
    val sourceConversationId: String = "",
    val sourceTurnTimestamp: Long = 0L,
)

@Serializable
data class HandBackup(
    val id: String,
    val name: String,
    val triggerPhrase: String,
    val steps: String,
    val enabled: Boolean,
    val createdAt: Long,
    val variables: String = "{}",
    val conditions: String = "[]",
    val scheduleType: String = "none",
    val scheduleHour: Int = 9,
    val scheduleMinute: Int = 0,
    val scheduleDayOfWeek: Int = 1,
    val updatedAt: Long = createdAt,
)

@Serializable
data class HandRunBackup(
    val id: String,
    val handId: String,
    val handName: String,
    val trigger: String,
    val status: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val output: String = "",
    val failedStep: Int? = null,
    val variablesJson: String = "{}",
)

@Serializable
data class TaskBackup(
    val id: String,
    val title: String,
    val description: String,
    val createdAt: Long,
    val dueAt: Long? = null,
    val completedAt: Long? = null,
    val status: String,
    val priority: Int,
    val tags: String,
)

@Serializable
data class ReminderBackup(
    val id: String,
    val message: String,
    val triggerAt: Long,
    val createdAt: Long,
    val taskId: String,
    val recurrence: String,
    val status: String,
    val firedAt: Long? = null,
)

@Serializable
data class ProactiveEventBackup(
    val id: Long,
    val eventType: String,
    val title: String,
    val body: String,
    val timestamp: Long,
    val payload: String,
    val correlationTag: String = "",
)

@Serializable
data class UserProfileBackup(
    val name: String?,
    val traitsJson: String,
    val preferencesJson: String,
    val factsJson: String,
    val lastUpdated: Long,
    val agentScope: String = "general",
)

@Serializable
data class PreferencesBackup(
    val defaultModel: String? = null,
    val firstRunComplete: Boolean = false,
    val appLockEnabled: Boolean = false,
    val embeddingModel: String? = null,
    val lastSeenProactiveAt: Long = 0L,
    val morningBriefEnabled: Boolean = true,
    val calendarMonitorEnabled: Boolean = true,
    val ttsEnabled: Boolean = true,
    val incognitoDefault: Boolean = false,
    val themeMode: String = "system",
    val customIdentity: String = "",
    val specialistOverrides: String = "{}",
    val morningBriefHour: Int = 7,
    val specialistToolOverrides: String = "{}",
    val evolutionEnabled: Boolean = false,
    val evolutionIntervalHours: Int = 24,
    // Schema v8 additions — previously lost on backup/restore.
    val visionModel: String? = null,
    val backgroundModel: String? = null,
    val deepModeModel: String? = null,
    val moaReferenceModels: String = "[]",
    val moaAggregatorModel: String? = null,
    val imageModel: String? = null,
    val smtpHost: String? = null,
    val smtpPort: Int = 0,
    val smtpUsername: String? = null,
    val smtpFrom: String? = null,
    val mcpServersJson: String = "[]",
    val evolutionShadowEnabled: Boolean = false,
    val evolutionOnboardingShown: Boolean = false,
    // Daemon thinking worker (every 8 min) — Settings toggle exists in the
    // UI but was previously lost on backup/restore. Default off; users
    // who have enabled it explicitly will get their preference back.
    val daemonEnabled: Boolean = false,
)

@Serializable
data class EvolutionProposalBackup(
    val id: String,
    val domain: String,
    val action: String,
    val targetId: String,
    val title: String = "",
    val description: String = "",
    val summary: String = "",
    val patchJson: String = "{}",
    val status: String,
    val requiresApproval: Boolean = true,
    val autoApply: Boolean = false,
    val confidence: Float = 0.0f,
    val evidenceIdsJson: String = "[]",
    val candidateIdsJson: String = "[]",
    val applySagaJson: String = "{}",
    val rollbackSnapshotJson: String = "{}",
    val outcomeNote: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val resolvedAt: Long? = null,
)

@Serializable
data class EvolutionSettingsBackup(
    val domain: String,
    val enabled: Boolean,
    val updatedAt: Long,
)

@Serializable
data class EvolutionRevisionBackup(
    val id: String,
    val domain: String,
    val targetId: String,
    val proposalId: String? = null,
    val summary: String = "",
    val snapshotCiphertext: String = "",
    val metadataJson: String = "{}",
    val createdAt: Long,
)

// ── Schema v10: World model backup types ──

@Serializable
data class BeliefBackup(
    val id: String,
    val subject: String,
    val predicate: String,
    val valueJson: String,
    val confidence: Float = 1.0f,
    val validFrom: Long = 0L,
    val validTo: Long = 0L,
    val status: String = "active",
    val supersededBy: String? = null,
    val privacyClass: String = "personal",
    val agentScope: String = "general",
    val createdAt: Long,
    val updatedAt: Long,
    val lastVerifiedAt: Long = 0L,
)

@Serializable
data class EvidenceBackup(
    val id: String,
    val beliefId: String,
    val source: String,
    val summary: String,
    val detailJson: String = "{}",
    val timestamp: Long,
    val confidence: Float = 1.0f,
    val agentScope: String = "general",
)

@Serializable
data class WorldEventBackup(
    val id: String,
    val eventType: String,
    val source: String,
    val summary: String,
    val payloadJson: String = "{}",
    val timestamp: Long,
    val consumed: Boolean = false,
    val agentScope: String = "general",
)

@Serializable
data class OpportunityBackup(
    val id: String,
    val title: String,
    val description: String,
    val kind: String = "suggestion",
    val benefit: Float = 0.5f,
    val urgency: Float = 0.5f,
    val confidence: Float = 0.5f,
    val costEstimateJson: String = "{}",
    val evidenceJson: String = "[]",
    val suggestedActionJson: String = "{}",
    val status: String = "proposed",
    val createdAt: Long,
    val resolvedAt: Long? = null,
    val snoozeUntil: Long = 0L,
    val agentScope: String = "general",
)

// ── Schema v10: Creative artifact backup types ──

@Serializable
data class CreativeArtifactBackup(
    val id: String,
    val projectId: String,
    val branchId: String,
    val kind: String,
    val title: String,
    val currentRevisionId: String? = null,
    val previewText: String = "",
    val mimeType: String = "",
    val storageUri: String? = null,
    val contentHash: String = "",
    val status: String = "pending",
    val metadataJson: String = "{}",
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class CreativeRevisionBackup(
    val id: String,
    val artifactId: String,
    val branchId: String = "",
    val parentRevisionId: String? = null,
    val contentText: String = "",
    val storageUri: String? = null,
    val contentHash: String = "",
    val authorKind: String = "manual",
    val providerPrefix: String = "",
    val modelId: String = "",
    val prompt: String = "",
    val settingsJson: String = "{}",
    val createdAt: Long,
)

@Serializable
data class CreativeBranchBackup(
    val id: String,
    val projectId: String,
    val name: String,
    val baseRevisionId: String? = null,
    val parentBranchId: String? = null,
    val headRevisionId: String? = null,
    val headArtifactId: String? = null,
    val status: String = "active",
    val createdAt: Long,
)

@Serializable
data class CanonFactBackup(
    val id: String,
    val projectId: String,
    val branchId: String,
    val subjectType: String,
    val subjectId: String,
    val predicate: String,
    val valueJson: String,
    val validFrom: Long = 0L,
    val validTo: Long = 0L,
    val confidence: Float = 1.0f,
    val sourceRevisionId: String? = null,
    val status: String = "active",
    val createdAt: Long,
    val updatedAt: Long,
)

// ── Schema v10: Taste backup types ──

@Serializable
data class PreferenceSignalBackup(
    val id: String,
    val projectId: String = "",
    val signalType: String,
    val category: String,
    val artifactId: String? = null,
    val attributesJson: String = "{}",
    val weight: Float = 1.0f,
    val createdAt: Long,
    val agentScope: String = "general",
)

@Serializable
data class StyleProfileBackup(
    val id: String,
    val projectId: String = "",
    val attributesJson: String = "{}",
    val signalCount: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val agentScope: String = "general",
)

// ── Dream database (schema v11: dream summaries, routines, contradictions, KG proposals) ──

@Serializable
data class DreamSummaryBackup(
    val id: String,
    val clusterId: String,
    val compressedText: String,
    val sourceMemoryIds: String,
    val dominantTags: String,
    val sourceCount: Int,
    val modelUsed: String,
    val createdAt: Long,
)

@Serializable
data class RoutineBackup(
    val id: String,
    val signature: String,
    val displayLabel: String,
    val occurrenceCount: Int,
    val distinctConversations: Int,
    val sourceConversationIds: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val description: String = "",
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class ContradictionBackup(
    val id: String,
    val olderSummaryId: String,
    val newerSummaryId: String,
    val olderText: String,
    val newerText: String,
    val triggerPhrase: String,
    val confidence: Float = 0.6f,
    val status: String = "UNRESOLVED",
    val createdAt: Long,
    val resolvedAt: Long? = null,
)

@Serializable
data class KgEdgeProposalBackup(
    val id: String,
    val fromNodeId: String,
    val toNodeId: String,
    val fromLabel: String,
    val toLabel: String,
    val similarity: Float,
    val proposedEdge: String = "RELATES_TO",
    val status: String = "PENDING",
    val createdAt: Long,
    val decidedAt: Long? = null,
)

