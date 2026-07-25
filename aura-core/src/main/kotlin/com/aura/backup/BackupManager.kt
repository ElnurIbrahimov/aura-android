package com.aura.backup

import android.content.Context
import com.aura.agent.ConversationDao
import com.aura.agent.ConversationEntity
import com.aura.agent.AgentDao
import com.aura.agent.AgentEntity
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
 * (384-dim for nomic-embed-text) and would be meaningless on a
 * different device with a different model. After import, the user
 * triggers Settings → Memory → Rebuild embeddings to regenerate.
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
) {

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
    suspend fun snapshot(
        appVersionName: String,
    ): AuraBackup = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        AuraBackup(
            exportedAt = now,
            appVersionName = appVersionName,
            memories = memoryDao.allForExport().map { it.toBackup() },
            memoryEdits = memoryEditDao.allForBackup().map { it.toBackup() },
            documents = documentDao.allForBackup().map { it.toBackup() },
            creativeProjects = creativeProjectDao.allForBackup().map { it.toBackup() },
            conversations = conversationDao.allForExport().map { it.toBackup() },
            knowledgeGraph = KnowledgeGraphBackup(
                nodes = kgDao.allNodes().map { it.toBackup() },
                edges = kgDao.allEdges().map { it.toBackup() },
            ),
            hands = handDao.getAll().map { it.toBackup() },
            handRuns = handDao.allRunsForBackup().map { it.toBackup() },
            tasks = taskDao.all().map { it.toBackup() },
            reminders = reminderDao.allForBackup().map { it.toBackup() },
            proactiveEvents = proactiveEventDao.allForBackup().map { it.toBackup() },
            userProfile = userProfileDao.get()?.toBackup(),
            preferences = PreferencesBackup(
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
                // Schema v8 additions — previously lost on backup/restore.
                visionModel = userPreferences.visionModel.first(),
                backgroundModel = userPreferences.backgroundModel.first(),
                deepModeModel = userPreferences.deepModeModel.first(),
                moaReferenceModels = userPreferences.moaReferenceModels.first().joinToString(","),
                moaAggregatorModel = userPreferences.moaAggregatorModel.first()?.takeIf { it.isNotBlank() },
                imageModel = userPreferences.imageModel.first().takeIf { it.isNotBlank() },
                smtpHost = userPreferences.smtpHost.first().takeIf { it.isNotBlank() },
                smtpPort = userPreferences.smtpPort.first(),
                smtpUsername = userPreferences.smtpUsername.first().takeIf { it.isNotBlank() },
                smtpFrom = userPreferences.smtpFrom.first().takeIf { it.isNotBlank() },
                mcpServersJson = userPreferences.mcpServersJson.first(),
                evolutionShadowEnabled = userPreferences.evolutionShadowEnabled.first(),
                evolutionOnboardingShown = userPreferences.evolutionOnboardingShown.first(),
                daemonEnabled = userPreferences.daemonEnabled.first(),
            ),
            usage = usageTracker.snapshot.value,
            agents = agentDao.allOnce().map { it.toBackup() },
            // Schema v10: world model, creative artifacts, taste.
            beliefs = beliefDao?.allForBackup()?.map { it.toBackup() } ?: emptyList(),
            evidence = evidenceDao?.allForBackup()?.map { it.toBackup() } ?: emptyList(),
            worldEvents = worldEventDao?.allForBackup()?.map { it.toBackup() } ?: emptyList(),
            opportunities = opportunityDao?.allForBackup()?.map { it.toBackup() } ?: emptyList(),
            creativeArtifacts = creativeArtifactDao?.allForBackup()?.map { it.toBackup() } ?: emptyList(),
            // CreativeRevision and CreativeBranch had types in AuraBackup.kt
            // since v0.30.0 but BackupManager never populated them, so
            // every snapshot silently wrote emptyList() for these fields.
            // Until v0.30.x they were dropped on roundtrip; this wires
            // them through snapshot+restore symmetrically.
            creativeRevisions = creativeRevisionDao?.allForBackup()?.map { it.toBackup() } ?: emptyList(),
            creativeBranches = creativeBranchDao?.allForBackup()?.map { it.toBackup() } ?: emptyList(),
            canonFacts = canonFactDao?.allForBackup()?.map { it.toBackup() } ?: emptyList(),
            preferenceSignals = preferenceSignalDao?.allForBackup()?.map { it.toBackup() } ?: emptyList(),
            styleProfiles = styleProfileDao?.allForBackup()?.map { it.toBackup() } ?: emptyList(),
            // Schema v11: dream database.
            dreamSummaries = dreamSummaryDao?.allForBackup()?.map { it.toBackup() } ?: emptyList(),
            routines = routineDao?.allForBackup()?.map { it.toBackup() } ?: emptyList(),
            contradictions = contradictionDao?.allForBackup()?.map { it.toBackup() } ?: emptyList(),
            kgEdgeProposals = kgEdgeProposalDao?.allForBackup()?.map { it.toBackup() } ?: emptyList(),
            // Schema v12: durable state previously dropped on backup/restore.
            memoryFeedback = memoryFeedbackDao?.all()?.map { it.toBackup() } ?: emptyList(),
            documentChunks = documentChunkDao?.allForBackup()?.map { it.toBackup() } ?: emptyList(),
            referenceIdentities = referenceIdentityDao?.allForBackup()?.map { it.toBackup() } ?: emptyList(),
            agentRuns = agentRunDao?.allForBackup()?.map { it.toBackup() } ?: emptyList(),
            agentGoals = goalDao?.allForBackup()?.map { it.toBackup() } ?: emptyList(),
            agentSteps = stepDao?.allForBackup()?.map { it.toBackup() } ?: emptyList(),
            agentEvents = agentEventDao?.allForBackup()?.map { it.toBackup() } ?: emptyList(),
            agentApprovals = approvalRequestDao?.allForBackup()?.map { it.toBackup() } ?: emptyList(),
            runCheckpoints = runCheckpointDao?.allForBackup()?.map { it.toBackup() } ?: emptyList(),
            artifactDependencies = artifactDependencyDao?.allForBackup()?.map { it.toBackup() } ?: emptyList(),
            continuityIssues = continuityIssueDao?.allForBackup()?.map { it.toBackup() } ?: emptyList(),
            creativeSimulations = creativeSimulationDao?.allForBackup()?.map { it.toBackup() } ?: emptyList(),
            evolutionEvidence = evolutionEvidenceDao?.allForBackup()?.map { it.toBackup() } ?: emptyList(),
            evolutionCandidates = evolutionCandidateDao?.allForBackup()?.map { it.toBackup() } ?: emptyList(),
            proactiveInteractions = proactiveInteractionDao?.allForBackup()?.map { it.toBackup() } ?: emptyList(),
            routingOutcomes = routingOutcomeDao?.allForBackup()?.map { it.toBackup() } ?: emptyList(),
        )
    }

    /**
     * Serialize [backup] to JSON. Public so the Settings UI can show
     * a preview or write the bytes to a content URI via
     * `ContentResolver.openOutputStream`.
     */
    fun encodeToJson(backup: AuraBackup): String = json.encodeToString(backup)

    /**
     * Parse [bytes] into an [AuraBackup]. Throws if the JSON is
     * unparseable or the schema version is newer than this build.
     */
    fun decodeFromJson(bytes: String): AuraBackup {
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
     * This is a destructive import — it does NOT clear tables first.
     * The OnConflictStrategy.REPLACE means re-importing the same
     * file is idempotent, but importing a smaller file after a
     * larger one will leave stale rows. The caller is expected to
     * call [purgeAll] first if a clean-slate restore is intended.
     *
     * @return the count of rows written per table.
     */
    suspend fun restore(backup: AuraBackup): RestoreCounts = withContext(Dispatchers.IO) {
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
        val preferenceSignalRows = backup.preferenceSignals.map { it.toEntity() }
        val styleProfileRows = backup.styleProfiles.map { it.toEntity() }
        // Schema v11: dream database.
        val dreamSummaryRows = backup.dreamSummaries.map { it.toEntity() }
        val routineRows = backup.routines.map { it.toEntity() }
        val contradictionRows = backup.contradictions.map { it.toEntity() }
        val kgEdgeProposalRows = backup.kgEdgeProposals.map { it.toEntity() }
        // Schema v12 rows.
        val memoryFeedbackRows = backup.memoryFeedback.map { it.toEntity() }
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

        if (memRows.isNotEmpty()) memoryDao.insertAll(memRows)
        if (editRows.isNotEmpty()) memoryEditDao.insertAll(editRows)
        if (documentRows.isNotEmpty()) documentDao.insertAll(documentRows)
        if (creativeRows.isNotEmpty()) creativeProjectDao.insertAll(creativeRows)
        if (convRows.isNotEmpty()) conversationDao.insertAll(convRows)
        if (nodeRows.isNotEmpty()) kgDao.insertAllNodes(nodeRows)
        if (edgeRows.isNotEmpty()) kgDao.insertAllEdges(edgeRows)
        if (handRows.isNotEmpty()) {
            handDao.insertAll(handRows)
            handRows.forEach(handScheduler::schedule)
        }
        if (handRunRows.isNotEmpty()) handDao.insertAllRuns(handRunRows)
        if (taskRows.isNotEmpty()) taskDao.insertAll(taskRows)
        if (beliefRows.isNotEmpty()) beliefDao?.insertAll(beliefRows)
        if (evidenceRows.isNotEmpty()) evidenceDao?.insertAll(evidenceRows)
        if (worldEventRows.isNotEmpty()) worldEventDao?.insertAll(worldEventRows)
        if (opportunityRows.isNotEmpty()) opportunityDao?.insertAll(opportunityRows)
        if (creativeArtifactRows.isNotEmpty()) creativeArtifactDao?.insertAll(creativeArtifactRows)
        if (creativeRevisionRows.isNotEmpty()) creativeRevisionDao?.insertAll(creativeRevisionRows)
        if (creativeBranchRows.isNotEmpty()) creativeBranchDao?.insertAll(creativeBranchRows)
        if (canonFactRows.isNotEmpty()) canonFactDao?.upsertAll(canonFactRows)
        if (preferenceSignalRows.isNotEmpty()) preferenceSignalDao?.insertAll(preferenceSignalRows)
        if (styleProfileRows.isNotEmpty()) styleProfileDao?.insertAll(styleProfileRows)
        // Schema v11: dream database.
        if (dreamSummaryRows.isNotEmpty()) dreamSummaryDao?.insertAll(dreamSummaryRows)
        if (routineRows.isNotEmpty()) routineDao?.insertAll(routineRows)
        if (contradictionRows.isNotEmpty()) contradictionDao?.insertAll(contradictionRows)
        if (kgEdgeProposalRows.isNotEmpty()) kgEdgeProposalDao?.insertAll(kgEdgeProposalRows)
        // Schema v12: restore durable state previously dropped on roundtrip.
        if (memoryFeedbackRows.isNotEmpty()) memoryFeedbackDao?.insertAll(memoryFeedbackRows)
        if (documentChunkRows.isNotEmpty()) documentChunkDao?.insertAll(documentChunkRows)
        if (referenceIdentityRows.isNotEmpty()) referenceIdentityDao?.insertAll(referenceIdentityRows)
        if (goalRows.isNotEmpty()) goalDao?.insertAll(goalRows)
        if (agentRunRows.isNotEmpty()) agentRunDao?.insertAll(agentRunRows)
        if (stepRows.isNotEmpty()) stepDao?.upsertAll(stepRows)
        if (agentEventRows.isNotEmpty()) agentEventDao?.insertAll(agentEventRows)
        if (agentApprovalRows.isNotEmpty()) approvalRequestDao?.insertAll(agentApprovalRows)
        if (runCheckpointRows.isNotEmpty()) runCheckpointDao?.upsertAll(runCheckpointRows)
        // Schema v13. Creative rows depend on artifacts/projects, which are
        // inserted above. Proactive interactions reference proactive events,
        // so they must follow the proactiveEventDao insert below.
        if (artifactDependencyRows.isNotEmpty()) artifactDependencyDao?.insertAll(artifactDependencyRows)
        if (continuityIssueRows.isNotEmpty()) continuityIssueDao?.insertAll(continuityIssueRows)
        if (creativeSimulationRows.isNotEmpty()) creativeSimulationDao?.insertAll(creativeSimulationRows)
        if (evolutionEvidenceRows.isNotEmpty()) evolutionEvidenceDao?.insertAll(evolutionEvidenceRows)
        if (evolutionCandidateRows.isNotEmpty()) evolutionCandidateDao?.insertAll(evolutionCandidateRows)
        if (routingOutcomeRows.isNotEmpty()) routingOutcomeDao?.insertAll(routingOutcomeRows)
        if (proactiveRows.isNotEmpty()) proactiveEventDao.insertAll(proactiveRows)
        if (proactiveInteractionRows.isNotEmpty()) proactiveInteractionDao?.insertAll(proactiveInteractionRows)
        restoreReminders(reminderRows)
        // If the backup has a profile, replace the current one.
        // If it doesn't, clear the existing profile so stale identity
        // data doesn't survive a restore.
        if (profileRow != null) {
            userProfileDao.upsert(profileRow)
        } else {
            userProfileDao.deleteAll()
        }
        backup.preferences.defaultModel?.let { userPreferences.setDefaultModel(it) }
        userPreferences.setAppLockEnabled(backup.preferences.appLockEnabled)
        userPreferences.setFirstRunComplete(backup.preferences.firstRunComplete)
        backup.preferences.embeddingModel?.let { providerKeys.setEmbeddingModel(it) }
        userPreferences.setLastSeenProactiveAt(backup.preferences.lastSeenProactiveAt)
        userPreferences.setMorningBriefEnabled(backup.preferences.morningBriefEnabled)
        userPreferences.setCalendarMonitorEnabled(backup.preferences.calendarMonitorEnabled)
        userPreferences.setTtsEnabled(backup.preferences.ttsEnabled)
        userPreferences.setIncognitoDefault(backup.preferences.incognitoDefault)
        userPreferences.setThemeMode(backup.preferences.themeMode)
        userPreferences.setCustomIdentity(backup.preferences.customIdentity)
        userPreferences.setSpecialistOverrides(backup.preferences.specialistOverrides)
        userPreferences.setMorningBriefHour(backup.preferences.morningBriefHour)
        userPreferences.setSpecialistToolOverrides(backup.preferences.specialistToolOverrides)
        // Schema v8 additions — restore previously-lost prefs.
        backup.preferences.visionModel?.let { userPreferences.setVisionModel(it) }
        backup.preferences.backgroundModel?.let { userPreferences.setBackgroundModel(it) }
        backup.preferences.deepModeModel?.let { userPreferences.setDeepModeModel(it) }
        if (backup.preferences.moaReferenceModels.isNotBlank()) {
            userPreferences.setMoaReferenceModels(backup.preferences.moaReferenceModels.split(",").filter { it.isNotBlank() })
        }
        backup.preferences.moaAggregatorModel?.let { userPreferences.setMoaAggregatorModel(it) }
        backup.preferences.imageModel?.takeIf { it.isNotBlank() }?.let { userPreferences.setImageModel(it) }
        backup.preferences.smtpHost?.takeIf { it.isNotBlank() }?.let { host ->
            userPreferences.setSmtpConfig(
                host = host,
                port = backup.preferences.smtpPort,
                username = backup.preferences.smtpUsername ?: "",
                from = backup.preferences.smtpFrom ?: "",
                // Password is intentionally not backed up — stored in SecureDataStore.
                password = "",
            )
        }
        if (backup.preferences.mcpServersJson.isNotBlank() && backup.preferences.mcpServersJson != "[]") {
            userPreferences.setMcpServersJson(backup.preferences.mcpServersJson)
        }
        userPreferences.setEvolutionShadowEnabled(backup.preferences.evolutionShadowEnabled)
        userPreferences.setEvolutionOnboardingShown(backup.preferences.evolutionOnboardingShown)
        userPreferences.setDaemonEnabled(backup.preferences.daemonEnabled)
        usageTracker.restore(backup.usage)
        restoreEvolution(backup)
        // Restore custom agents. Builtins are re-seeded on startup
        // so we only insert non-builtin entries from the backup.
        val customAgents = agentRows.filter { !it.isBuiltin }
        if (customAgents.isNotEmpty()) agentDao.insertAll(customAgents)

        RestoreCounts(
            memories = memRows.size,
            memoryEdits = editRows.size,
            documents = documentRows.size,
            creativeProjects = creativeRows.size,
            conversations = convRows.size,
            nodes = nodeRows.size,
            edges = edgeRows.size,
            hands = handRows.size,
            handRuns = handRunRows.size,
            tasks = taskRows.size,
            reminders = reminderRows.size,
            proactiveEvents = proactiveRows.size,
            profile = if (profileRow != null) 1 else 0,
            evolutionProposals = backup.evolutionProposals.size,
            evolutionSettings = backup.evolutionSettings.size,
            evolutionRevisions = backup.evolutionRevisions.size,
            agents = agentRows.size,
            // Schema v10: previously missing from RestoreCounts entirely.
            beliefs = beliefRows.size,
            evidence = evidenceRows.size,
            worldEvents = worldEventRows.size,
            opportunities = opportunityRows.size,
            creativeArtifacts = creativeArtifactRows.size,
            creativeRevisions = creativeRevisionRows.size,
            creativeBranches = creativeBranchRows.size,
            canonFacts = canonFactRows.size,
            preferenceSignals = preferenceSignalRows.size,
            styleProfiles = styleProfileRows.size,
            // Schema v11: dream database.
            dreamSummaries = dreamSummaryRows.size,
            routines = routineRows.size,
            contradictions = contradictionRows.size,
            kgEdgeProposals = kgEdgeProposalRows.size,
            // Schema v12: durable state previously dropped on backup/restore.
            memoryFeedback = memoryFeedbackRows.size,
            documentChunks = documentChunkRows.size,
            referenceIdentities = referenceIdentityRows.size,
            agentRuns = agentRunRows.size,
            agentGoals = goalRows.size,
            agentSteps = stepRows.size,
            agentEvents = agentEventRows.size,
            agentApprovals = agentApprovalRows.size,
            runCheckpoints = runCheckpointRows.size,
            // Schema v13.
            artifactDependencies = artifactDependencyRows.size,
            continuityIssues = continuityIssueRows.size,
            creativeSimulations = creativeSimulationRows.size,
            evolutionEvidence = evolutionEvidenceRows.size,
            evolutionCandidates = evolutionCandidateRows.size,
            proactiveInteractions = proactiveInteractionRows.size,
            routingOutcomes = routingOutcomeRows.size,
        )
    }

    private suspend fun restoreEvolution(backup: AuraBackup) {
        backup.evolutionProposals.map { it.toEntity() }.let { rows ->
            if (rows.isNotEmpty()) evolutionProposalDao.insertAll(rows)
        }
        backup.evolutionRevisions.map { it.toEntity() }.let { rows ->
            if (rows.isNotEmpty()) evolutionRevisionDao.insertAll(rows)
        }
        backup.evolutionSettings.forEach { settings ->
            evolutionSettingsDao.upsert(settings.toEntity())
        }
        // Restore evolution preferences.
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
        // Conversations, memories, hands, tasks are independent
        // tables — we use a single bulk DELETE via a one-off
        // Query to avoid N round-trips.
        memoryEditDao.deleteAll()
        documentDao.deleteAll()
        creativeProjectDao.deleteAll()
        memoryDao.deleteAll()
        conversationDao.deleteAll()
        kgDao.deleteAllEdges()
        kgDao.deleteAllNodes()
        handDao.getAll().forEach { handScheduler.cancel(it.id) }
        handDao.deleteRunHistory()
        handDao.deleteAll()
        reminderDao.deleteAll()
        taskDao.deleteAll()
        proactiveEventDao.deleteAll()
        evolutionProposalDao.deleteAll()
        evolutionRevisionDao.deleteAll()
        evolutionSettingsDao.deleteAll()
        agentDao.deleteAllCustom()
        userProfileDao.deleteAll()
        // Schema v10: world model + creative + taste tables. Without
        // these, restore→purge→restore would leave stale rows in the
        // world/creative/taste tables and the "clean slate" promise
        // of purgeAll would be a lie.
        beliefDao?.deleteAll()
        evidenceDao?.deleteAll()
        worldEventDao?.deleteAll()
        opportunityDao?.deleteAll()
        creativeArtifactDao?.deleteAll()
        creativeRevisionDao?.deleteAll()
        creativeBranchDao?.deleteAll()
        canonFactDao?.deleteAll()
        preferenceSignalDao?.deleteAll()
        styleProfileDao?.deleteAll()
        // Schema v11: dream database.
        dreamSummaryDao?.deleteAll()
        routineDao?.deleteAll()
        contradictionDao?.deleteAll()
        kgEdgeProposalDao?.deleteAll()
        // Schema v13: memory feedback (audit trail for memory ratings).
        memoryFeedbackDao?.deleteAll()
        // Schema v12: purge durable state previously dropped on roundtrip.
        documentChunkDao?.deleteAll()
        referenceIdentityDao?.deleteAll()
        agentRunDao?.deleteAll()
        goalDao?.deleteAll()
        stepDao?.deleteAll()
        agentEventDao?.deleteAll()
        approvalRequestDao?.deleteAll()
        runCheckpointDao?.deleteAll()
        // Schema v13.
        artifactDependencyDao?.deleteAll()
        continuityIssueDao?.deleteAll()
        creativeSimulationDao?.deleteAll()
        evolutionEvidenceDao?.deleteAll()
        evolutionCandidateDao?.deleteAll()
        proactiveInteractionDao?.deleteAll()
        routingOutcomeDao?.deleteAll()
    }

    /**
     * Default export filename: `aura-backup-YYYYMMDD-HHMMSS.json`.
     * Caller uses this when constructing the share Intent.
     */
    fun defaultExportFileName(now: Long = System.currentTimeMillis()): String {
        val date = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date(now))
        return "aura-backup-$date.json"
    }

    /** Default cache dir for the export file before sharing. */
    fun exportFile(): File = File(context.cacheDir, defaultExportFileName())

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
            val preferenceSignals: Int = 0,
            val styleProfiles: Int = 0,
            // Schema v11: dream database.
            val dreamSummaries: Int = 0,
            val routines: Int = 0,
            val contradictions: Int = 0,
            val kgEdgeProposals: Int = 0,
            // Schema v12: durable state previously dropped on backup/restore.
            val memoryFeedback: Int = 0,
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
                canonFacts + preferenceSignals + styleProfiles +
                dreamSummaries + routines + contradictions + kgEdgeProposals +
                memoryFeedback + documentChunks + referenceIdentities +
                agentRuns + agentGoals + agentSteps + agentEvents +
                agentApprovals + runCheckpoints +
                artifactDependencies + continuityIssues + creativeSimulations +
                evolutionEvidence + evolutionCandidates + proactiveInteractions +
                routingOutcomes
            )
    }
}

