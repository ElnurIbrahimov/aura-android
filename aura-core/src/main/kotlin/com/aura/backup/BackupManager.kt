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
import android.util.Log

/**
 * Reads the full local state into an [AuraBackup] for export, and
 * writes an [AuraBackup] back into Room for import.
 *
 * Both paths are O(total_rows) over the data they touch. For a
 * personal-use install (hundreds of memories, dozens of conversations,
 * thousands of KG edges) the export completes in well under a
 * second; the import is similar.
 *
 * Embeddings are intentionally NOT exported — they are model-specific
 * (768-dim for nomic-embed-text-v1.5; the hash sketch is 384) and would
 * be meaningless on a different device with a different model. [restore]
 * enqueues [com.aura.memory.ReembedWorker] when it finishes, so the
 * rebuild starts on its own. Settings → Memory → Rebuild embeddings still
 * forces a pass; it used to be the only way, and this comment said so.
 *
 * API keys are also NOT exported — they live in [com.aura.security.SecureDataStore]
 * (encrypted with the Android Keystore) and the user has to re-paste
 * them on a fresh install. Including them in plaintext in a backup
 * file would be a security regression.
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val memoryDao: MemoryDao,
    private val memoryEditDao: MemoryEditDao,
    private val documentDao: DocumentDao,
    private val creativeProjectDao: CreativeProjectDao,
    private val conversationDao: ConversationDao,
    private val kgDao: KnowledgeGraphDao,
    private val handDao: HandDao,
    private val taskDao: TaskDao,
    private val reminderDao: ReminderDao,
    private val proactiveEventDao: ProactiveEventDao,
    private val userProfileDao: UserProfileDao,
    private val providerKeys: ProviderKeys,
    private val userPreferences: UserPreferences,
    private val reminderScheduler: ReminderScheduler,
    private val handScheduler: HandScheduler,
    private val usageTracker: UsageTracker,
    private val evolutionProposalDao: com.aura.evolution.EvolutionProposalDao,
    private val evolutionSettingsDao: com.aura.evolution.EvolutionSettingsDao,
    private val evolutionRevisionDao: com.aura.evolution.EvolutionRevisionDao,
    private val agentDao: AgentDao,
    // Schema v10 DAOs — world model, creative artifacts, taste.
    private val beliefDao: com.aura.world.BeliefDao? = null,
    private val evidenceDao: com.aura.world.EvidenceDao? = null,
    private val worldEventDao: com.aura.world.WorldEventDao? = null,
    private val opportunityDao: com.aura.world.OpportunityDao? = null,
    private val creativeArtifactDao: com.aura.creative.CreativeArtifactDao? = null,
    private val creativeRevisionDao: com.aura.creative.CreativeRevisionDao? = null,
    private val creativeBranchDao: com.aura.creative.CreativeBranchDao? = null,
    private val canonFactDao: com.aura.creative.CanonFactDao? = null,
    private val proactiveOutcomeDao: com.aura.proactive.ProactiveOutcomeDao? = null,
    private val livingWorldDao: com.aura.creative.livingworld.LivingWorldDao? = null,
    private val livingEventDao: com.aura.creative.livingworld.LivingEventDao? = null,
    private val correctionDao: com.aura.memory.CorrectionDao? = null,
    private val openQuestionDao: com.aura.curiosity.OpenQuestionDao? = null,
    private val placeVisitDao: com.aura.place.PlaceVisitDao? = null,
    private val creativeAnalysisDao: com.aura.creative.CreativeAnalysisDao? = null,
    private val preferenceSignalDao: com.aura.taste.PreferenceSignalDao? = null,
    private val styleProfileDao: com.aura.taste.StyleProfileDao? = null,
    // Schema v11: dream database DAOs.
    private val dreamSummaryDao: com.aura.dream.DreamConsolidationDao? = null,
    private val routineDao: com.aura.dream.RoutineDao? = null,
    private val contradictionDao: com.aura.dream.ContradictionDao? = null,
    private val kgEdgeProposalDao: com.aura.dream.KgEdgeProposalDao? = null,
    private val memoryFeedbackDao: com.aura.memory.MemoryFeedbackDao? = null,
    // Schema v12 DAOs.
    private val documentChunkDao: com.aura.documents.DocumentChunkDao? = null,
    private val referenceIdentityDao: com.aura.taste.ReferenceIdentityDao? = null,
    private val agentRunDao: com.aura.agentrun.AgentRunDao? = null,
    private val goalDao: com.aura.agentrun.GoalDao? = null,
    private val stepDao: com.aura.agentrun.StepDao? = null,
    private val agentEventDao: com.aura.agentrun.AgentEventDao? = null,
    private val approvalRequestDao: com.aura.agentrun.ApprovalRequestDao? = null,
    private val runCheckpointDao: com.aura.agentrun.RunCheckpointDao? = null,
    // Schema v13 DAOs.
    private val artifactDependencyDao: com.aura.creative.ArtifactDependencyDao? = null,
    private val continuityIssueDao: com.aura.creative.ContinuityIssueDao? = null,
    private val creativeSimulationDao: com.aura.creative.CreativeSimulationDao? = null,
    private val evolutionEvidenceDao: com.aura.evolution.EvolutionEvidenceDao? = null,
    private val evolutionCandidateDao: com.aura.evolution.EvolutionCandidateDao? = null,
    private val proactiveInteractionDao: com.aura.proactive.ProactiveInteractionDao? = null,
    private val routingOutcomeDao: com.aura.taste.RoutingOutcomeDao? = null,
    // Schema v14: learned strategy weights.
    private val strategyBanditDao: StrategyBanditDao? = null,
    // Schema v16: council — agent state, relationships, observations, forum posts/votes.
    private val agentStateDao: com.aura.agent.state.AgentStateDao? = null,
    private val agentRelationshipDao: com.aura.agent.state.AgentRelationshipDao? = null,
    private val agentObservationDao: com.aura.agent.state.AgentObservationDao? = null,
    private val forumPostDao: com.aura.agent.forum.ForumPostDao? = null,
    private val forumVoteDao: com.aura.agent.forum.ForumVoteDao? = null,
    // Schema v18. Appended, not inserted: BackupManagerTest builds this class
    // with named arguments and omits everything defaulted, so a parameter added
    // mid-list is a compile break unrelated to what the test checks. The `= null`
    // defaults are for those test call sites only — Dagger ignores Kotlin
    // defaults and injects the real singleton for every one of these.
    private val toolPolicyStore: com.aura.agent.policy.ToolPolicyStore? = null,
    private val narrativeSelf: com.aura.consciousness.NarrativeSelf? = null,
    private val intrinsicMotivation: com.aura.consciousness.IntrinsicMotivation? = null,
    private val theoryOfMind: com.aura.consciousness.TheoryOfMind? = null,
    private val emotionEngine: com.aura.emotion.EmotionEngine? = null,
    private val affinityTracker: com.aura.consciousness.AffinityTracker? = null,
    private val keyManager: com.aura.security.KeyManager? = null,
    // Schema v26. Appended for the reason stated on the v18 block above.
    private val retrievalLabelDao: com.aura.memory.RetrievalLabelDao? = null,
    // Schema v27. Projects and their ledger, appended for the same reason.
    private val projectDao: com.aura.projects.ProjectDao? = null,
    private val projectNoteDao: com.aura.projects.ProjectNoteDao? = null,
    // Schema v28. Appended for the reason stated on the v18 block above.
    private val claimResolutionDao: com.aura.calibration.ClaimResolutionDao? = null,
    /**
     * Per-database transaction envelopes. Null in tests, where the DAOs are
     * mocks and there is no database to hold a transaction open on — every
     * call site below runs its block directly when this is absent, so the
     * ordering under test is identical either way.
     */
    private val transactions: RestoreTransactions? = null,
) : BackupService {

    private suspend fun encodeTriggersJson(userPreferences: UserPreferences): String = runCatching {
        val triggers = userPreferences.triggers.first()
        kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(com.aura.triggers.Trigger.serializer()),
            triggers,
        )
    }.onFailure { Log.w("BackupManager", "runCatching failed: ${it.message}", it) }.getOrDefault("[]")

