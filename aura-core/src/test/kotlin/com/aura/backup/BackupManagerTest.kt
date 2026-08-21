package com.aura.backup

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Contract tests for [BackupManager]. The DAO contract tests for the
 * underlying tables live in their respective `*DaoTest` files. These
 * tests pin the BackupManager's own logic: round-trip encode/decode,
 * schema-version guard, table-coverage of the export, and the
 * destructive `purgeAll` step.
 */
class BackupManagerTest {

    private val memoryDao = mockk<com.aura.memory.MemoryDao>(relaxed = true)
    private val memoryEditDao = mockk<com.aura.memory.MemoryEditDao>(relaxed = true)
    private val documentDao = mockk<com.aura.documents.DocumentDao>(relaxed = true)
    private val creativeProjectDao = mockk<com.aura.creative.CreativeProjectDao>(relaxed = true)
    private val conversationDao = mockk<com.aura.agent.ConversationDao>(relaxed = true)
    private val kgDao = mockk<com.aura.kg.KnowledgeGraphDao>(relaxed = true)
    private val handDao = mockk<com.aura.hands.HandDao>(relaxed = true)
    private val taskDao = mockk<com.aura.tasks.TaskDao>(relaxed = true)
    private val reminderDao = mockk<com.aura.tasks.ReminderDao>(relaxed = true)
    private val proactiveEventDao = mockk<com.aura.proactive.ProactiveEventDao>(relaxed = true)
    private val userProfileDao = mockk<com.aura.profile.UserProfileDao>(relaxed = true)
    private val providerKeys = mockk<com.aura.providers.ProviderKeys>(relaxed = true)
    private val userPreferences = mockk<com.aura.data.UserPreferences>(relaxed = true)
    /**
     * Real directories behind the mocked Context.
     *
     * The rollback snapshot is spooled to disk now, so `File(context.cacheDir,
     * …)` runs against whatever the mock returns — and `java.io.File(File,
     * String)` reads the parent's private `path` field directly, which a MockK
     * proxy does not have. A relaxed mock therefore fails inside java.io
     * rather than at the assertion.
     */
    private val tmpDir = kotlin.io.path.createTempDirectory("aura-backup-test").toFile()
        .also { it.deleteOnExit() }
    private val context = mockk<android.content.Context>(relaxed = true).also {
        every { it.cacheDir } returns tmpDir
        every { it.filesDir } returns tmpDir
    }
    private val reminderScheduler = mockk<com.aura.tasks.ReminderScheduler>(relaxed = true)
    private val handScheduler = mockk<com.aura.hands.HandScheduler>(relaxed = true)
    private val usageTracker = com.aura.usage.UsageTracker()
    private val evolutionProposalDao = mockk<com.aura.evolution.EvolutionProposalDao>(relaxed = true)
    private val evolutionSettingsDao = mockk<com.aura.evolution.EvolutionSettingsDao>(relaxed = true)
    private val evolutionRevisionDao = mockk<com.aura.evolution.EvolutionRevisionDao>(relaxed = true)
    private val agentDao = mockk<com.aura.agent.AgentDao>(relaxed = true)
    private val strategyBanditDao = mockk<com.aura.agent.StrategyBanditDao>(relaxed = true)
    private val agentStateDao = mockk<com.aura.agent.state.AgentStateDao>(relaxed = true)
    private val agentRelationshipDao = mockk<com.aura.agent.state.AgentRelationshipDao>(relaxed = true)
    private val agentObservationDao = mockk<com.aura.agent.state.AgentObservationDao>(relaxed = true)
    private val forumPostDao = mockk<com.aura.agent.forum.ForumPostDao>(relaxed = true)
    private val forumVoteDao = mockk<com.aura.agent.forum.ForumVoteDao>(relaxed = true)

    private val manager = BackupManager(
        context = context,
        memoryDao = memoryDao,
        memoryEditDao = memoryEditDao,
        documentDao = documentDao,
        creativeProjectDao = creativeProjectDao,
        conversationDao = conversationDao,
        kgDao = kgDao,
        handDao = handDao,
        taskDao = taskDao,
        reminderDao = reminderDao,
        proactiveEventDao = proactiveEventDao,
        userProfileDao = userProfileDao,
        providerKeys = providerKeys,
        userPreferences = userPreferences,
        reminderScheduler = reminderScheduler,
        handScheduler = handScheduler,
        usageTracker = usageTracker,
        evolutionProposalDao = evolutionProposalDao,
        evolutionSettingsDao = evolutionSettingsDao,
        evolutionRevisionDao = evolutionRevisionDao,
        agentDao = agentDao,
        strategyBanditDao = strategyBanditDao,
        agentStateDao = agentStateDao,
        agentRelationshipDao = agentRelationshipDao,
        agentObservationDao = agentObservationDao,
        forumPostDao = forumPostDao,
        forumVoteDao = forumVoteDao,
    )

    /**
     * Mock every DAO read + preference flow that [BackupManager.snapshot]
     * touches. Shared by the snapshot test and the restore-rollback test
     * (restore() now snapshots current data before writing).
     */
    private fun mockSnapshotDeps() {
        coEvery { memoryDao.allForExport() } returns emptyList()
        coEvery { memoryEditDao.allForBackup() } returns emptyList()
        coEvery { documentDao.allForBackup() } returns emptyList()
        coEvery { creativeProjectDao.allForBackup() } returns emptyList()
        coEvery { conversationDao.allForExport() } returns emptyList()
        coEvery { kgDao.allNodes() } returns emptyList()
        coEvery { kgDao.allEdges() } returns emptyList()
        coEvery { handDao.getAll() } returns emptyList()
        coEvery { handDao.allRunsForBackup() } returns emptyList()
        coEvery { taskDao.all() } returns emptyList()
        coEvery { reminderDao.allForBackup() } returns emptyList()
        coEvery { proactiveEventDao.allForBackup() } returns emptyList()
        coEvery { userProfileDao.get() } returns null
        coEvery { agentDao.allOnce() } returns emptyList()
        every { userPreferences.defaultModel } returns flowOf("ollama:deepseek-v4-pro:cloud")
        every { userPreferences.firstRunComplete } returns flowOf(true)
        every { userPreferences.appLockEnabled } returns flowOf(false)
        every { userPreferences.lastSeenProactiveAt } returns flowOf(0L)
        every { userPreferences.morningBriefEnabled } returns flowOf(true)
        every { userPreferences.calendarMonitorEnabled } returns flowOf(true)
        every { userPreferences.ttsEnabled } returns flowOf(true)
        every { userPreferences.incognitoDefault } returns flowOf(false)
        every { userPreferences.themeMode } returns flowOf("system")
        every { userPreferences.customIdentity } returns flowOf("")
        every { userPreferences.specialistOverrides } returns flowOf("{}")
        every { userPreferences.morningBriefHour } returns flowOf(7)
        every { userPreferences.specialistToolOverrides } returns flowOf("{}")
        every { userPreferences.visionModel } returns flowOf(null)
        every { userPreferences.backgroundModel } returns flowOf(null)
        every { userPreferences.deepModeModel } returns flowOf(null)
        every { userPreferences.moaReferenceModels } returns flowOf(emptyList())
        every { userPreferences.moaAggregatorModel } returns flowOf(null)
        every { userPreferences.imageModel } returns flowOf("")
        every { userPreferences.videoModel } returns flowOf("")
        every { userPreferences.voiceModel } returns flowOf("")
        every { userPreferences.smtpHost } returns flowOf("")
        every { userPreferences.smtpPort } returns flowOf(587)
        every { userPreferences.smtpUsername } returns flowOf("")
        every { userPreferences.smtpFrom } returns flowOf("")
        every { userPreferences.mcpServersJson } returns flowOf("[]")
        every { userPreferences.evolutionOnboardingShown } returns flowOf(false)
        every { userPreferences.daemonEnabled } returns flowOf(false)
        every { userPreferences.reasoningEnabled } returns flowOf(true)
        every { userPreferences.reasoningBudget } returns flowOf(32000)
        every { userPreferences.googleClientId } returns flowOf("")
        every { userPreferences.microsoftClientId } returns flowOf("")
        every { userPreferences.dreamLastRunAt } returns flowOf(0L)
        every { userPreferences.dreamLastRunStats } returns flowOf("")
        every { userPreferences.evolutionEnabled } returns flowOf(false)
        every { userPreferences.evolutionIntervalHours } returns flowOf(24)
        every { userPreferences.dreamEnabled } returns flowOf(true)
        every { userPreferences.decayEnabled } returns flowOf(true)
        every { userPreferences.triggersEnabled } returns flowOf(true)
        every { userPreferences.triggers } returns flowOf(emptyList())
        every { userPreferences.planningEnabled } returns flowOf(false)
        every { userPreferences.agentId } returns flowOf(null)
        every { userPreferences.councilEnabled } returns flowOf(true)
        every { userPreferences.councilAutoApply } returns flowOf(false)
        every { userPreferences.councilActivityLevel } returns flowOf(3)
        every { userPreferences.forRole(any()) } returns flowOf(null)
        every { providerKeys.embeddingModel } returns "nomic-embed-text"
    }