// ── Mappers: Entity → Backup ──

private fun MemoryEntity.toBackup() = MemoryBackup(
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
)

private fun MemoryBackup.toEntity() = MemoryEntity(
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
)

private fun MemoryEditEntity.toBackup() = MemoryEditBackup(
    id, memoryId, oldContent, newContent, oldCategory, newCategory, editedAt, editedBy,
)

private fun MemoryEditBackup.toEntity() = MemoryEditEntity(
    id, memoryId, oldContent, newContent, oldCategory, newCategory, editedAt, editedBy,
)

private fun DocumentEntity.toBackup() = DocumentBackup(
    id, name, mimeType, sourceUri, importedAt, characterCount, chunkCount,
)

private fun DocumentBackup.toEntity() = DocumentEntity(
    id, name, mimeType, sourceUri, importedAt, characterCount, chunkCount,
)

private fun CreativeProjectEntity.toBackup() = CreativeProjectBackup(
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

private fun CreativeProjectBackup.toEntity() = CreativeProjectEntity(
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

private fun ConversationEntity.toBackup() = ConversationBackup(
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

private fun ConversationBackup.toEntity() = ConversationEntity(
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

private fun NodeEntity.toBackup() = NodeBackup(
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

private fun NodeBackup.toEntity() = NodeEntity(
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

private fun EdgeEntity.toBackup() = EdgeBackup(
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

private fun EdgeBackup.toEntity() = EdgeEntity(
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

private fun Hand.toBackup() = HandBackup(
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

private fun HandBackup.toEntity() = Hand(
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

private fun HandRun.toBackup() = HandRunBackup(
    id, handId, handName, trigger, status, startedAt, finishedAt, output, failedStep, variablesJson,
)

private fun HandRunBackup.toEntity() = HandRun(
    id, handId, handName, trigger, status, startedAt, finishedAt, output, failedStep, variablesJson,
)

private fun TaskEntity.toBackup() = TaskBackup(
    id = id, title = title, description = description,
    createdAt = createdAt, dueAt = dueAt, completedAt = completedAt,
    status = status, priority = priority, tags = tags,
)

private fun TaskBackup.toEntity() = TaskEntity(
    id = id, title = title, description = description,
    createdAt = createdAt, dueAt = dueAt, completedAt = completedAt,
    status = status, priority = priority, tags = tags,
)

private fun ReminderEntity.toBackup() = ReminderBackup(
    id, message, triggerAt, createdAt, taskId, recurrence, status, firedAt,
)

private fun ReminderBackup.toEntity() = ReminderEntity(
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

private fun ProactiveEventEntity.toBackup() = ProactiveEventBackup(
    id, eventType, title, body, timestamp, payload, correlationTag,
)

private fun ProactiveEventBackup.toEntity() = ProactiveEventEntity(
    id, eventType, title, body, timestamp, payload, correlationTag,
)

private fun UserProfileEntity.toBackup() = UserProfileBackup(
    name = name,
    traitsJson = traitsJson,
    preferencesJson = preferencesJson,
    factsJson = factsJson,
    lastUpdated = lastUpdated,
    agentScope = agentScope,
)

private fun UserProfileBackup.toEntity() = UserProfileEntity(
    id = 1,
    agentScope = agentScope,
    name = name,
    traitsJson = traitsJson,
    preferencesJson = preferencesJson,
    factsJson = factsJson,
    lastUpdated = lastUpdated,
)
private fun EvolutionProposalBackup.toEntity() = com.aura.evolution.EvolutionProposalEntity(
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

private fun EvolutionSettingsBackup.toEntity() = com.aura.evolution.EvolutionSettingsEntity(
    domain = domain,
    enabled = enabled,
    updatedAt = updatedAt,
)

private fun EvolutionRevisionBackup.toEntity() = com.aura.evolution.EvolutionRevisionEntity(
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

private fun com.aura.world.BeliefEntity.toBackup() = BeliefBackup(
    id = id, subject = subject, predicate = predicate, valueJson = valueJson,
    confidence = confidence, validFrom = validFrom, validTo = validTo,
    status = status, supersededBy = supersededBy, privacyClass = privacyClass,
    agentScope = agentScope,
    createdAt = createdAt, updatedAt = updatedAt, lastVerifiedAt = lastVerifiedAt,
)

private fun com.aura.world.EvidenceEntity.toBackup() = EvidenceBackup(
    id = id, beliefId = beliefId, source = source, summary = summary,
    detailJson = detailJson, timestamp = timestamp, confidence = confidence,
    agentScope = agentScope,
)

private fun com.aura.world.WorldEventEntity.toBackup() = WorldEventBackup(
    id = id, eventType = eventType, source = source, summary = summary,
    payloadJson = payloadJson, timestamp = timestamp, consumed = consumed,
    agentScope = agentScope,
)

private fun com.aura.world.OpportunityEntity.toBackup() = OpportunityBackup(
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

private fun BeliefBackup.toEntity() = com.aura.world.BeliefEntity(
    id = id, subject = subject, predicate = predicate, valueJson = valueJson,
    confidence = confidence, validFrom = validFrom, validTo = validTo,
    status = status, supersededBy = supersededBy, privacyClass = privacyClass,
    agentScope = agentScope,
    createdAt = createdAt, updatedAt = updatedAt, lastVerifiedAt = lastVerifiedAt,
)

private fun EvidenceBackup.toEntity() = com.aura.world.EvidenceEntity(
    id = id, beliefId = beliefId, source = source, summary = summary,
    detailJson = detailJson, timestamp = timestamp, confidence = confidence,
    agentScope = agentScope,
)

private fun WorldEventBackup.toEntity() = com.aura.world.WorldEventEntity(
    id = id, eventType = eventType, source = source, summary = summary,
    payloadJson = payloadJson, timestamp = timestamp, consumed = consumed,
    agentScope = agentScope,
)

private fun OpportunityBackup.toEntity() = com.aura.world.OpportunityEntity(
    id = id, title = title, description = description, kind = kind,
    benefit = benefit, urgency = urgency, confidence = confidence,
    costEstimateJson = costEstimateJson, evidenceJson = evidenceJson,
    suggestedActionJson = suggestedActionJson, status = status,
    createdAt = createdAt, resolvedAt = resolvedAt, snoozeUntil = snoozeUntil,
    agentScope = agentScope,
)

// ── Schema v10: Creative artifact mappers (toBackup)

private fun com.aura.creative.CreativeArtifactEntity.toBackup() = CreativeArtifactBackup(
    id = id, projectId = projectId, branchId = branchId, kind = kind,
    title = title, currentRevisionId = currentRevisionId, previewText = previewText,
    mimeType = mimeType, storageUri = storageUri, contentHash = contentHash,
    status = status, metadataJson = metadataJson, createdAt = createdAt, updatedAt = updatedAt,
)

private fun com.aura.creative.CanonFactEntity.toBackup() = CanonFactBackup(
    id = id, projectId = projectId, branchId = branchId, subjectType = subjectType,
    subjectId = subjectId, predicate = predicate, valueJson = valueJson,
    validFrom = validFrom, validTo = validTo, confidence = confidence,
    sourceRevisionId = sourceRevisionId, status = status,
    createdAt = createdAt, updatedAt = updatedAt,
)

// ── Schema v10: Creative artifact mappers (toEntity)
// Until v0.30.x, CreativeArtifactBackup and CanonFactBackup were
// written to JSON but never read back. Closing the loop now.

private fun CreativeArtifactBackup.toEntity() = com.aura.creative.CreativeArtifactEntity(
    id = id, projectId = projectId, branchId = branchId, kind = kind,
    title = title, currentRevisionId = currentRevisionId, previewText = previewText,
    mimeType = mimeType, storageUri = storageUri, contentHash = contentHash,
    status = status, metadataJson = metadataJson, createdAt = createdAt, updatedAt = updatedAt,
)

// CreativeRevisionEntity ↔ CreativeRevisionBackup (schema extended in
// v0.30.x to match the entity's full field set; old backups remain
// forward-compatible because every new field has a default value).
private fun com.aura.creative.CreativeRevisionEntity.toBackup() = CreativeRevisionBackup(
    id = id, artifactId = artifactId, branchId = branchId,
    parentRevisionId = parentRevisionId, contentText = contentText,
    storageUri = storageUri, contentHash = contentHash,
    authorKind = authorKind, providerPrefix = providerPrefix,
    modelId = modelId, prompt = prompt, settingsJson = settingsJson,
    createdAt = createdAt,
)

private fun CreativeRevisionBackup.toEntity() = com.aura.creative.CreativeRevisionEntity(
    id = id, artifactId = artifactId, branchId = branchId,
    parentRevisionId = parentRevisionId, contentText = contentText,
    storageUri = storageUri, contentHash = contentHash,
    authorKind = authorKind, providerPrefix = providerPrefix,
    modelId = modelId, prompt = prompt, settingsJson = settingsJson,
    createdAt = createdAt,
)

private fun com.aura.creative.CreativeBranchEntity.toBackup() = CreativeBranchBackup(
    id = id, projectId = projectId, name = name,
    baseRevisionId = baseRevisionId, headRevisionId = headRevisionId,
    status = status, createdAt = createdAt,
)

private fun CreativeBranchBackup.toEntity() = com.aura.creative.CreativeBranchEntity(
    id = id, projectId = projectId, name = name,
    baseRevisionId = baseRevisionId, headRevisionId = headRevisionId,
    status = status, createdAt = createdAt,
)

private fun CanonFactBackup.toEntity() = com.aura.creative.CanonFactEntity(
    id = id, projectId = projectId, branchId = branchId, subjectType = subjectType,
    subjectId = subjectId, predicate = predicate, valueJson = valueJson,
    validFrom = validFrom, validTo = validTo, confidence = confidence,
    sourceRevisionId = sourceRevisionId, status = status,
    createdAt = createdAt, updatedAt = updatedAt,
)

// ── Schema v10: Taste mappers (toBackup)

private fun com.aura.taste.PreferenceSignalEntity.toBackup() = PreferenceSignalBackup(
    id = id, projectId = projectId, signalType = signalType, category = category,
    artifactId = artifactId, attributesJson = attributesJson,
    weight = weight, createdAt = createdAt, agentScope = agentScope,
)

private fun com.aura.taste.StyleProfileEntity.toBackup() = StyleProfileBackup(
    id = id, projectId = projectId, attributesJson = attributesJson,
    signalCount = signalCount, createdAt = createdAt, updatedAt = updatedAt,
    agentScope = agentScope,
)

// ── Schema v10: Taste mappers (toEntity)

private fun PreferenceSignalBackup.toEntity() = com.aura.taste.PreferenceSignalEntity(
    id = id, projectId = projectId, signalType = signalType, category = category,
    artifactId = artifactId, attributesJson = attributesJson,
    weight = weight, createdAt = createdAt, agentScope = agentScope,
)

private fun StyleProfileBackup.toEntity() = com.aura.taste.StyleProfileEntity(
    id = id, projectId = projectId, attributesJson = attributesJson,
    signalCount = signalCount, createdAt = createdAt, updatedAt = updatedAt,
    agentScope = agentScope,
)

// ── Schema v11: Dream database mappers ──

private fun com.aura.dream.DreamSummaryEntity.toBackup() = DreamSummaryBackup(
    id = id, clusterId = clusterId, compressedText = compressedText,
    sourceMemoryIds = sourceMemoryIds, dominantTags = dominantTags,
    sourceCount = sourceCount, modelUsed = modelUsed, createdAt = createdAt,
)

private fun DreamSummaryBackup.toEntity() = com.aura.dream.DreamSummaryEntity(
    id = id, clusterId = clusterId, compressedText = compressedText,
    sourceMemoryIds = sourceMemoryIds, dominantTags = dominantTags,
    sourceCount = sourceCount, modelUsed = modelUsed, createdAt = createdAt,
)

private fun com.aura.dream.RoutineEntity.toBackup() = RoutineBackup(
    id = id, signature = signature, displayLabel = displayLabel,
    occurrenceCount = occurrenceCount, distinctConversations = distinctConversations,
    sourceConversationIds = sourceConversationIds, firstSeenAt = firstSeenAt,
    lastSeenAt = lastSeenAt, description = description,
    createdAt = createdAt, updatedAt = updatedAt,
)

private fun RoutineBackup.toEntity() = com.aura.dream.RoutineEntity(
    id = id, signature = signature, displayLabel = displayLabel,
    occurrenceCount = occurrenceCount, distinctConversations = distinctConversations,
    sourceConversationIds = sourceConversationIds, firstSeenAt = firstSeenAt,
    lastSeenAt = lastSeenAt, description = description,
    createdAt = createdAt, updatedAt = updatedAt,
)

private fun com.aura.dream.ContradictionEntity.toBackup() = ContradictionBackup(
    id = id, olderSummaryId = olderSummaryId, newerSummaryId = newerSummaryId,
    olderText = olderText, newerText = newerText, triggerPhrase = triggerPhrase,
    confidence = confidence, status = status, createdAt = createdAt, resolvedAt = resolvedAt,
)

private fun ContradictionBackup.toEntity() = com.aura.dream.ContradictionEntity(
    id = id, olderSummaryId = olderSummaryId, newerSummaryId = newerSummaryId,
    olderText = olderText, newerText = newerText, triggerPhrase = triggerPhrase,
    confidence = confidence, status = status, createdAt = createdAt, resolvedAt = resolvedAt,
)

private fun com.aura.dream.KgEdgeProposalEntity.toBackup() = KgEdgeProposalBackup(
    id = id, fromNodeId = fromNodeId, toNodeId = toNodeId,
    fromLabel = fromLabel, toLabel = toLabel, similarity = similarity,
    proposedEdge = proposedEdge, status = status, createdAt = createdAt, decidedAt = decidedAt,
)

private fun KgEdgeProposalBackup.toEntity() = com.aura.dream.KgEdgeProposalEntity(
    id = id, fromNodeId = fromNodeId, toNodeId = toNodeId,
    fromLabel = fromLabel, toLabel = toLabel, similarity = similarity,
    proposedEdge = proposedEdge, status = status, createdAt = createdAt, decidedAt = decidedAt,
)