private fun com.aura.evolution.EvolutionProposalEntity.toBackup() = EvolutionProposalBackup(
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

private fun com.aura.evolution.EvolutionSettingsEntity.toBackup() = EvolutionSettingsBackup(
    domain = domain,
    enabled = enabled,
    updatedAt = updatedAt,
)

private fun com.aura.evolution.EvolutionRevisionEntity.toBackup() = EvolutionRevisionBackup(
    id = id,
    domain = domain,
    targetId = targetId,
    proposalId = proposalId,
    summary = summary,
    snapshotCiphertext = snapshotCiphertext,
    metadataJson = metadataJson,
    createdAt = createdAt,
)
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Snapshot every persisted table into an [AuraBackup]. Caller is
     * responsible for serializing (e.g. via [encodeToJson]) and
     * writing to disk / sharing via Intent.
     *
     * WARNING: The exported JSON is plaintext. It contains all
     * conversations, memories, tasks, profile facts, and preferences
     * (but NOT API keys or embeddings). Treat the file as sensitive.
     */
        private suspend fun snapshotPreferences(userPreferences: UserPreferences): PreferencesBackup = PreferencesBackup(
                defaultModel = userPreferences.defaultModel.first()?.takeIf { it.isNotBlank() },
                firstRunComplete = userPreferences.firstRunComplete.first(),
                appLockEnabled = userPreferences.appLockEnabled.first(),
                embeddingModel = providerKeys.embeddingModel.takeIf { it.isNotBlank() },
                lastSeenProactiveAt = userPreferences.lastSeenProactiveAt.first(),
                morningBriefEnabled = userPreferences.morningBriefEnabled.first(),
                calendarMonitorEnabled = userPreferences.calendarMonitorEnabled.first(),
                ttsEnabled = userPreferences.ttsEnabled.first(),
                incognitoDefault = userPreferences.incognitoDefault.first(),
                themeMode = userPreferences.themeMode.first(),
                customIdentity = userPreferences.customIdentity.first(),
                specialistOverrides = userPreferences.specialistOverrides.first(),
                morningBriefHour = userPreferences.morningBriefHour.first(),
                specialistToolOverrides = userPreferences.specialistToolOverrides.first(),
                evolutionEnabled = userPreferences.evolutionEnabled.first(),
                evolutionIntervalHours = userPreferences.evolutionIntervalHours.first(),
                visionModel = userPreferences.visionModel.first(),
                backgroundModel = userPreferences.backgroundModel.first(),
                deepModeModel = userPreferences.deepModeModel.first(),
                moaReferenceModels = userPreferences.moaReferenceModels.first().joinToString(","),
                moaAggregatorModel = userPreferences.moaAggregatorModel.first()?.takeIf { it.isNotBlank() },
                imageModel = userPreferences.imageModel.first()?.takeIf { it.isNotBlank() },
                videoModel = userPreferences.videoModel.first()?.takeIf { it.isNotBlank() },
                voiceModel = userPreferences.voiceModel.first()?.takeIf { it.isNotBlank() },
                mcpServersJson = userPreferences.mcpServersJson.first(),
                evolutionOnboardingShown = userPreferences.evolutionOnboardingShown.first(),
                daemonEnabled = userPreferences.daemonEnabled.first(),
                reasoningEnabled = userPreferences.reasoningEnabled.first(),
                reasoningBudget = userPreferences.reasoningBudget.first(),
                googleClientId = userPreferences.googleClientId.first(),
                microsoftClientId = userPreferences.microsoftClientId.first(),
                fastModel = userPreferences.forRole(com.aura.providers.ModelRole.FAST).first(),
                reasoningModel = userPreferences.forRole(com.aura.providers.ModelRole.REASONING).first(),
                creativeDraftModel = userPreferences.forRole(com.aura.providers.ModelRole.CREATIVE_DRAFT).first(),
                creativeCriticModel = userPreferences.forRole(com.aura.providers.ModelRole.CREATIVE_CRITIC).first(),
                plannerModel = userPreferences.forRole(com.aura.providers.ModelRole.PLANNER).first(),
                verifierModel = userPreferences.forRole(com.aura.providers.ModelRole.VERIFIER).first(),
                evolutionModel = userPreferences.forRole(com.aura.providers.ModelRole.EVOLUTION).first(),
                dreamLastRunAt = userPreferences.dreamLastRunAt.first(),
                dreamLastRunStats = userPreferences.dreamLastRunStats.first(),
                dreamEnabled = userPreferences.dreamEnabled.first(),
                decayEnabled = userPreferences.decayEnabled.first(),
                triggersEnabled = userPreferences.triggersEnabled.first(),
                triggersJson = encodeTriggersJson(userPreferences),
                planningEnabled = userPreferences.planningEnabled.first(),
                defaultAgentId = userPreferences.agentId.first().orEmpty(),
                councilEnabled = userPreferences.councilEnabled.first(),
                councilAutoApply = userPreferences.councilAutoApply.first(),
                councilActivityLevel = userPreferences.councilActivityLevel.first(),
                smtpHost = userPreferences.smtpHost.first().takeIf { it.isNotBlank() },
                smtpPort = userPreferences.smtpPort.first(),
                smtpUsername = userPreferences.smtpUsername.first().takeIf { it.isNotBlank() },
                smtpFrom = userPreferences.smtpFrom.first().takeIf { it.isNotBlank() },
            )

    override suspend fun snapshot(appVersionName: String): AuraBackup =
        snapshot(appVersionName = appVersionName, strict = false)

    /**
     * @param strict when true, a table that cannot be read aborts the whole
     *   snapshot instead of contributing an empty list.
     *
     * Export wants `false`: a transient read failure should cost those rows,
     * not the entire backup. The pre-restore rollback snapshot wants `true`
     * and used not to get it — the three `getOrDefault(emptyList())` calls on
     * the evolution tables produced a snapshot indistinguishable from one
     * where those tables were genuinely empty. Roll that back after a
     * `purgeAll` and the rows are gone, with nothing in the log tying the loss
     * to the restore.
     *
     * The first paragraph is true of **every** table now. For a long time it
     * described six of them — the evolution three, the forum two and tool
     * policies — while the other fifty-odd read straight off their DAO, so any
     * one of them throwing took the whole export with it. `strict` was
     * unaffected by that (an unwrapped read aborts either way, which is what
     * strict wants); what was missing was the tolerance the non-strict path
     * promised, on the run that matters most — the one where the database is
     * already damaged.
     *
     * What is deliberately NOT wrapped: `preferences` and `usage` are not
     * tables, `consciousness` carries [strict] into its own reader, and
     * `keyCanary` is computed. Each of those failing is a reason to fail.
     */
    internal suspend fun snapshot(
        appVersionName: String,
        strict: Boolean,
    ): AuraBackup = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        // Collected by readTable rather than returned by it, because a non-strict export
        // has to finish before it knows what it missed. Applied with copy() below rather
        // than as a constructor argument, so it does not depend on Kotlin evaluating the
        // argument list in source order.
        val unreadable = mutableListOf<String>()
        val backup = AuraBackup(
            exportedAt = now,
            appVersionName = appVersionName,
            memories = readTable(strict, "memories", unreadable) { memoryDao.allForExport().map { it.toBackup() } },
            memoryEdits = readTable(strict, "memory edits", unreadable) { memoryEditDao.allForBackup().map { it.toBackup() } },
            documents = readTable(strict, "documents", unreadable) { documentDao.allForBackup().map { it.toBackup() } },
            creativeProjects = readTable(strict, "creative projects", unreadable) { creativeProjectDao.allForBackup().map { it.toBackup() } },
            conversations = readTable(strict, "conversations", unreadable) { conversationDao.allForExport().map { it.toBackup() } },
            // Each side wrapped separately rather than the pair: they are two
            // tables and either can fail on its own, and edges without nodes
            // still restore — `writeEverything` inserts nodes first and Room
            // drops orphan edges, which is a smaller loss than the whole file.
            knowledgeGraph = KnowledgeGraphBackup(
                nodes = readTable(strict, "knowledge graph nodes", unreadable) { kgDao.allNodes().map { it.toBackup() } },
                edges = readTable(strict, "knowledge graph edges", unreadable) { kgDao.allEdges().map { it.toBackup() } },
            ),
            hands = readTable(strict, "hands", unreadable) { handDao.getAll().map { it.toBackup() } },
            handRuns = readTable(strict, "hand runs", unreadable) { handDao.allRunsForBackup().map { it.toBackup() } },
            tasks = readTable(strict, "tasks", unreadable) { taskDao.all().map { it.toBackup() } },
            reminders = readTable(strict, "reminders", unreadable) { reminderDao.allForBackup().map { it.toBackup() } },
            proactiveEvents = readTable(strict, "proactive events", unreadable) { proactiveEventDao.allForBackup().map { it.toBackup() } },
            userProfile = readRow(strict, "user profile", unreadable) { userProfileDao.get()?.toBackup() },
            preferences = snapshotPreferences(userPreferences),
            usage = usageTracker.snapshot.value,
            agents = readTable(strict, "agents", unreadable) { agentDao.allOnce().map { it.toBackup() } },
            // Schema v10: world model, creative artifacts, taste.
            beliefs = readTable(strict, "beliefs", unreadable) { beliefDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            evidence = readTable(strict, "evidence", unreadable) { evidenceDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            worldEvents = readTable(strict, "world events", unreadable) { worldEventDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            opportunities = readTable(strict, "opportunities", unreadable) { opportunityDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            creativeArtifacts = readTable(strict, "creative artifacts", unreadable) { creativeArtifactDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            // CreativeRevision and CreativeBranch had types in AuraBackup.kt
            // since v0.30.0 but BackupManager never populated them, so
            // every snapshot silently wrote emptyList() for these fields.
            // Until v0.30.x they were dropped on roundtrip; this wires
            // them through snapshot+restore symmetrically.
            creativeRevisions = readTable(strict, "creative revisions", unreadable) { creativeRevisionDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            creativeBranches = readTable(strict, "creative branches", unreadable) { creativeBranchDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            canonFacts = readTable(strict, "canon facts", unreadable) { canonFactDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            proactiveOutcomes = readTable(strict, "proactive outcomes", unreadable) { proactiveOutcomeDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            livingWorlds = readTable(strict, "living worlds", unreadable) { livingWorldDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            livingEvents = readTable(strict, "living events", unreadable) { livingEventDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            corrections = readTable(strict, "corrections", unreadable) { correctionDao?.allForExport()?.map { it.toBackup() } ?: emptyList() },
            openQuestions = readTable(strict, "open questions", unreadable) { openQuestionDao?.allForExport()?.map { it.toBackup() } ?: emptyList() },
            placeVisits = readTable(strict, "place visits", unreadable) { placeVisitDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            creativeAnalysis = readTable(strict, "creative analysis", unreadable) { creativeAnalysisDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            preferenceSignals = readTable(strict, "preference signals", unreadable) { preferenceSignalDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            styleProfiles = readTable(strict, "style profiles", unreadable) { styleProfileDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            // Schema v11: dream database.
            dreamSummaries = readTable(strict, "dream summaries", unreadable) { dreamSummaryDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            routines = readTable(strict, "routines", unreadable) { routineDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            contradictions = readTable(strict, "contradictions", unreadable) { contradictionDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            kgEdgeProposals = readTable(strict, "kg edge proposals", unreadable) { kgEdgeProposalDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            // Schema v12: durable state previously dropped on backup/restore.
            memoryFeedback = readTable(strict, "memory feedback", unreadable) { memoryFeedbackDao?.all()?.map { it.toBackup() } ?: emptyList() },
            // Schema v26: harvested retrieval labels.
            retrievalLabels = readTable(strict, "retrieval labels", unreadable) { retrievalLabelDao?.all()?.map { it.toBackup() } ?: emptyList() },
            // Schema v27: projects and every ledger row, superseded ones included —
            // exporting only the active row would restore a project that has never
            // changed its mind. See AuraBackupSchema27.
            projects = readTable(strict, "projects", unreadable) { projectDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            projectNotes = readTable(strict, "project notes", unreadable) { projectNoteDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            // Schema v28: the only rows here that a person produced by hand.
            claimResolutions = readTable(strict, "claim resolutions", unreadable) { claimResolutionDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            documentChunks = readTable(strict, "document chunks", unreadable) { documentChunkDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            referenceIdentities = readTable(strict, "reference identities", unreadable) { referenceIdentityDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            agentRuns = readTable(strict, "agent runs", unreadable) { agentRunDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            agentGoals = readTable(strict, "agent goals", unreadable) { goalDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            agentSteps = readTable(strict, "agent steps", unreadable) { stepDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            agentEvents = readTable(strict, "agent events", unreadable) { agentEventDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            agentApprovals = readTable(strict, "agent approvals", unreadable) { approvalRequestDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            runCheckpoints = readTable(strict, "run checkpoints", unreadable) { runCheckpointDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            artifactDependencies = readTable(strict, "artifact dependencies", unreadable) { artifactDependencyDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            continuityIssues = readTable(strict, "continuity issues", unreadable) { continuityIssueDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            creativeSimulations = readTable(strict, "creative simulations", unreadable) { creativeSimulationDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            evolutionEvidence = readTable(strict, "evolution evidence", unreadable) { evolutionEvidenceDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            evolutionCandidates = readTable(strict, "evolution candidates", unreadable) { evolutionCandidateDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            evolutionProposals = readTable(strict, "evolution proposals", unreadable) { evolutionProposalDao.allForBackup().map { it.toBackup() } },
            evolutionSettings = readTable(strict, "evolution settings", unreadable) { evolutionSettingsDao.all().map { it.toBackup() } },
            evolutionRevisions = readTable(strict, "evolution revisions", unreadable) { evolutionRevisionDao.allForBackup().map { it.toBackup() } },
            proactiveInteractions = readTable(strict, "proactive interactions", unreadable) { proactiveInteractionDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            routingOutcomes = readTable(strict, "routing outcomes", unreadable) { routingOutcomeDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            strategyBandit = readTable(strict, "strategy bandit", unreadable) { strategyBanditDao?.all()?.map { it.toBackup() } ?: emptyList() },
            // Schema v16: council — agent state, relationships, observations, forum.
            agentStates = readTable(strict, "agent states", unreadable) { agentStateDao?.allOnce()?.map { it.toBackup() } ?: emptyList() },
            agentRelationships = readTable(strict, "agent relationships", unreadable) { agentRelationshipDao?.let { dao -> dao.allOnce().map { it.toBackup() } } ?: emptyList() },
            agentObservations = readTable(strict, "agent observations", unreadable) { agentObservationDao?.let { dao -> dao.allOnce().map { it.toBackup() } } ?: emptyList() },
            // Uncapped, like agentStates/agentRelationships/agentObservations
            // above. `recent(200)` truncated the exported forum and then
            // derived the votes from the same truncated list, so a rollback
            // could restore fewer posts than it had just purged.
            forumPosts = readTable(strict, "forum posts", unreadable) { forumPostDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            forumVotes = readTable(strict, "forum votes", unreadable) { forumVoteDao?.allForBackup()?.map { it.toBackup() } ?: emptyList() },
            // Schema v18.
            toolPolicies = readTable(strict, "tool policies", unreadable) {
                toolPolicyStore?.allPolicies?.first()?.values?.map { it.toBackup() } ?: emptyList()
            },
            consciousness = snapshotConsciousness(strict),
            keyCanary = encodeKeyCanary(),
        )
        if (unreadable.isNotEmpty()) {
            Log.w("BackupManager", "backup is missing ${unreadable.size} table(s): $unreadable")
        }
        backup.copy(unreadableTables = unreadable.toList())
    }

    /**
     * Read one table, logging any failure and — when [strict] — rethrowing it.
     *
     * The strict path exists so the pre-restore snapshot cannot come back
     * partial. A partial snapshot is the dangerous shape: it reads as "those
     * tables were empty", so the rollback purges and then restores less than
     * it destroyed.
     *
     * Every table goes through here now. Six did, and the KDoc on [snapshot]
     * described the tolerance as though it were general — so an export from a
     * database with one corrupt table threw out of `snapshot` and produced no
     * file at all, which is the state in which a backup is worth the most. The
     * six were the ones a previous incident had touched; the other fifty-odd
     * were never a decision.
     */
    private suspend fun <T> readTable(
        strict: Boolean,
        label: String,
        unreadable: MutableList<String>,
        read: suspend () -> List<T>,
    ): List<T> = runCatching { read() }
        .onFailure { error ->
            Log.w("BackupManager", "$label snapshot failed: ${error.message}", error)
            if (strict) throw error
            unreadable += label
        }
        .getOrDefault(emptyList())

    /**
     * [readTable] for a single row rather than a list.
     *
     * Separate because the missing value is `null` here and `emptyList()`
     * there, and a generic "default" parameter would let a caller pass
     * something that reads as real data. There is exactly one caller — the user
     * profile — and one caller is not yet a reason to generalise.
     */
    private suspend fun <T> readRow(
        strict: Boolean,
        label: String,
        unreadable: MutableList<String>,
        read: suspend () -> T?,
    ): T? = runCatching { read() }
        .onFailure { error ->
            Log.w("BackupManager", "$label snapshot failed: ${error.message}", error)
            if (strict) throw error
            unreadable += label
        }
        .getOrNull()

    /**
     * Snapshot the five consciousness stores, or null when none are wired.
     *
     * They are one field rather than five because ENGINEERING_HISTORY §3
     * records why they waited: backing up one blob would have set an
     * inconsistent precedent for the other four.
     */
    private suspend fun snapshotConsciousness(strict: Boolean): ConsciousnessBackup? = runCatching {
        val narrative = narrativeSelf?.snapshot()?.toBackup()
        val drives = intrinsicMotivation?.drives?.value?.values?.map { it.toBackup() } ?: emptyList()
        val userModel = theoryOfMind?.model?.value?.toBackup()
        val emotion = emotionEngine?.snapshot()?.toBackup()
        val affinity = affinityTracker?.exportRaw()?.let { (score, at) -> AffinityBackup(score = score, lastInteractionAt = at) }
        if (narrative == null && drives.isEmpty() && userModel == null && emotion == null && affinity == null) null
        else ConsciousnessBackup(narrative, drives, userModel, emotion, affinity)
    }.onFailure { error ->
        Log.w("BackupManager", "consciousness snapshot failed: ${error.message}", error)
        if (strict) throw error
    }.getOrNull()

    /**
     * Encrypt the canary probe with this install's Keystore key, or null when
     * no [com.aura.security.KeyManager] is wired. See [AuraBackup.keyCanary].
     */
    private fun encodeKeyCanary(): String? {
        val km = keyManager ?: return null
        return runCatching { km.encrypt(KEY_CANARY_PLAINTEXT, km.getOrCreateKey()) }
            .onFailure { Log.w("BackupManager", "key canary encrypt failed: ${it.message}", it) }
            .getOrNull()
    }

    /** True when [canary] decrypts to the probe under this install's key. */
    private fun canaryMatchesThisInstall(canary: String?): Boolean {
        val km = keyManager ?: return false
        if (canary.isNullOrBlank()) return false
        return runCatching { km.decrypt(canary, km.getOrCreateKey()) }
            .onFailure { Log.w("BackupManager", "key canary decrypt failed: ${it.message}", it) }
            .getOrNull() == KEY_CANARY_PLAINTEXT
    }

    /**
     * Serialize [backup] to JSON. Public so the Settings UI can show
     * a preview or write the bytes to a content URI via
     * `ContentResolver.openOutputStream`.
     */
    override fun encodeToJson(backup: AuraBackup): String = json.encodeToString(backup)

    /**
     * Constructed here rather than injected, exactly as [BackupWorker] does it.
     *
     * `BackupCrypto`'s only dependency is a `KeyManager` it uses purely as an
     * AES-GCM primitive — every key is passed in as a parameter — and its default
     * is deliberately Keystore-free, because the Keystore is the one place a
     * backup key must not come from. Injecting it would also mean a 76th
     * constructor parameter on this class, which is the hazard BackupService's
     * KDoc exists to record.
     */
    private val crypto = com.aura.security.BackupCrypto()

    override fun isSealed(text: String): Boolean = crypto.isSealed(text)

    override suspend fun unseal(text: String, passphrase: String): String? =
        // PBKDF2 at 210,000 iterations: hundreds of milliseconds, and CPU-bound
        // rather than IO-bound. On the caller's dispatcher this is a dropped-frame
        // storm on the thread that has to draw the dialog it was launched from.
        withContext(Dispatchers.Default) { crypto.open(text, passphrase) }

    /**
     * Parse [bytes] into an [AuraBackup]. Throws if the JSON is
     * unparseable or the schema version is newer than this build.
     */
    override fun decodeFromJson(bytes: String): AuraBackup {
        val parsed = json.decodeFromString<AuraBackup>(bytes)
        require(parsed.schemaVersion <= AuraBackup.SCHEMA_VERSION) {
            "Backup schema version ${parsed.schemaVersion} is newer than " +
                "this build (${AuraBackup.SCHEMA_VERSION}). Upgrade Aura first."
        }
        return parsed
    }

    /**
     * Write [backup] into every persisted table. The order matters:
     * KG nodes must be inserted before edges (foreign-key relationship).
     * Memories and conversations are independent of each other and
     * of KG.
     *
     * [RestoreMode.MERGE] does NOT clear tables first: the upserts make
     * re-importing the same file idempotent, but importing a smaller file
     * after a larger one leaves stale rows. [RestoreMode.REPLACE] runs
     * [purgeAll] first and is the clean-slate path; it refuses to start at all
     * when the pre-restore snapshot could not be taken, because a purge with
     * nothing behind it is unrecoverable.
     *
     * That refusal covers the forward path only, and for a long time nothing
     * covered the failure path: the rollback purged before it checked whether
     * a snapshot existed to restore, so a merge — which never requires a
     * snapshot — could empty all eleven databases on any failed insert. The
     * order is now read-then-purge, which is what makes the guarantee below
     * true for both modes rather than one.
     *
     * Both modes overwrite the preferences
     * the backup carries — a merge is a merge of rows, not of settings, and
     * the confirmation dialog says so.
     *
     * @return the count of rows written per table.
     */
    override suspend fun restore(
        backup: AuraBackup,
        mode: RestoreMode,
    ): RestoreCounts = withContext(Dispatchers.IO) {
        // Spooled to disk, not held in heap: the pre-restore AuraBackup used to
        // stay live for the whole write phase, so the failure most likely to
        // trigger a rollback — an OutOfMemoryError writing a large import — was
        // the one the rollback could not survive.
        val rollbackFile = writeRollbackSnapshot()
        // REPLACE purges before it writes. Doing that with no snapshot behind it
        // is the exact shape this wave exists to remove — the user would be one
        // failed insert away from an empty database and a log line. Refusing is
        // recoverable; purging is not.
        if (mode == RestoreMode.REPLACE && rollbackFile == null) {
            error(
                "Replace-all restore refused: the pre-restore snapshot could not be taken, " +
                    "so a failed import could not be undone. Use \"Add to existing\" instead.",
            )
        }
        markRestoreInProgress(mode, rollbackFile != null, backup.appVersionName)

        // Non-cancellable write phase: restore runs in a ViewModel scope,
        // where a config change used to cancel mid-insert and leave the DB
        // half-imported with no cleanup at all (the CancellationException
        // rethrow skipped the purge).
        val counts = try {
            withContext(kotlinx.coroutines.NonCancellable) {
                try {
                    // Inside the guard, not before it. The purge was the one
                    // statement between taking the snapshot and the catch that
                    // restores from it — so a failure *during the wipe* left the
                    // tables emptied and skipped the rollback entirely, which is
                    // precisely the outcome the KDoc above says this design
                    // exists to prevent ("purging is not [recoverable]").
                    //
                    // Running purgeAll twice is harmless: it is ~60
                    // unconditional DELETEs with no reads between them.
                    if (mode == RestoreMode.REPLACE) purgeAll()
                    writeEverything(backup, mode)
                } catch (e: Throwable) {
                    // Throwable (not just Exception) so OOM / StackOverflow
                    // also trigger the rollback.
                    android.util.Log.e("BackupManager", "restore failed, rolling back to pre-restore data: ${e.message}", e)
                    val rolledBack = try {
                        // Read the spool BEFORE purging, not after.
                        //
                        // Purging first meant that a spool which could not be
                        // read left every table empty with nothing to put back,
                        // and there are ordinary ways for it to be unreadable:
                        // MERGE never required one in the first place — only
                        // REPLACE refuses to start without it — and the write
                        // itself can fail on a full disk, which is one of the
                        // states a large import reaches.
                        //
                        // This used to argue from the spool living in a cache
                        // the system can evict. It does not: `writeRollbackSnapshot`
                        // writes to `filesDir`, which is never reclaimed under
                        // us. The ordering is right for the reasons above, and
                        // a reason that is not true is worse than no reason —
                        // the next person to read it prices the risk wrongly.
                        //
                        // On the merge path that purge was pure loss. Every
                        // delete inside `writeEverything` is guarded on REPLACE,
                        // so a merge only ever adds rows; the wipe destroyed data
                        // the import had not touched, on the operation the
                        // confirmation dialog describes as "nothing is deleted".
                        //
                        // Reading first makes the purge conditional on having
                        // something to restore. When there is nothing, the
                        // half-imported database is left alone: worse than a
                        // clean rollback, better than an empty one, and the
                        // marker below still tells the next launch what happened.
                        val preRestore = readRollbackSnapshot(rollbackFile)
                        if (preRestore != null) {
                            // Tables are purged here rather than before the read,
                            // so the snapshot is known to exist first. REPLACE is
                            // what restoring a snapshot means regardless of the
                            // mode the failed import was running in.
                            purgeAll()
                            writeEverything(preRestore, RestoreMode.REPLACE)
                            true
                        } else {
                            android.util.Log.e(
                                "BackupManager",
                                "no pre-restore snapshot — leaving the database as the failed import left it, " +
                                    "rather than purging what the import never touched",
                            )
                            false
                        }
                    } catch (rollback: Throwable) {
                        android.util.Log.e("BackupManager", "rollback failed — database may be incomplete: ${rollback.message}", rollback)
                        false
                    }
                    // The marker is cleared only when the database is known to
                    // be consistent again. Left in place it is what tells the
                    // next launch that this install is mid-restore.
                    if (rolledBack) clearRestoreInProgress()
                    throw e
                }
            }
        } finally {
            runCatching { rollbackFile?.delete() }
                .onFailure { android.util.Log.w("BackupManager", "rollback spool cleanup failed: ${it.message}", it) }
        }
        clearRestoreInProgress()

        // Post-restore writes stay outside the guarded phase because they
        // touch stores purgeAll never clears — DataStore and filesDir, not
        // Room — so they can never need rolling back, and a DataStore failure
        // must not turn a completed Room restore into a failed one. The user
        // can re-toggle any of it in Settings.
        runCatching { restorePreferences(backup.preferences) }
            .onFailure { android.util.Log.w("BackupManager", "restorePreferences failed (non-fatal): ${it.message}", it) }
        runCatching { usageTracker.restore(backup.usage) }
            .onFailure { android.util.Log.w("BackupManager", "usageTracker restore failed (non-fatal): ${it.message}", it) }
        runCatching { restoreEvolutionPreferences(backup) }
            .onFailure { android.util.Log.w("BackupManager", "evolution preferences restore failed (non-fatal): ${it.message}", it) }
        var toolPolicyCount = 0
        runCatching { toolPolicyCount = restoreToolPolicies(backup) }
            .onFailure { android.util.Log.w("BackupManager", "restoreToolPolicies failed (non-fatal): ${it.message}", it) }
        var consciousnessCount = 0
        runCatching { consciousnessCount = restoreConsciousness(backup) }
            .onFailure { android.util.Log.w("BackupManager", "restoreConsciousness failed (non-fatal): ${it.message}", it) }

        // Backups carry no embedding vectors — `MemoryBackup` has no such field
        // and `toEntity` writes `embedding = null` — so every restored memory
        // lands unembedded and contributes nothing to vector recall until
        // something rebuilds it.
        //
        // Nothing here used to. `ReembedWorker` existed and was correct, but its
        // only callers were the embedding-model workers and app start, so the
        // rebuild happened on the next cold launch by accident rather than on
        // the restore that created the need. Meanwhile this file and the backup
        // dialog both told the user to go and tap Rebuild in Settings, which
        // made the manual path the documented one for a job the app can start
        // itself. `enqueueUniqueWork` is KEEP, so this is a no-op when a rebuild
        // is already pending.
        //
        // Non-fatal by the same rule as everything else in this block: a
        // WorkManager failure must not turn a completed Room restore into a
        // failed one.
        runCatching { com.aura.memory.ReembedWorker.enqueue(context) }
            .onFailure { android.util.Log.w("BackupManager", "re-embed enqueue after restore failed (non-fatal): ${it.message}", it) }

        counts.copy(toolPolicies = toolPolicyCount, consciousness = consciousnessCount)
    }

    /**
     * Write every table [purgeAll] can clear, from [backup]. Both the forward
     * restore and the rollback go through here.
     *
     * They used not to. The rollback ran `purgeAll()` and then
     * `writeRows(preRestore)`, and `writeRows` restores none of what `purgeAll`
     * clears through `evolutionProposalDao`, `evolutionRevisionDao`,
     * `evolutionSettingsDao`, `strategyBanditDao`, `agentStateDao`,
     * `agentRelationshipDao`, `agentObservationDao`, `forumPostDao`,
     * `forumVoteDao` and `agentDao.deleteAllCustom`. The three restorers that
     * do cover them ran on the success path only, and took the incoming file
     * rather than the pre-restore snapshot — so a failed import destroyed the
     * user's evolution history, learned strategy weights and entire council,
     * permanently, and reported only the error that started it.
     */
    private suspend fun writeEverything(backup: AuraBackup, mode: RestoreMode): RestoreCounts {
        val counts = writeRows(backup)
        val unreadable = restoreEvolutionRows(backup)
        // The two below stay guarded, unlike everything above, and the reason is
        // specific rather than caution. `restoreCouncil` deletes and re-inserts
        // forum posts whose primary key is `autoGenerate`, and `ForumPostBackup`
        // carries no id — so the votes it writes next still hold the postIds the
        // posts had on the exporting device. Wherever those ids do not line up,
        // `forum_votes.postId` violates its foreign key. Today that costs the
        // user their council and nothing else; promoting it into the fatal path
        // before forum posts have a stable id would cost them the whole restore
        // and roll back a Room import that had already succeeded. Calling them
        // from here still gets the council and the learned weights back on the
        // rollback path, which is the loss this method exists to close.
        // Only when replacing everything. A merge that wiped the profile because the backup
        // happened not to carry one is a silent loss of identity, on an import the dialog
        // describes as additive.
        if (mode == RestoreMode.REPLACE && backup.userProfile == null) {
            runCatching { userProfileDao.deleteAll() }
                .onFailure { android.util.Log.w("BackupManager", "profile clear failed: ${it.message}", it) }
        }
        runCatching { restoreStrategyBandit(backup, mode) }
            .onFailure { android.util.Log.w("BackupManager", "strategy bandit write failed: ${it.message}", it) }
        runCatching { restoreCouncil(backup, mode) }
            .onFailure { android.util.Log.w("BackupManager", "council write failed: ${it.message}", it) }
        return counts.copy(evolutionRevisionsUnreadable = unreadable)
    }

    /**
     * Serialise the pre-restore state to a file, or null when it could not be
     * taken. Called with `strict = true` — see [snapshot].
     *
     * This is spool-to-disk, not streaming: [encodeToJson] still builds the
     * whole string. The win is that the string is transient and the decoded
     * `AuraBackup` is dropped before the write phase begins, so the phase
     * holds one backup instead of two.
     */
    private suspend fun writeRollbackSnapshot(): File? = runCatching {
        val backup = snapshot(appVersionName = ROLLBACK_VERSION_TAG, strict = true)
        val file = File(context.filesDir, ROLLBACK_FILE_NAME)
        file.bufferedWriter().use { it.write(encodeToJson(backup)) }
        file
    }.onFailure {
        android.util.Log.w("BackupManager", "pre-restore snapshot failed (rollback unavailable): ${it.message}", it)
    }.getOrNull()

    private fun readRollbackSnapshot(file: File?): AuraBackup? {
        if (file == null || !file.exists()) return null
        return runCatching { decodeFromJson(file.readText()) }
            .onFailure { android.util.Log.e("BackupManager", "rollback snapshot unreadable: ${it.message}", it) }
            .getOrNull()
    }

    /**
     * What a restore that never finished left behind. See
     * [consumeInterruptedRestore].
     */
    @kotlinx.serialization.Serializable
    data class InterruptedRestore(
        val startedAt: Long,
        val mode: String,
        val sourceVersion: String,
        val rollbackAvailable: Boolean,
    )

    private val restoreMarkerFile: File get() = File(context.filesDir, RESTORE_MARKER_FILE_NAME)

    /**
     * Record that a restore is underway.
     *
     * [writeEverything] is about forty-five independent transactions across
     * eleven databases with no envelope around them, so process death partway
     * through leaves a database that is half this backup and half whatever was
     * there before — and nothing said so. The marker outlives the process; the
     * next launch reads it and can tell the user their data is in a state
     * neither they nor the app chose.
     */
    private fun markRestoreInProgress(mode: RestoreMode, rollbackAvailable: Boolean, sourceVersion: String) {
        runCatching {
            restoreMarkerFile.writeText(
                json.encodeToString(
                    InterruptedRestore.serializer(),
                    InterruptedRestore(System.currentTimeMillis(), mode.name, sourceVersion, rollbackAvailable),
                ),
            )
        }.onFailure { android.util.Log.w("BackupManager", "restore marker write failed: ${it.message}", it) }
    }

    private fun clearRestoreInProgress() {
        runCatching { if (restoreMarkerFile.exists()) restoreMarkerFile.delete() }
            .onFailure { android.util.Log.w("BackupManager", "restore marker clear failed: ${it.message}", it) }
    }

    /** Read the marker without clearing it. For startup logging. */
    fun peekInterruptedRestore(): InterruptedRestore? {
        if (!restoreMarkerFile.exists()) return null
        return runCatching { json.decodeFromString(InterruptedRestore.serializer(), restoreMarkerFile.readText()) }
            .onFailure { android.util.Log.w("BackupManager", "restore marker unreadable: ${it.message}", it) }
            .getOrNull()
            ?: InterruptedRestore(0L, "UNKNOWN", "", rollbackAvailable = false)
    }

    /**
     * Read and clear the marker. An unreadable marker still returns a value:
     * the file existing at all is the signal, and reporting nothing because the
     * body failed to parse would hide exactly the case it exists to catch.
     */
    override fun consumeInterruptedRestore(): InterruptedRestore? {
        val pending = peekInterruptedRestore() ?: return null
        clearRestoreInProgress()
        return pending
    }

    /**
     * Delete `aura-backup-*.json` files in the cache older than
     * [EXPORT_RETENTION_MS], returning how many went.
     *
     * Every export writes a full plaintext copy of the database —
     * conversations, memories, profile, preferences — into `cacheDir`, and
     * nothing ever removed it. One complete copy accumulated per export, for
     * the life of the install, readable by anything holding the app's uid.
     */
    override fun pruneCacheExports(now: Long): Int = runCatching {
        val stale = context.cacheDir?.listFiles()?.filter { file ->
            file.isFile &&
                file.name.startsWith("aura-backup-") &&
                file.name.endsWith(".json") &&
                now - file.lastModified() > EXPORT_RETENTION_MS
        }.orEmpty()
        stale.count { it.delete() }
    }.onFailure { android.util.Log.w("BackupManager", "cache export prune failed: ${it.message}", it) }
        .getOrDefault(0)

    private suspend fun restoreToolPolicies(backup: AuraBackup): Int {
        val store = toolPolicyStore ?: return 0
        if (backup.toolPolicies.isEmpty()) return 0
        store.replaceAll(backup.toolPolicies.associate { it.toolName to it.toPolicy() })
        return backup.toolPolicies.size
    }

    /**
     * Write back whichever of the five consciousness stores the backup carries
     * and this build has wired, returning how many were written.
     */
    private suspend fun restoreConsciousness(backup: AuraBackup): Int {
        val c = backup.consciousness ?: return 0
        var restored = 0
        val narrative = c.narrative
        val ns = narrativeSelf
        if (narrative != null && ns != null) { ns.restore(narrative.toState()); restored++ }
        val im = intrinsicMotivation
        if (c.drives.isNotEmpty() && im != null) {
            im.restore(c.drives.mapNotNull { it.toStateOrNull() })
            restored++
        }
        val userModel = c.userModel
        val tom = theoryOfMind
        if (userModel != null && tom != null) { tom.restore(userModel.toModel()); restored++ }
        val emotion = c.emotion
        val ee = emotionEngine
        if (emotion != null && ee != null) { ee.restore(emotion.toSnapshot()); restored++ }
        val affinity = c.affinity
        val at = affinityTracker
        if (affinity != null && at != null) { at.restoreRaw(affinity.score, affinity.lastInteractionAt); restored++ }
        return restored
    }

    /**
     * Every entity list a restore writes, mapped up front.
     *
     * `writeRows` used to map all of this into locals and then write it, in one
     * method, and the mapping had to come first: a `toEntity` that throws must
     * not leave the database half-imported. That invariant is why this is a
     * class rather than per-database mapping — constructing it does all the
     * mapping and no database work, so a mapping failure still happens before
     * the first insert.
     *
     * It is also what let `writeRows` be split at all. That method carried a
     * comment saying it "is already at the JVM 64KB ceiling and cannot take
     * another parameter", which is a real limit and was one schema version away
     * from being hit.
     */
    private class RestoreRows(backup: AuraBackup) {

    // Build ALL entity rows first (no DB calls) so a mapping
    // failure can't leave the DB half-imported.
    val memRows = backup.memories.map { it.toEntity() }
    val editRows = backup.memoryEdits.map { it.toEntity() }
    val documentRows = backup.documents.map { it.toEntity() }
    val creativeRows = backup.creativeProjects.map { it.toEntity() }
    val convRows = backup.conversations.map { it.toEntity() }
    val nodeRows = backup.knowledgeGraph.nodes.map { it.toEntity() }
    val edgeRows = backup.knowledgeGraph.edges.map { it.toEntity() }
    val handRows = backup.hands.map { it.toEntity() }
    val handRunRows = backup.handRuns.map { it.toEntity() }
    val taskRows = backup.tasks.map { it.toEntity() }
    val reminderRows = backup.reminders.map { it.toEntity() }
    val proactiveRows = backup.proactiveEvents.map { it.toEntity() }
    val profileRow = backup.userProfile?.toEntity()
    val agentRows = backup.agents.map { it.toEntity() }
    // Schema v10: world model + creative + taste. Until v0.30.x these
    // were silently dropped on restore because the toEntity() mappers
    // and the DAOs' insertAll() methods didn't exist.
    val beliefRows = backup.beliefs.map { it.toEntity() }
    val evidenceRows = backup.evidence.map { it.toEntity() }
    val worldEventRows = backup.worldEvents.map { it.toEntity() }
    val opportunityRows = backup.opportunities.map { it.toEntity() }
    val creativeArtifactRows = backup.creativeArtifacts.map { it.toEntity() }
    val creativeRevisionRows = backup.creativeRevisions.map { it.toEntity() }
    val creativeBranchRows = backup.creativeBranches.map { it.toEntity() }
    val canonFactRows = backup.canonFacts.map { it.toEntity() }
    val proactiveOutcomeRows = backup.proactiveOutcomes.map { it.toEntity() }
    val livingWorldRows = backup.livingWorlds.map { it.toEntity() }
    val livingEventRows = backup.livingEvents.map { it.toEntity() }
    val correctionRows = backup.corrections.map { it.toEntity() }
    val openQuestionRows = backup.openQuestions.map { it.toEntity() }
    val placeVisitRows = backup.placeVisits.map { it.toEntity() }
    val creativeAnalysisRows = backup.creativeAnalysis.map { it.toEntity() }
    val preferenceSignalRows = backup.preferenceSignals.map { it.toEntity() }
    val styleProfileRows = backup.styleProfiles.map { it.toEntity() }
    // Schema v11: dream database.
    val dreamSummaryRows = backup.dreamSummaries.map { it.toEntity() }
    val routineRows = backup.routines.map { it.toEntity() }
    val contradictionRows = backup.contradictions.map { it.toEntity() }
    val kgEdgeProposalRows = backup.kgEdgeProposals.map { it.toEntity() }
    // Schema v12 rows.
    val memoryFeedbackRows = backup.memoryFeedback.map { it.toEntity() }
    val retrievalLabelRows = backup.retrievalLabels.map { it.toEntity() }
    val projectRows = backup.projects.map { it.toEntity() }
    val projectNoteRows = backup.projectNotes.map { it.toEntity() }
    val claimResolutionRows = backup.claimResolutions.map { it.toEntity() }
    val documentChunkRows = backup.documentChunks.map { it.toEntity() }
    val referenceIdentityRows = backup.referenceIdentities.map { it.toEntity() }
    val agentRunRows = backup.agentRuns.map { it.toEntity() }
    val goalRows = backup.agentGoals.map { it.toEntity() }
    val stepRows = backup.agentSteps.map { it.toEntity() }
    val agentEventRows = backup.agentEvents.map { it.toEntity() }
    val agentApprovalRows = backup.agentApprovals.map { it.toEntity() }
    val runCheckpointRows = backup.runCheckpoints.map { it.toEntity() }
    val artifactDependencyRows = backup.artifactDependencies.map { it.toEntity() }
    val continuityIssueRows = backup.continuityIssues.map { it.toEntity() }
    val creativeSimulationRows = backup.creativeSimulations.map { it.toEntity() }
    val evolutionEvidenceRows = backup.evolutionEvidence.map { it.toEntity() }
    val evolutionCandidateRows = backup.evolutionCandidates.map { it.toEntity() }
    val proactiveInteractionRows = backup.proactiveInteractions.map { it.toEntity() }
    val routingOutcomeRows = backup.routingOutcomes.map { it.toEntity() }
    }

    /**
     * Map + insert every table from [backup].
     *
     * No internal error handling — [restore] owns the snapshot and rollback
     * around this. Writes are grouped by database and each group runs in its own
     * transaction, so a failure leaves each database either fully written or
     * untouched rather than arbitrary. Order inside a group is unchanged and
     * still matters: KG nodes before edges, worlds before their events, projects
     * before their notes, proactive events before their interactions.
     */
    private suspend fun writeRows(backup: AuraBackup): RestoreCounts {
        val rows = RestoreRows(backup)

        transactions?.memory { writeMemoryRows(rows) } ?: writeMemoryRows(rows)
        transactions?.conversations { writeConversationRows(rows) } ?: writeConversationRows(rows)
        transactions?.hands { writeHandRows(rows) } ?: writeHandRows(rows)
        transactions?.tasks { writeTaskRows(rows) } ?: writeTaskRows(rows)
        transactions?.proactive { writeProactiveRows(rows) } ?: writeProactiveRows(rows)
        transactions?.dreams { writeDreamRows(rows) } ?: writeDreamRows(rows)
        transactions?.agentRuns { writeAgentRunRows(rows) } ?: writeAgentRunRows(rows)
        transactions?.evolution { writeEvolutionRows(rows) } ?: writeEvolutionRows(rows)
        transactions?.profile { writeProfileRow(rows) } ?: writeProfileRow(rows)
        transactions?.agents { writeAgentRows(rows) } ?: writeAgentRows(rows)

        // Outside every transaction, and last.
        //
        // Both of these enqueue WorkManager jobs as a side effect of writing a
        // row — `handScheduler.schedule` directly, and `restoreReminders`
        // through `ReminderScheduler`, which inserts and then enqueues. A SQLite
        // rollback cannot un-enqueue a job, so a scheduler call inside a
        // transaction can outlive the row that justified it. Running them after
        // the writes commit means the jobs only ever exist for rows that do.
        rows.handRows.forEach { hand ->
            runCatching { handScheduler.schedule(hand) }
                .onFailure { android.util.Log.w("BackupManager", "hand schedule failed: " + it.message, it) }
        }
        restoreReminders(rows.reminderRows)

        return countsFor(backup, rows)
    }

    /**
     * Row counts for the restore report.
     *
     * Reads the mapped lists rather than — say — `backup.memories.size`,
     * which would be the same number today and would stop being the same
     * number the moment any mapper filters. `agents` is deliberately the
     * unfiltered count: builtins are re-seeded rather than restored, so the
     * number of agents the file carried is the honest figure to report.
     */
    private fun countsFor(backup: AuraBackup, r: RestoreRows): RestoreCounts {
        return RestoreCounts(
            memories = r.memRows.size,
            memoryEdits = r.editRows.size,
            documents = r.documentRows.size,
            creativeProjects = r.creativeRows.size,
            conversations = r.convRows.size,
            nodes = r.nodeRows.size,
            edges = r.edgeRows.size,
            hands = r.handRows.size,
            handRuns = r.handRunRows.size,
            tasks = r.taskRows.size,
            reminders = r.reminderRows.size,
            proactiveEvents = r.proactiveRows.size,
            profile = if (r.profileRow != null) 1 else 0,
            evolutionProposals = backup.evolutionProposals.size,
            evolutionSettings = backup.evolutionSettings.size,
            evolutionRevisions = backup.evolutionRevisions.size,
            agents = r.agentRows.size,
            // Schema v10: previously missing from RestoreCounts entirely.
            beliefs = r.beliefRows.size,
            evidence = r.evidenceRows.size,
            worldEvents = r.worldEventRows.size,
            opportunities = r.opportunityRows.size,
            creativeArtifacts = r.creativeArtifactRows.size,
            creativeRevisions = r.creativeRevisionRows.size,
            creativeBranches = r.creativeBranchRows.size,
            canonFacts = r.canonFactRows.size,
            proactiveOutcomes = r.proactiveOutcomeRows.size,
            livingWorlds = r.livingWorldRows.size,
            livingEvents = r.livingEventRows.size,
            corrections = r.correctionRows.size,
            openQuestions = r.openQuestionRows.size,
            placeVisits = r.placeVisitRows.size,
            creativeAnalysis = r.creativeAnalysisRows.size,
            preferenceSignals = r.preferenceSignalRows.size,
            styleProfiles = r.styleProfileRows.size,
            // Schema v11: dream database.
            dreamSummaries = r.dreamSummaryRows.size,
            routines = r.routineRows.size,
            contradictions = r.contradictionRows.size,
            kgEdgeProposals = r.kgEdgeProposalRows.size,
            // Schema v12: durable state previously dropped on backup/restore.
            memoryFeedback = r.memoryFeedbackRows.size,
            retrievalLabels = r.retrievalLabelRows.size,
            projects = r.projectRows.size,
            projectNotes = r.projectNoteRows.size,
            claimResolutions = r.claimResolutionRows.size,
            documentChunks = r.documentChunkRows.size,
            referenceIdentities = r.referenceIdentityRows.size,
            agentRuns = r.agentRunRows.size,
            agentGoals = r.goalRows.size,
            agentSteps = r.stepRows.size,
            agentEvents = r.agentEventRows.size,
            agentApprovals = r.agentApprovalRows.size,
            runCheckpoints = r.runCheckpointRows.size,
            // Schema v13.
            artifactDependencies = r.artifactDependencyRows.size,
            continuityIssues = r.continuityIssueRows.size,
            creativeSimulations = r.creativeSimulationRows.size,
            evolutionEvidence = r.evolutionEvidenceRows.size,
            evolutionCandidates = r.evolutionCandidateRows.size,
            proactiveInteractions = r.proactiveInteractionRows.size,
            routingOutcomes = r.routingOutcomeRows.size,
        )
    }

    private suspend fun writeMemoryRows(r: RestoreRows) {
        if (r.memRows.isNotEmpty() && r.editRows.isNotEmpty()) memoryDao.insertAllWithEdits(r.memRows, r.editRows)
        else if (r.memRows.isNotEmpty()) memoryDao.insertAll(r.memRows)
        else if (r.editRows.isNotEmpty()) memoryEditDao.insertAll(r.editRows)
        if (r.documentRows.isNotEmpty()) documentDao.insertAll(r.documentRows)
        if (r.creativeRows.isNotEmpty()) creativeProjectDao.insertAll(r.creativeRows)
        // Nodes before edges: an edge whose node does not exist yet is rejected.
        if (r.nodeRows.isNotEmpty()) kgDao.insertAllNodes(r.nodeRows)
        if (r.edgeRows.isNotEmpty()) kgDao.insertAllEdges(r.edgeRows)
        if (r.beliefRows.isNotEmpty()) beliefDao?.insertAll(r.beliefRows)
        if (r.evidenceRows.isNotEmpty()) evidenceDao?.insertAll(r.evidenceRows)
        if (r.worldEventRows.isNotEmpty()) worldEventDao?.insertAll(r.worldEventRows)
        if (r.opportunityRows.isNotEmpty()) opportunityDao?.insertAll(r.opportunityRows)
        if (r.creativeArtifactRows.isNotEmpty()) creativeArtifactDao?.insertAll(r.creativeArtifactRows)
        if (r.creativeRevisionRows.isNotEmpty()) creativeRevisionDao?.insertAll(r.creativeRevisionRows)
        if (r.creativeBranchRows.isNotEmpty()) creativeBranchDao?.insertAll(r.creativeBranchRows)
        if (r.canonFactRows.isNotEmpty()) canonFactDao?.upsertAll(r.canonFactRows)
        // Worlds strictly before their events: living_events carries a foreign
        // key onto living_worlds, so the reverse order drops every event.
        if (r.livingWorldRows.isNotEmpty()) livingWorldDao?.upsertAll(r.livingWorldRows)
        if (r.livingEventRows.isNotEmpty()) livingEventDao?.upsertAll(r.livingEventRows)
        if (r.correctionRows.isNotEmpty()) correctionDao?.insertAll(r.correctionRows)
        if (r.openQuestionRows.isNotEmpty()) openQuestionDao?.insertAll(r.openQuestionRows)
        if (r.placeVisitRows.isNotEmpty()) placeVisitDao?.insertAll(r.placeVisitRows)
        // After revisions: the FK is ON DELETE CASCADE, so an analysis row
        // inserted before its revision exists is rejected outright.
        if (r.creativeAnalysisRows.isNotEmpty()) creativeAnalysisDao?.insertAll(r.creativeAnalysisRows)
        if (r.preferenceSignalRows.isNotEmpty()) preferenceSignalDao?.insertAll(r.preferenceSignalRows)
        if (r.styleProfileRows.isNotEmpty()) styleProfileDao?.insertAll(r.styleProfileRows)
        // Schema v12: restore durable state previously dropped on roundtrip.
        if (r.memoryFeedbackRows.isNotEmpty()) memoryFeedbackDao?.insertAll(r.memoryFeedbackRows)
        if (r.retrievalLabelRows.isNotEmpty()) retrievalLabelDao?.upsertAll(r.retrievalLabelRows)
        // Projects before their notes: `project_notes` carries a CASCADE foreign
        // key into `projects`, and Room restores with `PRAGMA foreign_keys = ON`,
        // so the reverse order rejects every note and loses the whole ledger.
        if (r.projectRows.isNotEmpty()) projectDao?.upsertAll(r.projectRows)
        if (r.projectNoteRows.isNotEmpty()) projectNoteDao?.upsertAll(r.projectNoteRows)
        // After beliefs above: claim_resolutions CASCADEs into `beliefs`, and
        // Room restores with foreign keys on, so the reverse order rejects every
        // verdict — the one table in the export nothing could reproduce.
        if (r.claimResolutionRows.isNotEmpty()) claimResolutionDao?.upsertAll(r.claimResolutionRows)
        if (r.documentChunkRows.isNotEmpty()) documentChunkDao?.insertAll(r.documentChunkRows)
        if (r.referenceIdentityRows.isNotEmpty()) referenceIdentityDao?.insertAll(r.referenceIdentityRows)
        // Schema v13. Creative rows depend on artifacts and projects, above.
        if (r.artifactDependencyRows.isNotEmpty()) artifactDependencyDao?.insertAll(r.artifactDependencyRows)
        if (r.continuityIssueRows.isNotEmpty()) continuityIssueDao?.insertAll(r.continuityIssueRows)
        if (r.creativeSimulationRows.isNotEmpty()) creativeSimulationDao?.insertAll(r.creativeSimulationRows)
        if (r.routingOutcomeRows.isNotEmpty()) routingOutcomeDao?.insertAll(r.routingOutcomeRows)
    }

    private suspend fun writeConversationRows(r: RestoreRows) {
        if (r.convRows.isNotEmpty()) conversationDao.insertAll(r.convRows)
    }

    private suspend fun writeHandRows(r: RestoreRows) {
        // The WorkManager side of this runs after the transaction commits.
        if (r.handRows.isNotEmpty()) handDao.insertAll(r.handRows)
        if (r.handRunRows.isNotEmpty()) handDao.insertAllRuns(r.handRunRows)
    }

    private suspend fun writeTaskRows(r: RestoreRows) {
        // Reminders are not here: they schedule as they insert, so they run
        // outside the transaction with the hand schedules.
        if (r.taskRows.isNotEmpty()) taskDao.insertAll(r.taskRows)
    }

    private suspend fun writeProactiveRows(r: RestoreRows) {
        if (r.proactiveOutcomeRows.isNotEmpty()) proactiveOutcomeDao?.insertAll(r.proactiveOutcomeRows)
        // Events before interactions: proactive_interactions references them.
        if (r.proactiveRows.isNotEmpty()) proactiveEventDao.insertAll(r.proactiveRows)
        if (r.proactiveInteractionRows.isNotEmpty()) proactiveInteractionDao?.insertAll(r.proactiveInteractionRows)
    }

    private suspend fun writeDreamRows(r: RestoreRows) {
        // Schema v11: dream database.
        if (r.dreamSummaryRows.isNotEmpty()) dreamSummaryDao?.insertAll(r.dreamSummaryRows)
        if (r.routineRows.isNotEmpty()) routineDao?.insertAll(r.routineRows)
        if (r.contradictionRows.isNotEmpty()) contradictionDao?.insertAll(r.contradictionRows)
        if (r.kgEdgeProposalRows.isNotEmpty()) kgEdgeProposalDao?.insertAll(r.kgEdgeProposalRows)
    }

    private suspend fun writeAgentRunRows(r: RestoreRows) {
        if (r.goalRows.isNotEmpty()) goalDao?.insertAll(r.goalRows)
        if (r.agentRunRows.isNotEmpty()) agentRunDao?.insertAll(r.agentRunRows)
        if (r.stepRows.isNotEmpty()) stepDao?.upsertAll(r.stepRows)
        if (r.agentEventRows.isNotEmpty()) agentEventDao?.insertAll(r.agentEventRows)
        if (r.agentApprovalRows.isNotEmpty()) approvalRequestDao?.insertAll(r.agentApprovalRows)
        if (r.runCheckpointRows.isNotEmpty()) runCheckpointDao?.upsertAll(r.runCheckpointRows)
    }

    private suspend fun writeEvolutionRows(r: RestoreRows) {
        if (r.evolutionEvidenceRows.isNotEmpty()) evolutionEvidenceDao?.insertAll(r.evolutionEvidenceRows)
        if (r.evolutionCandidateRows.isNotEmpty()) evolutionCandidateDao?.insertAll(r.evolutionCandidateRows)
    }

    private suspend fun writeProfileRow(r: RestoreRows) {
        // Writing only. Clearing a profile the backup does not carry depends on
        // the restore mode and lives in writeEverything with the other
        // mode-dependent deletes.
        r.profileRow?.let { userProfileDao.upsert(it) }
    }

    private suspend fun writeAgentRows(r: RestoreRows) {
        // Builtins are re-seeded on startup, so only non-builtin entries from
        // the backup are inserted.
        val customAgents = r.agentRows.filter { !it.isBuiltin }
        if (customAgents.isNotEmpty()) agentDao.insertAll(customAgents)
    }

    private suspend fun restorePreferences(p: PreferencesBackup) {
        p.defaultModel?.let { userPreferences.setDefaultModel(it) }
        userPreferences.setAppLockEnabled(p.appLockEnabled)
        userPreferences.setFirstRunComplete(p.firstRunComplete)
        p.embeddingModel?.let { providerKeys.setEmbeddingModel(it) }
        userPreferences.setLastSeenProactiveAt(p.lastSeenProactiveAt)
        userPreferences.setMorningBriefEnabled(p.morningBriefEnabled)
        userPreferences.setCalendarMonitorEnabled(p.calendarMonitorEnabled)
        userPreferences.setTtsEnabled(p.ttsEnabled)
        userPreferences.setIncognitoDefault(p.incognitoDefault)
        userPreferences.setThemeMode(p.themeMode)
        userPreferences.setCustomIdentity(p.customIdentity)
        userPreferences.setSpecialistOverrides(p.specialistOverrides)
        userPreferences.setMorningBriefHour(p.morningBriefHour)
        userPreferences.setSpecialistToolOverrides(p.specialistToolOverrides)
        p.visionModel?.let { userPreferences.setVisionModel(it) }
        p.backgroundModel?.let { userPreferences.setBackgroundModel(it) }
        p.deepModeModel?.let { userPreferences.setDeepModeModel(it) }
        if (p.moaReferenceModels.isNotBlank()) {
            userPreferences.setMoaReferenceModels(p.moaReferenceModels.split(",").filter { it.isNotBlank() })
        }
        p.moaAggregatorModel?.let { userPreferences.setMoaAggregatorModel(it) }
        p.imageModel?.takeIf { it.isNotBlank() }?.let { userPreferences.setImageModel(it) }
        p.videoModel?.takeIf { it.isNotBlank() }?.let { userPreferences.setVideoModel(it) }
        p.voiceModel?.takeIf { it.isNotBlank() }?.let { userPreferences.setVoiceModel(it) }
        if (p.mcpServersJson.isNotBlank() && p.mcpServersJson != "[]") {
            userPreferences.setMcpServersJson(p.mcpServersJson)
        }
        userPreferences.setEvolutionOnboardingShown(p.evolutionOnboardingShown)
        userPreferences.setDaemonEnabled(p.daemonEnabled)
        userPreferences.setReasoningEnabled(p.reasoningEnabled)
        userPreferences.setReasoningBudget(p.reasoningBudget)
        if (p.googleClientId.isNotBlank()) userPreferences.setGoogleClientId(p.googleClientId)
        if (p.microsoftClientId.isNotBlank()) userPreferences.setMicrosoftClientId(p.microsoftClientId)
        p.fastModel?.let { userPreferences.setRoleModel(com.aura.providers.ModelRole.FAST, it) }
        p.reasoningModel?.let { userPreferences.setRoleModel(com.aura.providers.ModelRole.REASONING, it) }
        p.creativeDraftModel?.let { userPreferences.setRoleModel(com.aura.providers.ModelRole.CREATIVE_DRAFT, it) }
        p.creativeCriticModel?.let { userPreferences.setRoleModel(com.aura.providers.ModelRole.CREATIVE_CRITIC, it) }
        p.plannerModel?.let { userPreferences.setRoleModel(com.aura.providers.ModelRole.PLANNER, it) }
        p.verifierModel?.let { userPreferences.setRoleModel(com.aura.providers.ModelRole.VERIFIER, it) }
        p.evolutionModel?.let { userPreferences.setRoleModel(com.aura.providers.ModelRole.EVOLUTION, it) }
        userPreferences.setDreamEnabled(p.dreamEnabled)
        userPreferences.setDecayEnabled(p.decayEnabled)
        userPreferences.setTriggersEnabled(p.triggersEnabled)
        if (p.triggersJson.isNotBlank() && p.triggersJson != "[]") {
            runCatching {
                val triggers = kotlinx.serialization.json.Json.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(com.aura.triggers.Trigger.serializer()),
                    p.triggersJson,
                )
                userPreferences.setTriggers(triggers)
            }.onFailure { android.util.Log.w("BackupManager", "failed to restore triggers: ${it.message}", it) }
        }
        userPreferences.setPlanningEnabled(p.planningEnabled)
        if (p.defaultAgentId.isNotBlank()) userPreferences.setAgentId(p.defaultAgentId)
        userPreferences.setCouncilEnabled(p.councilEnabled)
        userPreferences.setCouncilAutoApply(p.councilAutoApply)
        userPreferences.setCouncilActivityLevel(p.councilActivityLevel)
        // Restore SMTP config (host, port, username, from — password stays in SecureDataStore)
        if (!p.smtpHost.isNullOrBlank()) {
            userPreferences.setSmtpConfig(
                host = p.smtpHost,
                port = p.smtpPort,
                username = p.smtpUsername ?: "",
                password = "", // Password lives in SecureDataStore — not in backup JSON
                from = p.smtpFrom ?: "",
            )
        }
    }

    /**
     * Write the evolution tables, returning how many revision snapshots were
     * kept as history but cleared as revert sources.
     *
     * `snapshotCiphertext` is Keystore ciphertext. Carried to another install
     * it decrypts to null, `EvolutionSkillRevisionStore.latest` returns null,
     * and the revert button does nothing and says nothing — so a restored
     * revision that looks revertible is a lie the UI has no way to detect.
     * Blanking the field makes the row honest: the summary and metadata still
     * read as history, and the revert path sees no snapshot because there
     * genuinely is none.
     *
     * Blanking happens only when the file carries a canary and that canary
     * fails to decrypt, never when it carries none. Every backup written
     * before schema v18 has no canary, and so does every rollback spool taken
     * in the moment the Keystore refused to encrypt one — treating absence as
     * proof of foreignness would destroy the revert sources of exactly the
     * restores that are legitimately local.
     */
    private suspend fun restoreEvolutionRows(backup: AuraBackup): Int {
        backup.evolutionProposals.map { it.toEntity() }.let { rows ->
            if (rows.isNotEmpty()) evolutionProposalDao.insertAll(rows)
        }
        val foreignInstall = !backup.keyCanary.isNullOrBlank() && !canaryMatchesThisInstall(backup.keyCanary)
        val revisions = backup.evolutionRevisions.map { it.toEntity() }
        val unreadable = if (foreignInstall) revisions.count { it.snapshotCiphertext.isNotBlank() } else 0
        val rows = if (foreignInstall) revisions.map { it.copy(snapshotCiphertext = "") } else revisions
        if (rows.isNotEmpty()) evolutionRevisionDao.insertAll(rows)
        if (unreadable > 0) {
            android.util.Log.w(
                "BackupManager",
                "$unreadable evolution revision snapshot(s) were encrypted by another install; " +
                    "kept as history, cleared as revert sources",
            )
        }
        backup.evolutionSettings.forEach { settings ->
            evolutionSettingsDao.upsert(settings.toEntity())
        }
        return unreadable
    }

    /**
     * The two evolution toggles. Split out of the row write because they land
     * in DataStore, which [purgeAll] never clears and so never needs rolling
     * back — keeping them here preserves their non-fatal handling.
     */
    private suspend fun restoreEvolutionPreferences(backup: AuraBackup) {
        userPreferences.setEvolutionEnabled(backup.preferences.evolutionEnabled)
        userPreferences.setEvolutionIntervalHours(backup.preferences.evolutionIntervalHours)
    }

    private suspend fun restoreReminders(rows: List<ReminderEntity>) {
        val now = System.currentTimeMillis()
        rows.forEach { row ->
            if (row.status != "scheduled") {
                reminderDao.insert(row.copy(workId = ""))
                return@forEach
            }
            val nextTrigger = if (row.triggerAt > now) {
                row.triggerAt
            } else {
                ReminderRecurrence.nextTrigger(row.triggerAt, row.recurrence, now)
            }
            if (nextTrigger == null) {
                reminderDao.insert(
                    row.copy(workId = "", status = "fired", firedAt = row.firedAt ?: now),
                )
            } else {
                reminderScheduler.schedule(
                    row.copy(workId = "", triggerAt = nextTrigger, status = "scheduled"),
                )
            }
        }
    }

    /**
     * Drop everything. Used by the "Restore from backup" flow when
     * the user explicitly wants a clean slate before re-importing.
     * Each table is wiped by id; cascade relationships (KG edges
     * for a node) are torn down by deleting all edges first.
     */
    suspend fun purgeAll() = withContext(Dispatchers.IO) {
        // Grouped by database, and each group inside its own transaction.
        //
        // This was ~65 unguarded DELETEs in source order, hopping between
        // databases as the schema grew. A failure partway through left some
        // tables emptied and others not, in whatever arrangement the ordering
        // happened to produce, and the only record was a log line. Per-database
        // transactions make each of the eleven all-or-nothing. They do not make
        // the purge as a whole atomic — see [RestoreTransactions].
        //
        // Relative order inside each group is exactly what it was. Every
        // ordering comment below survived the regrouping because none of them
        // crossed a database: Room foreign keys cannot span database files, so
        // no cross-database order here was ever load-bearing.

        // Hoisted out of the transactions entirely. This is the one read in the
        // method and the only WorkManager call, and neither belongs inside a
        // SQLite transaction: a rollback cannot un-cancel a scheduled job, so
        // doing it first makes the failure mode "jobs cancelled, rows kept",
        // which is recoverable, rather than "rows gone, jobs still scheduled".
        runCatching { handDao.getAll().map { it.id } }
            .onFailure { android.util.Log.w("BackupManager", "hand cancel enumeration failed: ${it.message}", it) }
            .getOrDefault(emptyList())
            .forEach { id ->
                runCatching { handScheduler.cancel(id) }
                    .onFailure { android.util.Log.w("BackupManager", "hand cancel failed: ${it.message}", it) }
            }

        transactions?.memory { purgeMemory() } ?: purgeMemory()
        transactions?.conversations { purgeConversations() } ?: purgeConversations()
        transactions?.hands { purgeHands() } ?: purgeHands()
        transactions?.tasks { purgeTasks() } ?: purgeTasks()
        transactions?.proactive { purgeProactive() } ?: purgeProactive()
        transactions?.evolution { purgeEvolution() } ?: purgeEvolution()
        transactions?.agents { purgeAgents() } ?: purgeAgents()
        transactions?.profile { purgeProfile() } ?: purgeProfile()
        transactions?.dreams { purgeDreams() } ?: purgeDreams()
        transactions?.agentRuns { purgeAgentRuns() } ?: purgeAgentRuns()
        transactions?.strategyBandit { purgeStrategyBandit() } ?: purgeStrategyBandit()
    }

    private suspend fun purgeMemory() {
        memoryEditDao.deleteAll()
        documentDao.deleteAll()
        creativeProjectDao.deleteAll()
        memoryDao.deleteAll()
        // Edges before nodes: an edge outliving its node is the one shape the
        // cascade cannot tidy after the fact.
        kgDao.deleteAllEdges()
        kgDao.deleteAllNodes()
        // Schema v10: world model + creative + taste tables. Without these,
        // restore -> purge -> restore would leave stale rows and the "clean
        // slate" promise of purgeAll would be a lie.
        beliefDao?.deleteAll()
        evidenceDao?.deleteAll()
        worldEventDao?.deleteAll()
        opportunityDao?.deleteAll()
        creativeArtifactDao?.deleteAll()
        creativeRevisionDao?.deleteAll()
        creativeBranchDao?.deleteAll()
        canonFactDao?.deleteAll()
        livingEventDao?.deleteAll()
        correctionDao?.deleteAll()
        openQuestionDao?.deleteAll()
        placeVisitDao?.deleteAll()
        creativeAnalysisDao?.deleteAll()
        livingWorldDao?.deleteAll()
        preferenceSignalDao?.deleteAll()
        styleProfileDao?.deleteAll()
        // Schema v13: memory feedback (audit trail for memory ratings).
        memoryFeedbackDao?.deleteAll()
        // Schema v26: harvested retrieval labels.
        retrievalLabelDao?.deleteAll()
        // Schema v27: notes before projects. The CASCADE would take the notes
        // anyway, but relying on it means the order here silently decides
        // whether a foreign-key error can happen, and explicit child-first
        // never can.
        projectNoteDao?.deleteAll()
        projectDao?.deleteAll()
        // Schema v28. This line used to claim it ran "before `beliefs` is
        // cleared, so the CASCADE never has to" — `beliefs` is cleared well
        // above, so the CASCADE has always done this work and the table is
        // already empty by the time this runs. Kept because an explicit delete
        // costs nothing and does not depend on the cascade staying configured;
        // the claim is corrected rather than the order, because reordering to
        // match a comment is how a working purge acquires a new bug.
        claimResolutionDao?.deleteAll()
        // Schema v12: purge durable state previously dropped on roundtrip.
        documentChunkDao?.deleteAll()
        referenceIdentityDao?.deleteAll()
        // Schema v13.
        artifactDependencyDao?.deleteAll()
        continuityIssueDao?.deleteAll()
        creativeSimulationDao?.deleteAll()
        routingOutcomeDao?.deleteAll()
    }

    private suspend fun purgeConversations() {
        conversationDao.deleteAll()
    }

    private suspend fun purgeHands() {
        // The WorkManager cancels for these rows already ran, above.
        handDao.deleteRunHistory()
        handDao.deleteAll()
    }

    private suspend fun purgeTasks() {
        reminderDao.deleteAll()
        taskDao.deleteAll()
    }

    private suspend fun purgeProactive() {
        proactiveEventDao.deleteAll()
        proactiveOutcomeDao?.deleteAll()
        proactiveInteractionDao?.deleteAll()
    }

    private suspend fun purgeEvolution() {
        evolutionProposalDao.deleteAll()
        evolutionRevisionDao.deleteAll()
        evolutionSettingsDao.deleteAll()
        evolutionEvidenceDao?.deleteAll()
        evolutionCandidateDao?.deleteAll()
    }

    private suspend fun purgeAgents() {
        // Builtins are re-seeded on startup; only user-created agents are ours
        // to delete.
        agentDao.deleteAllCustom()
        // Schema v16: council data.
        agentStateDao?.deleteAll()
        agentRelationshipDao?.deleteAll()
        agentObservationDao?.deleteAll()
        forumPostDao?.deleteAll()
        forumVoteDao?.deleteAll()
    }

    private suspend fun purgeProfile() {
        userProfileDao.deleteAll()
    }

    private suspend fun purgeDreams() {
        // Schema v11: dream database.
        dreamSummaryDao?.deleteAll()
        routineDao?.deleteAll()
        contradictionDao?.deleteAll()
        kgEdgeProposalDao?.deleteAll()
    }

    private suspend fun purgeAgentRuns() {
        agentRunDao?.deleteAll()
        goalDao?.deleteAll()
        stepDao?.deleteAll()
        agentEventDao?.deleteAll()
        approvalRequestDao?.deleteAll()
        runCheckpointDao?.deleteAll()
    }

    private suspend fun purgeStrategyBandit() {
        strategyBanditDao?.clear()
    }

    /**
     * Default export filename: `aura-backup-YYYYMMDD-HHMMSS.json`.
     * Caller uses this when constructing the share Intent.
     */
    override fun defaultExportFileName(now: Long): String {
        val date = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date(now))
        return "aura-backup-$date.json"
    }

    /** Default cache dir for the export file before sharing. */
    override fun exportFile(): File = File(context.cacheDir, defaultExportFileName(System.currentTimeMillis()))

    data class RestoreCounts(
            val memories: Int,
            val memoryEdits: Int,
            val documents: Int,
            val creativeProjects: Int,
            val conversations: Int,
            val nodes: Int,
            val edges: Int,
            val hands: Int,
            val handRuns: Int,
            val tasks: Int,
            val reminders: Int,
            val proactiveEvents: Int,
            val profile: Int,
            val evolutionProposals: Int = 0,
            val evolutionSettings: Int = 0,
            val evolutionRevisions: Int = 0,
            val agents: Int = 0,
            // Schema v10 fields. Until v0.30.x these existed in the JSON
            // but were silently dropped on restore. The defaults let the
            // constructor stay backward-compatible with existing call sites.
            val beliefs: Int = 0,
            val evidence: Int = 0,
            val worldEvents: Int = 0,
            val opportunities: Int = 0,
            val creativeArtifacts: Int = 0,
            val creativeRevisions: Int = 0,
            val creativeBranches: Int = 0,
            val canonFacts: Int = 0,
            val proactiveOutcomes: Int = 0,
            val livingWorlds: Int = 0,
            val livingEvents: Int = 0,
            val corrections: Int = 0,
            val openQuestions: Int = 0,
            val placeVisits: Int = 0,
            val creativeAnalysis: Int = 0,
            val preferenceSignals: Int = 0,
            val styleProfiles: Int = 0,
            // Schema v11: dream database.
            val dreamSummaries: Int = 0,
            val routines: Int = 0,
            val contradictions: Int = 0,
            val kgEdgeProposals: Int = 0,
            // Schema v12: durable state previously dropped on backup/restore.
            val memoryFeedback: Int = 0,
            val retrievalLabels: Int = 0,
            val documentChunks: Int = 0,
            val referenceIdentities: Int = 0,
            val agentRuns: Int = 0,
            val agentGoals: Int = 0,
            val agentSteps: Int = 0,
            val agentEvents: Int = 0,
            val agentApprovals: Int = 0,
            val runCheckpoints: Int = 0,
            // Schema v13: the last entities that had no backup class.
            val artifactDependencies: Int = 0,
            val continuityIssues: Int = 0,
            val creativeSimulations: Int = 0,
            val evolutionEvidence: Int = 0,
            val evolutionCandidates: Int = 0,
            val proactiveInteractions: Int = 0,
            val routingOutcomes: Int = 0,
            // Schema v18.
            val toolPolicies: Int = 0,
            /** How many of the five consciousness stores were written. */
            val consciousness: Int = 0,
            /**
             * Evolution revisions whose snapshot was encrypted by another
             * install and so was cleared on the way in. Deliberately NOT part
             * of [total]: it counts rows that were not written as revert
             * sources, and folding it into a "rows restored" figure would make
             * the number say the opposite of what happened.
             */
            val evolutionRevisionsUnreadable: Int = 0,
            // Schema v27.
            val projects: Int = 0,
            val projectNotes: Int = 0,
            val claimResolutions: Int = 0,
        ) {
            // Auto-derived: every non-self Int field summed. Until v0.30.x
            // this was a 17-term hand-sum that fell out of sync with the
            // schema v10 fields added below. Listing them explicitly here
            // (instead of via reflection) keeps the build dep-free and
            // makes the dependency obvious to the next person who adds a
            // new count.
            val total: Int get() = (
                memories + memoryEdits + documents + creativeProjects +
                conversations + nodes + edges + hands + handRuns +
                tasks + reminders + proactiveEvents + profile +
                evolutionProposals + evolutionSettings + evolutionRevisions +
                agents + beliefs + evidence + worldEvents + opportunities +
                creativeArtifacts + creativeRevisions + creativeBranches +
                canonFacts + livingWorlds + livingEvents + corrections + openQuestions + placeVisits + creativeAnalysis + proactiveOutcomes + preferenceSignals + styleProfiles +
                dreamSummaries + routines + contradictions + kgEdgeProposals +
                memoryFeedback + retrievalLabels + documentChunks + referenceIdentities +
                agentRuns + agentGoals + agentSteps + agentEvents +
                agentApprovals + runCheckpoints +
                artifactDependencies + continuityIssues + creativeSimulations +
                evolutionEvidence + evolutionCandidates + proactiveInteractions +
                routingOutcomes + toolPolicies + consciousness +
                projects + projectNotes + claimResolutions
            )
    }

    /**
     * How a restore treats what is already on the device.
     *
     * [MERGE] writes the file's rows on top of the existing ones; [REPLACE]
     * runs [purgeAll] first so the device ends up holding exactly what the
     * file holds. The distinction existed but was unreachable —
     * `BackupViewModel.confirmImport` took a `purgeFirst` flag and the one
     * caller passed `false` — so every restore was a merge, while
     * [restorePreferences] overwrote about twenty-five settings either way.
     * Naming both modes and putting both on the confirmation dialog is what
     * turns the destructive one into a choice.
     *
     * Declared next to [RestoreCounts] rather than above [restore] so the
     * restore KDoc still documents restore.
     */
    enum class RestoreMode { MERGE, REPLACE }

    companion object {
        /**
         * Plaintext probe for [AuraBackup.keyCanary]. Its content is
         * irrelevant — only whether it survives a decrypt round trip is.
         */
        private const val KEY_CANARY_PLAINTEXT = "aura-backup-key-canary-v1"

        /**
         * Lives in `filesDir` for the same reason as the marker below, which
         * said so first and was moved on its own. This file is the only copy of
         * the user's pre-restore data while the import runs; leaving it in a
         * directory the system may reclaim at any moment made the rollback
         * unavailable exactly when memory pressure — the most likely cause of a
         * failed import — made it most likely to be needed.
         */
        private const val ROLLBACK_FILE_NAME = "aura-restore-rollback.json"

        private const val ROLLBACK_VERSION_TAG = "pre-restore-rollback"

        /**
         * Lives in `filesDir`, not `cacheDir`: the point of the marker is that
         * it outlives the process that died mid-restore, and the system is free
         * to evict the cache before that process ever runs again.
         */
        internal const val RESTORE_MARKER_FILE_NAME = "aura-restore-in-progress.json"

        /** Seven days. Long enough to re-share an export, short enough to bound the leak. */
        private const val EXPORT_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    }

    /**
     * Replace the learned strategy weights when [backup] has any.
     *
     * The `isNotEmpty()` guard stays, and it is correct in both directions for
     * one reason: an empty list means "nothing to write", never "delete what is
     * there". In MERGE mode it stops a backup taken before the bandit existed
     * from wiping weights the device has since learned — the `clear()` inside
     * is a replace-this-table, not a merge. On the rollback path and in REPLACE
     * mode the table has already been emptied by [purgeAll], so skipping an
     * empty list writes nothing and deletes nothing, which is exactly right:
     * empty there means the pre-restore snapshot genuinely had no rows.
     */
    private suspend fun restoreStrategyBandit(backup: AuraBackup, mode: RestoreMode) {
        val rows = backup.strategyBandit.map { it.toEntity() }
        if (rows.isNotEmpty()) {
            // Only REPLACE clears. The merge dialog promises "nothing is deleted", and
            // clearing here threw away every strategy weight the backup did not happen to
            // carry — months of learning, on an import the user was told was additive.
            if (mode == RestoreMode.REPLACE) strategyBanditDao?.clear()
            strategyBanditDao?.insertAll(rows)
        }
    }

    /**
     * Replace the council tables from [backup]. The per-group `isNotEmpty()`
     * guards stay, for the reason given on [restoreStrategyBandit].
     *
     * The forum vote write below is the one part of a restore that can fail on
     * correct data: posts are re-inserted with fresh auto-generated ids while
     * the votes still carry the ids from the exporting device, so
     * `forum_votes.postId` can point at a row that no longer exists. That is
     * why [writeEverything] calls this inside a guard — see the comment there.
     */
    private suspend fun restoreCouncil(backup: AuraBackup, mode: RestoreMode) {
        // Agent state
        val states = backup.agentStates.map { it.toEntity() }
        if (states.isNotEmpty()) {
            if (mode == RestoreMode.REPLACE) agentStateDao?.deleteAll()
            agentStateDao?.upsertAll(states)
        }
        // Relationships
        val rels = backup.agentRelationships.map { it.toEntity() }
        if (rels.isNotEmpty()) {
            if (mode == RestoreMode.REPLACE) agentRelationshipDao?.deleteAll()
            agentRelationshipDao?.upsertAll(rels)
        }
        // Observations
        val obs = backup.agentObservations.map { it.toEntity() }
        if (obs.isNotEmpty()) {
            if (mode == RestoreMode.REPLACE) agentObservationDao?.deleteAll()
            agentObservationDao?.insertAll(obs)
        }
        // Forum posts
        val posts = backup.forumPosts.map { it.toEntity() }
        if (posts.isNotEmpty()) {
            if (mode == RestoreMode.REPLACE) forumPostDao?.deleteAll()
            posts.forEach { forumPostDao?.insert(it) }
        }
        // Forum votes
        //
        // `forum_votes.postId` is a foreign key onto the post, and it holds the *exporting*
        // device's row id. Backups written before schema 30 did not carry post ids at all,
        // so their posts came back under fresh autogenerated ids and every vote pointed at
        // nothing. The resulting FK violation propagated out of `restoreCouncil` into the
        // `runCatching` at :618, which abandoned the rest of the council restore while the
        // UI still reported success.
        //
        // Keep only the votes whose post came back under the id they name. For a schema-30
        // backup that is all of them; for an older one it is none, and those votes were
        // already unrecoverable — dropping them loses nothing that inserting them could
        // have saved, and stops them taking the restore down with them.
        val restorableIds = posts.mapNotNull { post -> post.id.takeIf { it != 0L } }.toSet()
        val votes = backup.forumVotes.filter { it.postId in restorableIds }.map { it.toEntity() }
        if (votes.isNotEmpty()) {
            if (mode == RestoreMode.REPLACE) forumVoteDao?.deleteAll()
            votes.forEach { forumVoteDao?.insert(it) }
        }
    }
}