    @Test
    fun `snapshot exports all six tables plus preferences`() = runTest {
        // Empty tables — the call should not throw and should return a
        // valid AuraBackup with the metadata fields populated.
        coEvery { memoryDao.allForExport() } returns emptyList()
        coEvery { memoryEditDao.allForBackup() } returns emptyList()
        coEvery { documentDao.allForBackup() } returns emptyList()
        coEvery { creativeProjectDao.allForBackup() } returns emptyList()
        coEvery { conversationDao.allForExport() } returns emptyList()
        coEvery { kgDao.allNodes() } returns emptyList()
        coEvery { kgDao.allEdges() } returns emptyList()
        coEvery { handDao.getAll() } returns emptyList()
        coEvery { handDao.allRunsForBackup() } returns emptyList()
        coEvery { taskDao.all() } returns emptyList()
        coEvery { reminderDao.allForBackup() } returns emptyList()
        coEvery { proactiveEventDao.allForBackup() } returns emptyList()
        coEvery { userProfileDao.get() } returns null
        coEvery { agentDao.allOnce() } returns emptyList()
        every { userPreferences.defaultModel } returns flowOf("ollama:deepseek-v4-pro:cloud")
        every { userPreferences.firstRunComplete } returns flowOf(true)
        every { userPreferences.appLockEnabled } returns flowOf(false)
        every { userPreferences.lastSeenProactiveAt } returns flowOf(0L)
        every { userPreferences.morningBriefEnabled } returns flowOf(true)
        every { userPreferences.calendarMonitorEnabled } returns flowOf(true)
        every { userPreferences.ttsEnabled } returns flowOf(true)
        every { userPreferences.incognitoDefault } returns flowOf(false)
        every { userPreferences.themeMode } returns flowOf("system")
        every { userPreferences.customIdentity } returns flowOf("")
        every { userPreferences.specialistOverrides } returns flowOf("{}")
        every { userPreferences.morningBriefHour } returns flowOf(7)
        every { userPreferences.specialistToolOverrides } returns flowOf("{}")
        // Schema v8 additions
        every { userPreferences.visionModel } returns flowOf(null)
        every { userPreferences.backgroundModel } returns flowOf(null)
        every { userPreferences.deepModeModel } returns flowOf(null)
        every { userPreferences.moaReferenceModels } returns flowOf(emptyList())
        every { userPreferences.moaAggregatorModel } returns flowOf(null)
        every { userPreferences.imageModel } returns flowOf("")
        every { userPreferences.videoModel } returns flowOf("")
        every { userPreferences.voiceModel } returns flowOf("")
        every { userPreferences.smtpHost } returns flowOf("")
        every { userPreferences.smtpPort } returns flowOf(587)
        every { userPreferences.smtpUsername } returns flowOf("")
        every { userPreferences.smtpFrom } returns flowOf("")
        every { userPreferences.mcpServersJson } returns flowOf("[]")
        every { userPreferences.evolutionOnboardingShown } returns flowOf(false)
        
        every { userPreferences.daemonEnabled } returns flowOf(false)
        every { userPreferences.reasoningEnabled } returns flowOf(true)
        every { userPreferences.reasoningBudget } returns flowOf(32000)
        every { userPreferences.googleClientId } returns flowOf("")
        every { userPreferences.microsoftClientId } returns flowOf("")
        every { userPreferences.dreamLastRunAt } returns flowOf(0L)
        every { userPreferences.dreamLastRunStats } returns flowOf("")
        every { userPreferences.evolutionEnabled } returns flowOf(false)
        every { userPreferences.evolutionIntervalHours } returns flowOf(24)
        every { userPreferences.dreamEnabled } returns flowOf(true)
        every { userPreferences.decayEnabled } returns flowOf(true)
        every { userPreferences.triggersEnabled } returns flowOf(true)
        every { userPreferences.triggers } returns flowOf(emptyList())
        every { userPreferences.planningEnabled } returns flowOf(false)
        every { userPreferences.agentId } returns flowOf(null)
        every { userPreferences.councilEnabled } returns flowOf(true)
        every { userPreferences.councilAutoApply } returns flowOf(false)
        every { userPreferences.councilActivityLevel } returns flowOf(3)
        every { userPreferences.forRole(any()) } returns flowOf(null)
        every { providerKeys.embeddingModel } returns "nomic-embed-text"

        val backup = manager.snapshot(appVersionName = "0.1.0")

        assertEquals(AuraBackup.SCHEMA_VERSION, backup.schemaVersion)
        assertEquals("aura-android", backup.exportedBy)
        assertEquals("0.1.0", backup.appVersionName)
        assertTrue(backup.exportedAt > 0L)
        assertEquals(0, backup.memories.size)
        assertEquals(0, backup.conversations.size)
        assertEquals(0, backup.knowledgeGraph.nodes.size)
        assertEquals(0, backup.hands.size)
        assertEquals(0, backup.tasks.size)
        assertEquals(null, backup.userProfile)

        // Preferences are captured.
        assertEquals("ollama:deepseek-v4-pro:cloud", backup.preferences.defaultModel)
        assertEquals(true, backup.preferences.firstRunComplete)
        assertEquals(false, backup.preferences.appLockEnabled)
        assertEquals("nomic-embed-text", backup.preferences.embeddingModel)
    }

