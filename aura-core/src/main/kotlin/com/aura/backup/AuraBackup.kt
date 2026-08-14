package com.aura.backup

import com.aura.agent.AgentEntity
import com.aura.agent.StrategyBanditBackup
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
    /**
     * Schema v21: whether proactive suggestions actually helped.
     *
     * Not optional. ENGINEERING_HISTORY §2.2 records shipping eight entities
     * with no backup class at all, and one of the things a restore silently
     * dropped was the user's responses to proactive suggestions. Doing that
     * again to the table those responses are now measured in would be the same
     * bug with a straight face.
     */
    val proactiveOutcomes: List<ProactiveOutcomeBackup> = emptyList(),
    /**
     * Schema v19: living worlds and their history.
     *
     * A world is the most irreplaceable thing in a creative project — it is
     * months of accumulated, unrepeatable history, and unlike a draft the user
     * cannot rewrite it. Its state is deterministic given the seed, but only
     * from tick zero forward, and any intervention along the way is not
     * re-derivable at all.
     */
    val livingWorlds: List<LivingWorldBackup> = emptyList(),
    val livingEvents: List<LivingEventBackup> = emptyList(),
    val corrections: List<CorrectionBackup> = emptyList(),
    val openQuestions: List<OpenQuestionBackup> = emptyList(),
    val placeVisits: List<PlaceVisitBackup> = emptyList(),
    val creativeAnalysis: List<CreativeAnalysisBackup> = emptyList(),
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
    // Schema v13: the last 8 Room entities with no backup class. Seven are
    // covered here; CreativeGenerationJobEntity is deliberately transient —
    // see AuraBackupSchema13.kt for why.
    val artifactDependencies: List<ArtifactDependencyBackup> = emptyList(),
    val continuityIssues: List<ContinuityIssueBackup> = emptyList(),
    val creativeSimulations: List<CreativeSimulationBackup> = emptyList(),
    val evolutionEvidence: List<EvolutionEvidenceBackup> = emptyList(),
    val evolutionCandidates: List<EvolutionCandidateBackup> = emptyList(),
    val proactiveInteractions: List<ProactiveInteractionBackup> = emptyList(),
    val routingOutcomes: List<RoutingOutcomeBackup> = emptyList(),
    // Schema v15: learned strategy weights.
    val strategyBandit: List<StrategyBanditBackup> = emptyList(),
    // Schema v16: council — agent emotional state, relationships, observations, forum.
    val agentStates: List<AgentStateBackup> = emptyList(),
    val agentRelationships: List<AgentRelationshipBackup> = emptyList(),
    val agentObservations: List<AgentObservationBackup> = emptyList(),
    val forumPosts: List<ForumPostBackup> = emptyList(),
    val forumVotes: List<ForumVoteBackup> = emptyList(),
    // Schema v18: per-tool policy and the five consciousness stores — see
    // AuraBackupSchema18.kt for why all five had to land together.
    val toolPolicies: List<ToolPolicyBackup> = emptyList(),
    val consciousness: ConsciousnessBackup? = null,
    /**
     * A fixed probe encrypted with the exporting install's Keystore key.
     *
     * [EvolutionRevisionBackup.snapshotCiphertext] is AES-GCM ciphertext under
     * a key that never leaves the device, so on any other install it decrypts
     * to null and `EvolutionSkillRevisionStore.latest` reports "no snapshot" —
     * which is why every skill revert silently did nothing after a device
     * migration. The Android Keystore will not export the key itself, so the
     * restore compares this probe instead: decrypt it, and if the plaintext
     * comes back the ciphertexts in this file belong to this install. Null
     * means "this file predates the probe", which is not the same as "foreign"
     * — see `BackupManager.restoreEvolutionRows`.
     */
    val keyCanary: String? = null,
) {
    companion object {
        /**
         * 17 is skipped: ENGINEERING_HISTORY §3 specified this work as
         * `AuraBackupSchema18.kt` before a version number was allocated, and
         * matching the constant to the filename the plan was recorded under is
         * less confusing than a file and a version that disagree.
         */
        const val SCHEMA_VERSION = 25
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
    /** Which embedding model produced the embedding. Null = no embedding or pre-field. */
    val embeddingModel: String? = null,
    /** Schema version of the embedding vector. 0 = pre-field. */
    val embeddingVersion: Int = 0,
    /**
     * Retirement state. A retired memory is superseded, not deleted, so a
     * restore that dropped these would resurrect every memory a consolidation
     * or correction had replaced — silently reintroducing facts the user had
     * already told Aura were wrong. Null in older backups, which is correct:
     * nothing was retired before the column existed.
     */
    val retiredAt: Long? = null,
    val supersededBy: String? = null,
    val retiredReason: String? = null,
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
    /**
     * Schema v20. Absent until now, so a recurring task created by
     * `schedule_task` silently came back one-shot from every restore — the
     * column existed on the entity, in the database and in the schema export,
     * and only the backup DTO had never heard of it.
     */
    val recurrence: String? = null,
    /**
     * Schema v20: the attention model. Defaulted, so older backups restore as
     * fully bright tasks and are dimmed by their first real decay pass rather
     * than arriving pre-judged.
     */
    val salience: Double = 1.0,
    val lastTouchedAt: Long = 0L,
    val deferCount: Int = 0,
    val quietSince: Long = 0L,
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
    // Defaulted, like every other field added to this class: an older backup
    // decodes without them rather than failing the whole restore.
    val videoModel: String? = null,
    val voiceModel: String? = null,
    val smtpHost: String? = null,
    val smtpPort: Int = 0,
    val smtpUsername: String? = null,
    val smtpFrom: String? = null,
    val mcpServersJson: String = "[]",
    val evolutionOnboardingShown: Boolean = false,
    // Daemon thinking worker (configurable interval) — Settings toggle exists in the
    // UI but was previously lost on backup/restore. Default off; users
    // who have enabled it explicitly will get their preference back.
    val daemonEnabled: Boolean = false,
    // Schema v15 additions — reasoning, integrations, per-role models, dream stats.
    val reasoningEnabled: Boolean = true,
    val reasoningBudget: Int = 32000,
    val googleClientId: String = "",
    val microsoftClientId: String = "",
    val fastModel: String? = null,
    val reasoningModel: String? = null,
    val creativeDraftModel: String? = null,
    val creativeCriticModel: String? = null,
    val plannerModel: String? = null,
    val verifierModel: String? = null,
    val evolutionModel: String? = null,
    val dreamLastRunAt: Long = 0L,
    val dreamLastRunStats: String = "",
    // Schema v16: previously-lost toggles and settings.
    val dreamEnabled: Boolean = true,
    val decayEnabled: Boolean = true,
    val triggersEnabled: Boolean = true,
    val triggersJson: String = "[]",
    val planningEnabled: Boolean = false,
    val defaultAgentId: String = "",
    // Schema v16: council preferences. Default matches
    // UserPreferences.councilEnabled (false since the P0 sweep).
    val councilEnabled: Boolean = false,
    val councilAutoApply: Boolean = false,
    val councilActivityLevel: Int = 3,
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
    val headRevisionId: String? = null,
    val status: String = "active",
    val createdAt: Long,
    val updatedAt: Long = createdAt,
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

@Serializable
data class ProactiveOutcomeBackup(
    val id: Long = 0,
    val eventId: Long,
    val findingType: String,
    val subjectKind: String,
    val subjectIds: String = "[]",
    val baselineJson: String = "{}",
    val surface: String = "card",
    val postedAt: Long,
    val dueAt: Long = 0L,
    val outcome: String = "pending",
    val outcomeAt: Long = 0L,
    val outcomeReason: String = "",
)

// ── Schema v19: living world backup types ──

@Serializable
data class LivingWorldBackup(
    val id: String,
    val projectId: String,
    val branchId: String,
    val rootSeed: Long,
    val branchSalt: Long = 0L,
    val parentWorldId: String = "",
    val forkedAtTick: Long = 0L,
    val worldEpochMs: Long,
    val currentTick: Long = 0L,
    val stateJson: String,
    val status: String = "running",
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * Aura's open questions, including the ones already answered and refused.
 *
 * The refusals are the reason this is in the backup. "Never ask about this
 * again" is enforced by the row existing; a restore without them would have
 * Aura cheerfully re-ask everything the user has already told it to drop, which
 * is the single worst thing this feature could do.
 */
/**
 * Somewhere the user was.
 *
 * In the backup because it is personal data the user chose to let Aura keep, and
 * a restore that silently dropped it would lose the only record of it — this is
 * not telemetry like `worker_runs`, which is deliberately excluded. Coordinates
 * are already coarse in the table; nothing here re-precises them.
 */
/**
 * What an analysis pass concluded about one revision.
 *
 * Backed up rather than treated as derived, despite being computed from text
 * that is itself in the backup. Regenerating it costs a model call per revision,
 * and — the part that actually matters — a score produced later by a different
 * model is not comparable with the ones beside it. The whole value of this table
 * is the trend across a chain of drafts, and a trend rescored halfway through is
 * not a trend.
 */
@Serializable
data class CreativeAnalysisBackup(
    val id: String,
    val revisionId: String,
    val artifactId: String,
    val kind: String,
    val payloadJson: String,
    val headline: Float = 0f,
    val note: String = "",
    val createdAt: Long = 0L,
)

@Serializable
data class PlaceVisitBackup(
    val lat: Double,
    val lon: Double,
    val arrivedAt: Long,
    val lastSeenAt: Long,
    val samples: Int = 1,
    val label: String = "",
)

@Serializable
data class OpenQuestionBackup(
    val id: String,
    val kind: String,
    val subjectKind: String,
    val subjectId: String,
    val question: String,
    val status: String,
    val answerable: String = "user",
    val answerMemoryId: String? = null,
    val askedAt: Long? = null,
    val timesAsked: Int = 0,
    val answeredAt: Long? = null,
    val createdAt: Long = 0L,
)

/**
 * A correction the user made. Restoring without these would resurrect every
 * memory the user has retracted, which is the one thing a restore must never
 * do quietly.
 *
 * `queryEmbedding` is dropped: it is a derived vector, it is the largest field
 * here, and a scoped demotion whose embedding is missing simply stops matching
 * rather than matching wrongly. [queryText] survives, so it can be recomputed.
 */
@Serializable
data class CorrectionBackup(
    val id: String,
    val targetKind: String,
    val targetId: String,
    val kind: String,
    val replacementId: String? = null,
    val note: String = "",
    val queryText: String = "",
    val sourceConversationId: String = "",
    val sourceTurnTimestamp: Long = 0L,
    val propagatedJson: String = "[]",
    val createdAt: Long = 0L,
    val undoneAt: Long? = null,
)

@Serializable
data class LivingEventBackup(
    val id: String,
    val worldId: String,
    val branchId: String,
    val tickIndex: Long,
    val seq: Int,
    val kind: String,
    val actorId: String,
    val targetId: String = "",
    val ruleId: String = "",
    val magnitudeMilli: Long = 0L,
    val summary: String,
    val notability: Double = 0.0,
    val narration: String = "",
    val narratedAt: Long = 0L,
    val createdAt: Long,
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
    // Schema v14: belief-linked contradictions (db v3). Null default keeps
    // pre-v14 backups, which only ever had summary-linked rows, decoding
    // fine.
    val olderBeliefId: String? = null,
    val newerBeliefId: String? = null,
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



// ── Schema v16: Council backup types ──

@Serializable
data class AgentStateBackup(
    val agentId: kotlin.String,
    val mood: Float,
    val energy: Float,
    val currentGoal: kotlin.String,
    val stanceOnUser: Float,
    val participationCount: Int,
    val lastActiveAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class AgentRelationshipBackup(
    val agentAId: kotlin.String,
    val agentBId: kotlin.String,
    val affinity: Float,
    val conflictCount: Int,
    val collaborationCount: Int,
    val updatedAt: Long,
)

@Serializable
data class AgentObservationBackup(
    val agentId: kotlin.String,
    val targetType: kotlin.String,
    val targetId: kotlin.String,
    val content: kotlin.String,
    val sentiment: Float,
    val weight: Float,
    val resolved: Boolean,
    val createdAt: Long,
)

@Serializable
data class ForumPostBackup(
    val threadId: kotlin.String,
    val agentId: kotlin.String,
    val replyToId: Long? = null,
    val type: kotlin.String,
    val title: kotlin.String,
    val body: kotlin.String,
    val sentiment: Float,
    val status: kotlin.String,
    val createdAt: Long,
)

@Serializable
data class ForumVoteBackup(
    val postId: Long,
    val agentId: kotlin.String,
    val vote: kotlin.String,
    val reason: kotlin.String,
    val createdAt: Long,
)

// ── Council backup mappers ──

fun com.aura.agent.state.AgentStateEntity.toBackup() = AgentStateBackup(
    agentId = agentId, mood = mood, energy = energy,
    currentGoal = currentGoal, stanceOnUser = stanceOnUser,
    participationCount = participationCount, lastActiveAt = lastActiveAt,
    createdAt = createdAt, updatedAt = updatedAt,
)

fun AgentStateBackup.toEntity() = com.aura.agent.state.AgentStateEntity(
    agentId = agentId, mood = mood, energy = energy,
    currentGoal = currentGoal, stanceOnUser = stanceOnUser,
    participationCount = participationCount, lastActiveAt = lastActiveAt,
    createdAt = createdAt, updatedAt = updatedAt,
)

fun com.aura.agent.state.AgentRelationshipEntity.toBackup() = AgentRelationshipBackup(
    agentAId = agentAId, agentBId = agentBId,
    affinity = affinity, conflictCount = conflictCount,
    collaborationCount = collaborationCount, updatedAt = updatedAt,
)

fun AgentRelationshipBackup.toEntity() = com.aura.agent.state.AgentRelationshipEntity(
    agentAId = agentAId, agentBId = agentBId,
    affinity = affinity, conflictCount = conflictCount,
    collaborationCount = collaborationCount, updatedAt = updatedAt,
)

fun com.aura.agent.state.AgentObservationEntity.toBackup() = AgentObservationBackup(
    agentId = agentId, targetType = targetType, targetId = targetId,
    content = content, sentiment = sentiment, weight = weight,
    resolved = resolved, createdAt = createdAt,
)

fun AgentObservationBackup.toEntity() = com.aura.agent.state.AgentObservationEntity(
    agentId = agentId, targetType = targetType, targetId = targetId,
    content = content, sentiment = sentiment, weight = weight,
    resolved = resolved, createdAt = createdAt,
)

fun com.aura.agent.forum.ForumPostEntity.toBackup() = ForumPostBackup(
    threadId = threadId, agentId = agentId, replyToId = replyToId,
    type = type, title = title, body = body, sentiment = sentiment,
    status = status, createdAt = createdAt,
)

fun ForumPostBackup.toEntity() = com.aura.agent.forum.ForumPostEntity(
    threadId = threadId, agentId = agentId, replyToId = replyToId,
    type = type, title = title, body = body, sentiment = sentiment,
    status = status, createdAt = createdAt,
)

fun com.aura.agent.forum.ForumVoteEntity.toBackup() = ForumVoteBackup(
    postId = postId, agentId = agentId, vote = vote, reason = reason, createdAt = createdAt,
)

fun ForumVoteBackup.toEntity() = com.aura.agent.forum.ForumVoteEntity(
    postId = postId, agentId = agentId, vote = vote, reason = reason, createdAt = createdAt,
)