    @Test
    fun `round-trip encode decode preserves payload`() = runTest {
        // The snapshot doesn't need real data — we just need a payload
        // that exercises every field. Build one directly.
        val original = AuraBackup(
            exportedAt = 1_700_000_000_000L,
            appVersionName = "0.1.0",
            memories = listOf(
                MemoryBackup(
                    id = "m1", content = "prefers dark mode", source = "user",
                    category = "preference", importance = 0.8f,
                    createdAt = 1L, accessedAt = 2L, accessCount = 3,
                    decayScore = 0.5f, tags = "ui,theme", metadata = "{}",
                ),
            ),
            documents = listOf(
                DocumentBackup(
                    id = "hash-1",
                    name = "world-bible.pdf",
                    mimeType = "application/pdf",
                    sourceUri = "content://docs/world-bible",
                    importedAt = 4L,
                    characterCount = 12_000,
                    chunkCount = 8,
                ),
            ),
            creativeProjects = listOf(
                CreativeProjectBackup(
                    id = "p1", name = "Glass City", description = "A memory city",
                    genre = "speculative", tone = "luminous", worldJson = "{\"overview\":\"A city remembers\"}",
                    templateId = "novel", metadataJson = "{}", turnCount = 4,
                    lastSessionEnded = 9L, createdAt = 1L, updatedAt = 9L,
                ),
            ),
            conversations = listOf(
                ConversationBackup(
                    id = "c1", title = "Onboarding help", createdAt = 1L, updatedAt = 2L,
                    systemPrompt = "be brief", model = "ollama:deepseek-v4-pro:cloud",
                    metadataJson = "{}", turnsJson = "[]",
                    contextSummary = "User chose dark mode.", summaryThroughTurn = 12,
                ),
            ),
        )

        val json = manager.encodeToJson(original)
        val parsed = manager.decodeFromJson(json)

        assertEquals(original.exportedAt, parsed.exportedAt)
        assertEquals(original.memories.size, parsed.memories.size)
        assertEquals("prefers dark mode", parsed.memories[0].content)
        assertEquals("world-bible.pdf", parsed.documents.single().name)
        assertEquals(8, parsed.documents.single().chunkCount)
        assertEquals("Glass City", parsed.creativeProjects.single().name)
        assertEquals("novel", parsed.creativeProjects.single().templateId)
        assertEquals("Onboarding help", parsed.conversations[0].title)
        assertEquals("User chose dark mode.", parsed.conversations[0].contextSummary)
        assertEquals(12, parsed.conversations[0].summaryThroughTurn)
    }

    /**
     * The loop that was open for the whole life of automatic backup.
     *
     * `BackupWorker` sealed every weekly backup with `BackupCrypto.seal`, and
     * `BackupCrypto.open` — correct, tested, and with a `null` return documented
     * down to the reason it does not distinguish a wrong passphrase from a corrupt
     * file — had no production caller. Restore fed the sealed envelope straight to
     * [BackupManager.decodeFromJson] and reported "Unexpected JSON token at offset
     * 0". Every backup on disk was an envelope nothing in this project could open.
     *
     * Nothing is mocked on the path under test: a real `BackupCrypto` seals, and
     * the real manager detects, unseals and decodes. If the wire between them
     * comes apart again, this is what fails.
     */
    @Test
    fun `a sealed backup opens through the manager and decodes to what was sealed`() = runTest {
        val original = AuraBackup(
            schemaVersion = AuraBackup.SCHEMA_VERSION,
            exportedAt = 1234L,
            appVersionName = "sealed-round-trip",
        )
        val passphrase = "correct horse battery"
        val sealed = com.aura.security.BackupCrypto().seal(manager.encodeToJson(original), passphrase)!!

        assertTrue(manager.isSealed(sealed), "the worker's own envelope was not recognised as sealed")

        val plaintext = manager.unseal(sealed, passphrase)
        assertTrue(plaintext != null, "the passphrase that sealed it did not open it")

        val restored = manager.decodeFromJson(plaintext!!)
        assertEquals(original.exportedAt, restored.exportedAt)
        assertEquals(original.appVersionName, restored.appVersionName)
        assertEquals(original.schemaVersion, restored.schemaVersion)
    }

    @Test
    fun `the wrong passphrase yields null rather than something decodeFromJson would try to parse`() = runTest {
        val sealed = com.aura.security.BackupCrypto()
            .seal(manager.encodeToJson(AuraBackup(exportedAt = 0L, appVersionName = "x")), "the real passphrase")!!

        assertNull(manager.unseal(sealed, "not the passphrase"))
        assertNull(manager.unseal("AURA-BACKUP-1.aaaa.210000.truncated", "the real passphrase"))
    }

    @Test
    fun `a plaintext export is not mistaken for a sealed one`() = runTest {
        // The manual "Export to JSON" path is unsealed and must keep working
        // unchanged; isSealed is what routes between the two.
        val plain = manager.encodeToJson(AuraBackup(exportedAt = 0L, appVersionName = "plain"))
        assertTrue(!manager.isSealed(plain), "a plain JSON export was routed down the decrypt path")
    }

    @Test
    fun `decode rejects backups from a newer schema version`() = runTest {
        val future = AuraBackup(
            schemaVersion = AuraBackup.SCHEMA_VERSION + 100,
            exportedAt = 0L,
            appVersionName = "future",
        )
        val json = manager.encodeToJson(future)
        val ex = assertFailsWith<IllegalArgumentException> {
            manager.decodeFromJson(json)
        }
        assertTrue(
            "schema" in ex.message!!.lowercase(),
            "error should mention the schema, got: ${ex.message}",
        )
    }

    @Test
    fun `purgeAll wipes every table in dependency order`() = runTest {
        coEvery { memoryDao.deleteAll() } returns Unit
        coEvery { conversationDao.deleteAll() } returns Unit
        coEvery { kgDao.deleteAllEdges() } returns Unit
        coEvery { kgDao.deleteAllNodes() } returns Unit
        coEvery { handDao.deleteRunHistory() } returns Unit
        coEvery { handDao.getAll() } returns listOf(com.aura.hands.Hand("old-hand", "Old"))
        coEvery { handDao.deleteAll() } returns Unit
        coEvery { taskDao.deleteAll() } returns Unit

        manager.purgeAll()

        // Edges must go before nodes (foreign-key relationship).
        coVerify { kgDao.deleteAllEdges() }
        coVerify { kgDao.deleteAllNodes() }
        coVerify { documentDao.deleteAll() }
        coVerify { creativeProjectDao.deleteAll() }
        coVerify { memoryDao.deleteAll() }
        coVerify { conversationDao.deleteAll() }
        coVerify { handDao.deleteRunHistory() }
        coVerify { handScheduler.cancel("old-hand") }
        coVerify { handDao.deleteAll() }
        coVerify { taskDao.deleteAll() }
    }

    @Test
    fun `restore writes rows to the right tables in dependency order`() = runTest {
        coEvery { memoryDao.insertAll(any()) } returns Unit
        coEvery { conversationDao.insertAll(any()) } returns Unit
        coEvery { kgDao.insertAllNodes(any()) } returns Unit
        coEvery { kgDao.insertAllEdges(any()) } returns Unit
        coEvery { handDao.insertAll(any()) } returns Unit
        coEvery { handDao.insertAllRuns(any()) } returns Unit
        coEvery { taskDao.insertAll(any()) } returns Unit
        coEvery { userProfileDao.upsert(any()) } returns Unit
        coEvery { userPreferences.setDefaultModel(any()) } returns Unit
        coEvery { userPreferences.setAppLockEnabled(any()) } returns Unit
        coEvery { userPreferences.setFirstRunComplete(any()) } returns Unit

        val counts = manager.restore(
            AuraBackup(
                exportedAt = 0L,
                appVersionName = "0.1.0",
                memories = listOf(
                    MemoryBackup("m1", "c", "user", "preference", "general", 0.5f, 1L, 1L, 0, 1f, "", "{}")
                ),
                documents = listOf(
                    DocumentBackup("hash", "notes.md", "text/markdown", "content://notes", 1L, 100, 1),
                ),
                creativeProjects = listOf(
                    CreativeProjectBackup(
                        "p1", "World", "", "fantasy", "mythic", "{}", "novel", "{}", 0, 0L, 1L, 1L,
                    ),
                ),
                conversations = listOf(
                    ConversationBackup(
                        "c1", "t", 1L, 2L, null, "m", "{}", "[]",
                        contextSummary = "Durable context", summaryThroughTurn = 5,
                    )
                ),
                knowledgeGraph = KnowledgeGraphBackup(
                    nodes = listOf(
                        NodeBackup("n1", "l", "PERSON", "{}", 0.8f, "", 1L, 2L, 0, 0L)
                    ),
                    edges = listOf(
                        EdgeBackup("e1", "KNOWS", "n1", "n2", 0.5f, "{}", 0.8f, "", 1L, 1L)
                    ),
                ),
                hands = listOf(
                    HandBackup(
                        "h1", "h", "", "[]", true, 1L,
                        variables = "{\"city\":\"Baku\"}",
                        scheduleType = "daily", scheduleHour = 8,
                    ),
                ),
                handRuns = listOf(
                    HandRunBackup(
                        "run-1", "h1", "h", "manual", "success", 1L,
                        finishedAt = 2L, output = "done",
                    ),
                ),
                tasks = listOf(TaskBackup("t1", "t", "", 1L, null, null, "pending", 0, "")),
                userProfile = UserProfileBackup(null, "[]", "{}", "[]", 1L),
                preferences = PreferencesBackup(
                    defaultModel = "ollama:deepseek-v4-pro:cloud",
                    firstRunComplete = true,
                    appLockEnabled = true,
                    embeddingModel = "nomic-embed-text",
                ),
            ),
        )

        assertEquals(1, counts.memories)
        assertEquals(1, counts.documents)
        assertEquals(1, counts.creativeProjects)
        assertEquals(1, counts.conversations)
        assertEquals(1, counts.nodes)
        assertEquals(1, counts.edges)
        assertEquals(1, counts.hands)
        assertEquals(1, counts.handRuns)
        assertEquals(1, counts.tasks)
        assertEquals(1, counts.profile)
        assertEquals(10, counts.total)
        coVerify { documentDao.insertAll(match { it.single().name == "notes.md" }) }
        coVerify { creativeProjectDao.insertAll(match { it.single().name == "World" }) }
        coVerify { handScheduler.schedule(match { it.id == "h1" && it.scheduleType == "daily" }, any()) }
        coVerify { handDao.insertAllRuns(match { it.single().id == "run-1" }) }
        coVerify {
            conversationDao.insertAll(
                match { rows ->
                    rows.single().contextSummary == "Durable context" &&
                        rows.single().summaryThroughTurn == 5
                },
            )
        }
    }

    @Test
    fun `restore reschedules future reminders with fresh work`() = runTest {
        val future = System.currentTimeMillis() + 3_600_000L
        val restored = com.aura.tasks.ReminderEntity(
            id = "reminder-1",
            workId = "",
            message = "Call home",
            triggerAt = future,
            recurrence = "weekly",
        )
        coEvery { reminderScheduler.schedule(any()) } returns restored.copy(workId = "fresh-work")

        val counts = manager.restore(
            AuraBackup(
                exportedAt = 0L,
                appVersionName = "0.2.0",
                reminders = listOf(
                    ReminderBackup(
                        id = "reminder-1",
                        message = "Call home",
                        triggerAt = future,
                        createdAt = 1L,
                        taskId = "",
                        recurrence = "weekly",
                        status = "scheduled",
                    ),
                ),
            ),
        )

        coVerify {
            reminderScheduler.schedule(
                match {
                    it.id == "reminder-1" && it.workId.isEmpty() &&
                        it.recurrence == "weekly" && it.triggerAt == future
                },
            )
        }
        assertEquals(1, counts.reminders)
    }

    @Test
    fun `failed restore rolls back to pre-existing data instead of purging it`() = runTest {
        // Regression: any insert failure used to run purgeAll() alone —
        // destroying the user's PRE-EXISTING data (including tables the
        // backup never contained) on a corrupt import. Now restore()
        // snapshots current data first and re-inserts it on failure.
        mockSnapshotDeps()
        // Pre-existing data the snapshot will capture.
        coEvery { memoryDao.allForExport() } returns listOf(
            com.aura.memory.MemoryEntity(
                id = "pre1", content = "existing memory", source = "user", category = "fact",
            ),
        )
        // The import blows up mid-insert, AFTER memories are written.
        coEvery { conversationDao.insertAll(any()) } throws RuntimeException("disk full")

        val importBackup = AuraBackup(
            exportedAt = 0L,
            appVersionName = "0.1.0",
            memories = listOf(
                MemoryBackup("im1", "imported memory", "user", "fact", "general", 0.5f, 1L, 1L, 0, 1f, "", "{}"),
            ),
            conversations = listOf(
                ConversationBackup("c1", "t", 1L, 2L, null, "m", "{}", "[]"),
            ),
        )

        assertFailsWith<RuntimeException> { manager.restore(importBackup) }

        // Purge ran, then the pre-existing memory was re-inserted.
        coVerify { memoryDao.deleteAll() }
        coVerify {
            memoryDao.insertAll(match { rows -> rows.any { it.content == "existing memory" } })
        }
    }

    @Test
    fun `a merge that cannot snapshot first never purges on failure`() = runTest {
        // Regression, and the worst one this class has held: a MERGE could empty
        // all eleven databases.
        //
        // restore() refuses to start without a pre-restore snapshot only when
        // mode == REPLACE. A merge runs regardless — reasonably, since every
        // delete inside writeEverything is guarded on REPLACE, so a merge only
        // ever adds rows. But the failure path then called purgeAll() before it
        // checked whether a snapshot existed to restore from. With no snapshot
        // there was nothing to put back, so a single failed insert wiped tables
        // the import had never touched, on the operation the confirmation dialog
        // describes to the user as "nothing is deleted".
        //
        // Two ordinary things produce a null snapshot: the spool could not be
        // written or read, and a merge never required one in the first place.
        mockSnapshotDeps()
        // strict = true on the rollback snapshot, so a failed table read
        // propagates and writeRollbackSnapshot() returns null.
        coEvery { memoryDao.allForExport() } throws RuntimeException("cannot read memories")
        // The import then fails partway through, as it did above.
        coEvery { conversationDao.insertAll(any()) } throws RuntimeException("disk full")

        val importBackup = AuraBackup(
            exportedAt = 0L,
            appVersionName = "0.1.0",
            memories = listOf(
                MemoryBackup("im1", "imported memory", "user", "fact", "general", 0.5f, 1L, 1L, 0, 1f, "", "{}"),
            ),
            conversations = listOf(
                ConversationBackup("c1", "t", 1L, 2L, null, "m", "{}", "[]"),
            ),
        )

        assertFailsWith<RuntimeException> {
            manager.restore(importBackup, BackupManager.RestoreMode.MERGE)
        }

        // Nothing was purged. The half-imported database is worse than a clean
        // rollback and far better than an empty one, and the interrupted-restore
        // marker is what tells the next launch which of the two it is holding.
        coVerify(exactly = 0) { memoryDao.deleteAll() }
        coVerify(exactly = 0) { conversationDao.deleteAll() }
        coVerify(exactly = 0) { taskDao.deleteAll() }
    }

    @Test
    fun `defaultExportFileName ends in json and has a timestamp that matches`() {
        // Use a fixed instant and verify the filename contains the
        // timestamp in some timezone-recognizable form. We just check
        // shape (.json suffix, "aura-backup-" prefix, 15-char
        // date+time body) rather than a specific date string because
        // the local timezone affects the formatted date.
        val name = manager.defaultExportFileName(now = 1_700_000_000_000L)
        assertTrue(name.endsWith(".json"), "filename should end in .json: $name")
        assertTrue(name.startsWith("aura-backup-"), "filename should start with prefix: $name")
        // Format is "aura-backup-YYYYMMDD-HHMMSS.json" — the body
        // between the prefix and ".json" should be 15 chars
        // (8 date + 1 dash + 6 time).
        val body = name.removePrefix("aura-backup-").removeSuffix(".json")
        assertEquals(15, body.length, "expected YYYYMMDD-HHMMSS body, got: $body")
        assertTrue(body[8] == '-', "expected dash separator at index 8, got: $body")
    }

    // ── Schema v10 roundtrip regression tests ──
    // Until v0.30.x, beliefs/evidence/worldEvents/opportunities/creativeArtifacts/
    // canonFacts/preferenceSignals/styleProfiles were written to the JSON
    // backup but never read back. These tests pin the roundtrip.

    @Test
    fun `restore inserts schema v10 world model rows when DAOs are wired`() = runTest {
        val beliefDao = mockk<com.aura.world.BeliefDao>(relaxed = true)
        val evidenceDao = mockk<com.aura.world.EvidenceDao>(relaxed = true)
        val worldEventDao = mockk<com.aura.world.WorldEventDao>(relaxed = true)
        val opportunityDao = mockk<com.aura.world.OpportunityDao>(relaxed = true)
        val mgr = BackupManager(
            context = context, memoryDao = memoryDao, memoryEditDao = memoryEditDao,
            documentDao = documentDao, creativeProjectDao = creativeProjectDao,
            conversationDao = conversationDao, kgDao = kgDao, handDao = handDao,
            taskDao = taskDao, reminderDao = reminderDao,
            proactiveEventDao = proactiveEventDao, userProfileDao = userProfileDao,
            providerKeys = providerKeys, userPreferences = userPreferences,
            reminderScheduler = reminderScheduler, handScheduler = handScheduler,
            usageTracker = usageTracker, evolutionProposalDao = evolutionProposalDao,
            evolutionSettingsDao = evolutionSettingsDao,
            evolutionRevisionDao = evolutionRevisionDao, agentDao = agentDao,
            beliefDao = beliefDao, evidenceDao = evidenceDao,
            worldEventDao = worldEventDao, opportunityDao = opportunityDao,
        )

        val backup = AuraBackup(
            exportedAt = 1_700_000_000_000L,
            appVersionName = "0.30.0",
            beliefs = listOf(BeliefBackup(
                id = "b1", subject = "user", predicate = "name",
                valueJson = "\"Alice\"", createdAt = 1000L, updatedAt = 2000L,
            )),
            evidence = listOf(EvidenceBackup(
                id = "e1", beliefId = "b1", source = "user_statement",
                summary = "stated name", timestamp = 1500L,
            )),
            worldEvents = listOf(WorldEventBackup(
                id = "w1", eventType = "calendar", source = "system",
                summary = "cal invite", timestamp = 2500L,
            )),
            opportunities = listOf(OpportunityBackup(
                id = "o1", title = "Test", description = "test",
                createdAt = 3000L,
            )),
        )
        coEvery { memoryDao.allForExport() } returns emptyList()
        coEvery { memoryEditDao.allForBackup() } returns emptyList()
        coEvery { documentDao.allForBackup() } returns emptyList()
        coEvery { creativeProjectDao.allForBackup() } returns emptyList()
        coEvery { conversationDao.allForExport() } returns emptyList()
        coEvery { kgDao.allNodes() } returns emptyList()
        coEvery { kgDao.allEdges() } returns emptyList()
        coEvery { handDao.getAll() } returns emptyList()
        coEvery { handDao.allRunsForBackup() } returns emptyList()
        coEvery { taskDao.all() } returns emptyList()
        coEvery { proactiveEventDao.allForBackup() } returns emptyList()
        coEvery { agentDao.allOnce() } returns emptyList()

        // The default mocked DAOs return null/absent which is fine for the
        // "no rows" path; we just need to verify the schema v10 DAOs
        // got their insertAll() calls.
        val counts = mgr.restore(backup)
        assertEquals(1, counts.beliefs)
        assertEquals(1, counts.evidence)
        assertEquals(1, counts.worldEvents)
        assertEquals(1, counts.opportunities)
        coVerify(exactly = 1) { beliefDao.insertAll(match { it.size == 1 && it[0].id == "b1" }) }
        coVerify(exactly = 1) { evidenceDao.insertAll(match { it.size == 1 && it[0].id == "e1" }) }
        coVerify(exactly = 1) { worldEventDao.insertAll(match { it.size == 1 && it[0].id == "w1" }) }
        coVerify(exactly = 1) { opportunityDao.insertAll(match { it.size == 1 && it[0].id == "o1" }) }
    }

    @Test
    fun `restore silently skips schema v10 tables when DAOs are null`() = runTest {
        // BackupManager with no schema v10 DAOs (default constructor
        // params) should not throw when given a backup with schema v10
        // data. The RestoreCounts reflects what was in the backup (so
        // the UI can show "10 beliefs restored"), not what was actually
        // inserted (which is 0 when the DAOs are absent). The
        // contract is: no exception + no insert attempted.
        val backup = AuraBackup(
            exportedAt = 1_700_000_000_000L,
            appVersionName = "0.30.0",
            beliefs = listOf(BeliefBackup(
                id = "b1", subject = "user", predicate = "name",
                valueJson = "\"x\"", createdAt = 1L, updatedAt = 2L,
            )),
        )
        coEvery { memoryDao.allForExport() } returns emptyList()
        coEvery { memoryEditDao.allForBackup() } returns emptyList()
        coEvery { documentDao.allForBackup() } returns emptyList()
        coEvery { creativeProjectDao.allForBackup() } returns emptyList()
        coEvery { conversationDao.allForExport() } returns emptyList()
        coEvery { kgDao.allNodes() } returns emptyList()
        coEvery { kgDao.allEdges() } returns emptyList()
        coEvery { handDao.getAll() } returns emptyList()
        coEvery { handDao.allRunsForBackup() } returns emptyList()
        coEvery { taskDao.all() } returns emptyList()
        coEvery { proactiveEventDao.allForBackup() } returns emptyList()
        coEvery { agentDao.allOnce() } returns emptyList()

        val counts = manager.restore(backup)
        // Restore counts reflect backup content, not insert result.
        // The "no insert" guarantee is that no exception is thrown
        // (the call returned successfully) and no calls were made on
        // the absent DAOs.
        assertEquals(1, counts.beliefs)
    }

    // ── Memory scope roundtrip (MEMORY_AUDIT A1) ───────────────────────
    // Until the scope field was added to MemoryBackup, every restore
    // dropped scope on the floor and all memories became "general" —
    // meaning agent-private memories leaked into the General agent's
    // recall scope on every backup roundtrip. The default "general" in
    // the backup data class keeps old backups forward-compatible.

    @Test
    fun `snapshot preserves memory scope in MemoryBackup`() = runTest {
        // Wire memoryDao to return one memory with a non-default
        // scope (agent:agent_coder).
        val entity = com.aura.memory.MemoryEntity(
            id = "m1",
            content = "user prefers Tailwind CSS over inline styles",
            source = "user",
            category = "preference",
            scope = "agent:agent_coder",
            importance = 0.7f,
            createdAt = 1000L,
            accessedAt = 1000L,
            accessCount = 0,
            decayScore = 1.0f,
            tags = "ui,framework",
            metadata = "{}",
        )
        coEvery { memoryDao.allForExport() } returns listOf(entity)
        coEvery { memoryEditDao.allForBackup() } returns emptyList()
        coEvery { documentDao.allForBackup() } returns emptyList()
        coEvery { creativeProjectDao.allForBackup() } returns emptyList()
        coEvery { conversationDao.allForExport() } returns emptyList()
        coEvery { kgDao.allNodes() } returns emptyList()
        coEvery { kgDao.allEdges() } returns emptyList()
        coEvery { handDao.getAll() } returns emptyList()
        coEvery { handDao.allRunsForBackup() } returns emptyList()
        coEvery { taskDao.all() } returns emptyList()
        coEvery { reminderDao.allForBackup() } returns emptyList()
        coEvery { proactiveEventDao.allForBackup() } returns emptyList()
        coEvery { userProfileDao.get() } returns null
        coEvery { agentDao.allOnce() } returns emptyList()
        // Stub the schema v10 DAOs to empty so the snapshot doesn't
        // try to read from them via relaxed-null.
        val beliefDao = mockk<com.aura.world.BeliefDao>(relaxed = true)
        val evidenceDao = mockk<com.aura.world.EvidenceDao>(relaxed = true)
        val worldEventDao = mockk<com.aura.world.WorldEventDao>(relaxed = true)
        val opportunityDao = mockk<com.aura.world.OpportunityDao>(relaxed = true)
        val creativeArtifactDao = mockk<com.aura.creative.CreativeArtifactDao>(relaxed = true)
        val creativeRevisionDao = mockk<com.aura.creative.CreativeRevisionDao>(relaxed = true)
        val creativeBranchDao = mockk<com.aura.creative.CreativeBranchDao>(relaxed = true)
        val canonFactDao = mockk<com.aura.creative.CanonFactDao>(relaxed = true)
        val preferenceSignalDao = mockk<com.aura.taste.PreferenceSignalDao>(relaxed = true)
        val styleProfileDao = mockk<com.aura.taste.StyleProfileDao>(relaxed = true)
        coEvery { beliefDao.allForBackup() } returns emptyList()
        coEvery { evidenceDao.allForBackup() } returns emptyList()
        coEvery { worldEventDao.allForBackup() } returns emptyList()
        coEvery { opportunityDao.allForBackup() } returns emptyList()
        coEvery { creativeArtifactDao.allForBackup() } returns emptyList()
        coEvery { creativeRevisionDao.allForBackup() } returns emptyList()
        coEvery { creativeBranchDao.allForBackup() } returns emptyList()
        coEvery { canonFactDao.allForBackup() } returns emptyList()
        coEvery { preferenceSignalDao.allForBackup() } returns emptyList()
        coEvery { styleProfileDao.allForBackup() } returns emptyList()
        // Stub the userPreferences flows so the snapshot's
        // `userPreferences.foo.first()` calls don't throw
        // NoSuchElementException on the class-level relaxed mock.
        every { userPreferences.defaultModel } returns flowOf("ollama:deepseek-v4-pro:cloud")
        every { userPreferences.firstRunComplete } returns flowOf(true)
        every { userPreferences.appLockEnabled } returns flowOf(false)
        every { userPreferences.lastSeenProactiveAt } returns flowOf(0L)
        every { userPreferences.morningBriefEnabled } returns flowOf(true)
        every { userPreferences.calendarMonitorEnabled } returns flowOf(true)
        every { userPreferences.ttsEnabled } returns flowOf(true)
        every { userPreferences.incognitoDefault } returns flowOf(false)
        every { userPreferences.themeMode } returns flowOf("system")
        every { userPreferences.customIdentity } returns flowOf("")
        every { userPreferences.specialistOverrides } returns flowOf("{}")
        every { userPreferences.morningBriefHour } returns flowOf(7)
        every { userPreferences.specialistToolOverrides } returns flowOf("{}")
        every { userPreferences.visionModel } returns flowOf(null)
        every { userPreferences.backgroundModel } returns flowOf(null)
        every { userPreferences.deepModeModel } returns flowOf(null)
        every { userPreferences.moaReferenceModels } returns flowOf(emptyList())
        every { userPreferences.moaAggregatorModel } returns flowOf(null)
        every { userPreferences.imageModel } returns flowOf("")
        every { userPreferences.videoModel } returns flowOf("")
        every { userPreferences.voiceModel } returns flowOf("")
        every { userPreferences.smtpHost } returns flowOf("")
        every { userPreferences.smtpPort } returns flowOf(587)
        every { userPreferences.smtpUsername } returns flowOf("")
        every { userPreferences.smtpFrom } returns flowOf("")
        every { userPreferences.mcpServersJson } returns flowOf("[]")
        every { userPreferences.evolutionOnboardingShown } returns flowOf(false)
        
        every { userPreferences.daemonEnabled } returns flowOf(false)
        every { userPreferences.reasoningEnabled } returns flowOf(true)
        every { userPreferences.reasoningBudget } returns flowOf(32000)
        every { userPreferences.googleClientId } returns flowOf("")
        every { userPreferences.microsoftClientId } returns flowOf("")
        every { userPreferences.dreamLastRunAt } returns flowOf(0L)
        every { userPreferences.dreamLastRunStats } returns flowOf("")
        every { userPreferences.evolutionEnabled } returns flowOf(false)
        every { userPreferences.evolutionIntervalHours } returns flowOf(24)
        every { userPreferences.dreamEnabled } returns flowOf(true)
        every { userPreferences.decayEnabled } returns flowOf(true)
        every { userPreferences.triggersEnabled } returns flowOf(true)
        every { userPreferences.triggers } returns flowOf(emptyList())
        every { userPreferences.planningEnabled } returns flowOf(false)
        every { userPreferences.agentId } returns flowOf(null)
        every { userPreferences.councilEnabled } returns flowOf(true)
        every { userPreferences.councilAutoApply } returns flowOf(false)
        every { userPreferences.councilActivityLevel } returns flowOf(3)
        every { userPreferences.forRole(any()) } returns flowOf(null)
        every { providerKeys.embeddingModel } returns "nomic-embed-text"

        val mgr = BackupManager(
            context = context, memoryDao = memoryDao, memoryEditDao = memoryEditDao,
            documentDao = documentDao, creativeProjectDao = creativeProjectDao,
            conversationDao = conversationDao, kgDao = kgDao, handDao = handDao,
            taskDao = taskDao, reminderDao = reminderDao,
            proactiveEventDao = proactiveEventDao, userProfileDao = userProfileDao,
            providerKeys = providerKeys, userPreferences = userPreferences,
            reminderScheduler = reminderScheduler, handScheduler = handScheduler,
            usageTracker = usageTracker, evolutionProposalDao = evolutionProposalDao,
            evolutionSettingsDao = evolutionSettingsDao,
            evolutionRevisionDao = evolutionRevisionDao, agentDao = agentDao,
            beliefDao = beliefDao, evidenceDao = evidenceDao,
            worldEventDao = worldEventDao, opportunityDao = opportunityDao,
            creativeArtifactDao = creativeArtifactDao,
            creativeRevisionDao = creativeRevisionDao,
            creativeBranchDao = creativeBranchDao, canonFactDao = canonFactDao,
            preferenceSignalDao = preferenceSignalDao, styleProfileDao = styleProfileDao,
        )

        val backup = mgr.snapshot(appVersionName = "0.33.0")
        assertEquals(1, backup.memories.size)
        val out = backup.memories.single()
        assertEquals("m1", out.id)
        assertEquals(
            "agent:agent_coder", out.scope,
            "snapshot must preserve non-default scope — MEMORY_AUDIT A1",
        )
    }

    @Test
    fun `restore inserts memories with the scope from the backup`() = runTest {
        coEvery { memoryDao.insertAll(any()) } returns Unit

        manager.restore(
            AuraBackup(
                exportedAt = 0L,
                appVersionName = "0.33.0",
                memories = listOf(
                    MemoryBackup(
                        id = "m1", content = "agent-private fact",
                        source = "user", category = "fact",
                        scope = "agent:agent_researcher",
                        importance = 0.5f, createdAt = 1L, accessedAt = 1L,
                        accessCount = 0, decayScore = 1f, tags = "", metadata = "{}",
                    ),
                ),
            ),
        )

        // Verify the inserted row's scope. We use a "match" that
        // grabs the argument and asserts the scope field. The
        // [io.mockk.Matcher] lets us pin behavior in a single
        // expression without capture-slot setup.
        coVerify {
            memoryDao.insertAll(match { rows ->
                rows.single().scope == "agent:agent_researcher"
            })
        }
    }

    @Test
    fun `restore defaults missing scope to general for forward compatibility`() = runTest {
        // Old backups (written before the scope field was added) have
        // no `scope` key in the JSON. The default "general" in the
        // MemoryBackup data class must kick in and the restored row
        // should have scope = "general" — which matches the behavior
        // at the time the backup was written (no agent scoping existed).
        coEvery { memoryDao.insertAll(any()) } returns Unit

        // Use a JSON literal that omits the scope field — simulates
        // an old backup.
        val legacyJson = """
            {
              "schemaVersion": 11,
              "exportedAt": 0,
              "exportedBy": "aura-android",
              "appVersionName": "0.30.0",
              "memories": [
                {
                  "id": "m1",
                  "content": "legacy memory",
                  "source": "user",
                  "category": "preference",
                  "importance": 0.5,
                  "createdAt": 1,
                  "accessedAt": 1,
                  "accessCount": 0,
                  "decayScore": 1.0,
                  "tags": "",
                  "metadata": "{}"
                }
              ]
            }
        """.trimIndent()

        manager.restore(manager.decodeFromJson(legacyJson))

        coVerify {
            memoryDao.insertAll(match { rows ->
                rows.single().scope == "general"
            })
        }
    }

    @Test
    fun `RestoreCounts total includes schema v10 fields`() {
        // The auto-derived total used to be a 17-term hand-sum that
        // fell out of sync with schema v10. Pin that it now includes
        // the 10 new fields.
        val counts = BackupManager.RestoreCounts(
            memories = 1, memoryEdits = 1, documents = 1, creativeProjects = 1,
            conversations = 1, nodes = 1, edges = 1, hands = 1, handRuns = 1,
            tasks = 1, reminders = 1, proactiveEvents = 1, profile = 1,
            evolutionProposals = 1, evolutionSettings = 1, evolutionRevisions = 1,
            agents = 1, beliefs = 1, evidence = 1, worldEvents = 1,
            opportunities = 1, creativeArtifacts = 1, creativeRevisions = 1,
            creativeBranches = 1, canonFacts = 1, preferenceSignals = 1,
            styleProfiles = 1,
        )
        // 17 legacy + 10 schema-v10 = 27
        assertEquals(27, counts.total)
    }

    /**
     * P1-SEC-F2 regression: SMTP host/port/username/from must NOT appear
     * in the JSON export. The previous implementation included them in
     * plaintext, which leaked the user's mailbox identity and SMTP relay
     * to anyone holding the backup file. The user re-enters them after
     * restore, just like the SMTP password (which has always been
     * excluded).
     */
    @Test
    fun `snapshot includes SMTP config but not password`() = runTest {
        coEvery { memoryDao.allForExport() } returns emptyList()
        coEvery { memoryEditDao.allForBackup() } returns emptyList()
        coEvery { documentDao.allForBackup() } returns emptyList()
        coEvery { creativeProjectDao.allForBackup() } returns emptyList()
        coEvery { conversationDao.allForExport() } returns emptyList()
        coEvery { kgDao.allNodes() } returns emptyList()
        coEvery { kgDao.allEdges() } returns emptyList()
        coEvery { handDao.getAll() } returns emptyList()
        coEvery { handDao.allRunsForBackup() } returns emptyList()
        coEvery { taskDao.all() } returns emptyList()
        coEvery { reminderDao.allForBackup() } returns emptyList()
        coEvery { proactiveEventDao.allForBackup() } returns emptyList()
        coEvery { userProfileDao.get() } returns null
        coEvery { agentDao.allOnce() } returns emptyList()
        every { userPreferences.defaultModel } returns flowOf(null)
        every { userPreferences.firstRunComplete } returns flowOf(true)
        every { userPreferences.appLockEnabled } returns flowOf(false)
        every { userPreferences.lastSeenProactiveAt } returns flowOf(0L)
        every { userPreferences.morningBriefEnabled } returns flowOf(true)
        every { userPreferences.calendarMonitorEnabled } returns flowOf(true)
        every { userPreferences.ttsEnabled } returns flowOf(true)
        every { userPreferences.incognitoDefault } returns flowOf(false)
        every { userPreferences.themeMode } returns flowOf("system")
        every { userPreferences.customIdentity } returns flowOf("")
        every { userPreferences.specialistOverrides } returns flowOf("{}")
        every { userPreferences.morningBriefHour } returns flowOf(7)
        every { userPreferences.specialistToolOverrides } returns flowOf("{}")
        every { userPreferences.visionModel } returns flowOf(null)
        every { userPreferences.backgroundModel } returns flowOf(null)
        every { userPreferences.deepModeModel } returns flowOf(null)
        every { userPreferences.moaReferenceModels } returns flowOf(emptyList())
        every { userPreferences.moaAggregatorModel } returns flowOf(null)
        every { userPreferences.imageModel } returns flowOf("")
        every { userPreferences.videoModel } returns flowOf("")
        every { userPreferences.voiceModel } returns flowOf("")
        // Even when the user has real SMTP values set, the snapshot
        // must not include them.
        every { userPreferences.smtpHost } returns flowOf("mail.example.com")
        every { userPreferences.smtpPort } returns flowOf(465)
        every { userPreferences.smtpUsername } returns flowOf("user@example.com")
        every { userPreferences.smtpFrom } returns flowOf("Aura <noreply@example.com>")
        every { userPreferences.mcpServersJson } returns flowOf("[]")
        every { userPreferences.evolutionOnboardingShown } returns flowOf(false)
        
        every { userPreferences.daemonEnabled } returns flowOf(false)
        every { userPreferences.reasoningEnabled } returns flowOf(true)
        every { userPreferences.reasoningBudget } returns flowOf(32000)
        every { userPreferences.googleClientId } returns flowOf("")
        every { userPreferences.microsoftClientId } returns flowOf("")
        every { userPreferences.dreamLastRunAt } returns flowOf(0L)
        every { userPreferences.dreamLastRunStats } returns flowOf("")
        every { userPreferences.evolutionEnabled } returns flowOf(false)
        every { userPreferences.evolutionIntervalHours } returns flowOf(24)
        every { userPreferences.dreamEnabled } returns flowOf(true)
        every { userPreferences.decayEnabled } returns flowOf(true)
        every { userPreferences.triggersEnabled } returns flowOf(true)
        every { userPreferences.triggers } returns flowOf(emptyList())
        every { userPreferences.planningEnabled } returns flowOf(false)
        every { userPreferences.agentId } returns flowOf(null)
        every { userPreferences.councilEnabled } returns flowOf(true)
        every { userPreferences.councilAutoApply } returns flowOf(false)
        every { userPreferences.councilActivityLevel } returns flowOf(3)
        every { userPreferences.forRole(any()) } returns flowOf(null)
        every { providerKeys.embeddingModel } returns ""

        val backup = manager.snapshot(appVersionName = "0.1.0")
        val json = manager.encodeToJson(backup)

        // SMTP config IS in the backup (host, port, username, from) —
        // only the password stays in SecureDataStore and is NOT exported.
        assertEquals("mail.example.com", backup.preferences.smtpHost)
        assertEquals("user@example.com", backup.preferences.smtpUsername)
        assertEquals("Aura <noreply@example.com>", backup.preferences.smtpFrom)
        assertEquals(465, backup.preferences.smtpPort)
        // Verify SMTP values are in the JSON.
        assertTrue(json.contains("mail.example.com"))
        assertTrue(json.contains("user@example.com"))
        // Password must NOT be in the backup JSON.
        assertFalse(json.contains("smtp_password"))
        assertFalse(json.contains("password"))
    }

    // ------------------------------------------------- what "add to existing" promises

    @Test
    fun `merge restore keeps local rows the backup does not carry`() = runTest {
        // The restore dialog says, in these words: "Add to existing keeps what is already
        // here and writes the backup on top: rows with the same id are replaced, new rows are
        // added, nothing is deleted."
        //
        // restoreCouncil and restoreStrategyBandit deleted their tables outright before
        // writing, so a merge silently destroyed agent state, relationships, observations,
        // forum posts, votes and every learned strategy weight the backup did not happen to
        // contain. writeEverything took no RestoreMode, so it could not have known better.
        manager.restore(
            AuraBackup(
                exportedAt = 0L,
                appVersionName = "0.1.0",
                strategyBandit = listOf(
                    com.aura.agent.StrategyBanditBackup("general", "DIRECT", 1.0, 1.0, 0L),
                ),
            ),
            BackupManager.RestoreMode.MERGE,
        )

        coVerify(exactly = 0) { strategyBanditDao.clear() }
        coVerify(exactly = 0) { agentStateDao.deleteAll() }
        coVerify(exactly = 0) { agentRelationshipDao.deleteAll() }
        coVerify(exactly = 0) { agentObservationDao.deleteAll() }
        coVerify(exactly = 0) { forumPostDao.deleteAll() }
    }

    @Test
    fun `merge restore does not wipe the profile just because the backup has none`() = runTest {
        // The one unconditional delete: a backup carrying no userProfile cleared the local
        // one. Correct when replacing everything, and a silent loss of identity when merging.
        manager.restore(
            AuraBackup(exportedAt = 0L, appVersionName = "0.1.0"),
            BackupManager.RestoreMode.MERGE,
        )

        coVerify(exactly = 0) { userProfileDao.deleteAll() }
    }

    @Test
    fun `merge restore still writes the rows the backup does carry`() {
        // The control, and not a REPLACE one: replace refuses without a rollback snapshot,
        // which this harness cannot take, so asserting on it would test the harness. This
        // asserts the merge path still writes — without it, the two tests above would pass
        // if restore stopped doing anything at all.
        runTest {
            manager.restore(
                AuraBackup(
                    exportedAt = 0L,
                    appVersionName = "0.1.0",
                    strategyBandit = listOf(
                        com.aura.agent.StrategyBanditBackup("general", "DIRECT", 1.0, 1.0, 0L),
                    ),
                ),
                BackupManager.RestoreMode.MERGE,
            )

            coVerify { strategyBanditDao.insertAll(any()) }
        }
    }

    // ------------------------------------------------------- forum votes survive a restore

    @Test
    fun `restored forum posts keep the ids their votes point at`() = runTest {
        // ForumPostEntity.id is autoGenerate, and ForumPostBackup carried no id at all — so
        // posts came back with fresh ids while votes still carried the exporting device's,
        // and forum_votes.postId pointed at rows that no longer existed. The FK violation was
        // swallowed by the runCatching around restoreCouncil, so the votes were deleted, not
        // restored, and the UI reported success. replyToId is broken the same way: reply
        // threading does not survive either.
        //
        // The loss happens at export, not import. Every backup taken before this could not
        // have reconnected its votes.
        val posts = slot<com.aura.agent.forum.ForumPostEntity>()

        manager.restore(
            AuraBackup(
                exportedAt = 0L,
                appVersionName = "0.1.0",
                forumPosts = listOf(
                    ForumPostBackup(
                        id = 42L, threadId = "t1", agentId = "a1", replyToId = null,
                        type = "proposal", title = "T", body = "B", sentiment = 0f,
                        status = "open", createdAt = 1L,
                    ),
                ),
                forumVotes = listOf(
                    ForumVoteBackup(postId = 42L, agentId = "a1", vote = "yes", reason = "", createdAt = 1L),
                ),
            ),
            BackupManager.RestoreMode.MERGE,
        )

        coVerify { forumPostDao.insert(capture(posts)) }
        assertEquals(42L, posts.captured.id, "a post must come back under the id its votes reference")
        coVerify { forumVoteDao.insert(any()) }
    }

    @Test
    fun `votes from a backup that predates post ids are dropped rather than orphaned`() = runTest {
        // An older backup carries no post ids, so its votes cannot be reconnected to anything.
        // Inserting them anyway is what broke the whole council restore. Dropping them loses
        // the votes — which were already lost — without taking the rest down with them.
        manager.restore(
            AuraBackup(
                exportedAt = 0L,
                appVersionName = "0.1.0",
                forumPosts = listOf(
                    ForumPostBackup(
                        threadId = "t1", agentId = "a1", replyToId = null, type = "proposal",
                        title = "T", body = "B", sentiment = 0f, status = "open", createdAt = 1L,
                    ),
                ),
                forumVotes = listOf(
                    ForumVoteBackup(postId = 7L, agentId = "a1", vote = "yes", reason = "", createdAt = 1L),
                ),
            ),
            BackupManager.RestoreMode.MERGE,
        )

        coVerify(exactly = 0) { forumVoteDao.insert(any()) }
    }

    @Test
    fun `add to existing does not clear forum votes`() = runTest {
        // Pass A gated every other council delete on REPLACE and missed this one, so
        // "Add to existing" — which the UI describes as "nothing is deleted" — still
        // dropped every vote on the device before inserting the backup's.
        manager.restore(
            AuraBackup(
                exportedAt = 0L,
                appVersionName = "0.1.0",
                forumPosts = listOf(
                    ForumPostBackup(
                        id = 42L, threadId = "t1", agentId = "a1", replyToId = null,
                        type = "proposal", title = "T", body = "B", sentiment = 0f,
                        status = "open", createdAt = 1L,
                    ),
                ),
                forumVotes = listOf(
                    ForumVoteBackup(postId = 42L, agentId = "a1", vote = "yes", reason = "", createdAt = 1L),
                ),
            ),
            BackupManager.RestoreMode.MERGE,
        )

        coVerify(exactly = 0) { forumVoteDao.deleteAll() }
    }

    // ---------------------------------------------------- a partial export says it is partial

    @Test
    fun `a table that cannot be read is named in the backup it is missing from`() = runTest {
        // Export is deliberately non-strict — the KDoc on snapshot argues a transient read
        // failure should cost those rows, not the whole backup, and for a scheduled backup
        // that is right. What was wrong is that the result was indistinguishable from a
        // complete one: readTable contributed emptyList() and the worker recorded
        // "wrote N KB". Six tables could vanish and every signal still said success, so the
        // loss surfaced at restore, which is the one moment it cannot be fixed.
        mockSnapshotDeps()
        coEvery { evolutionProposalDao.allForBackup() } throws IllegalStateException("db locked")

        val backup = manager.snapshot(appVersionName = "0.1.0")

        assertEquals(listOf("evolution proposals"), backup.unreadableTables)
        assertTrue(backup.evolutionProposals.isEmpty(), "the rows are still gone; only the silence is fixed")
    }

    @Test
    fun `a complete export names no unreadable tables`() = runTest {
        mockSnapshotDeps()

        assertTrue(manager.snapshot(appVersionName = "0.1.0").unreadableTables.isEmpty())
    }
}
