package com.aura.dream

import com.aura.agent.ConversationStore
import com.aura.core.error.CrashLogger
import com.aura.kg.KnowledgeGraphRepository
import com.aura.memory.Embedder
import com.aura.memory.MemoryEntity
import com.aura.memory.MemoryStore
import com.aura.profile.UserProfileStore
import com.aura.providers.FinishReason
import com.aura.providers.ProviderChunk
import com.aura.providers.ProviderRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the [DreamConsolidator] orchestration class.
 *
 * Scope: the parts worth pinning - the cluster threshold, the
 * empty-input fast-path, and the LLM-failure safe-fallback. The
 * cosine math is verified by [MemoryStoreCosineTest] in the
 * memory package; we only assert here that [DreamConsolidator]
 * uses it correctly.
 *
 * Test doubles:
 *  - [MemoryStore] is mockk with explicit overrides for `recent()`
 *    and `cosineSimilarity()`
 *  - [ProviderRegistry.chat] returns a [Flow]&lt;[ProviderChunk]&gt;
 *    with the response text and a `stop` finish reason
 *  - [DreamConsolidationDao.insert] is a no-op
 */
class DreamConsolidatorTest {

    /**
     * A memory with a pre-baked 384-dim embedding. The vector
     * encodes a single 32-bit "tag" repeated 12 times (12 * 32 =
     * 384 dims) so similar tags produce identical vectors and
     * dissimilar tags produce orthogonal vectors. Real embeddings
     * are normalized floats; this is enough to drive cosine.
     */
    private fun mem(
        id: String,
        content: String,
        tag: Int,
    ): MemoryEntity {
        val vec = FloatArray(384) { idx ->
            if (tag <= 0) 0f
            else if ((idx / 32) == (tag - 1) % 12) 1f
            else 0f
        }
        val bytes = Embedder.toBytes(vec)
        return MemoryEntity(
            id = id,
            content = content,
            source = "user",
            category = "fact",
            embedding = bytes,
        )
    }

    private fun mockStore(mems: List<MemoryEntity>): MemoryStore = mockk<MemoryStore>(relaxed = true).also { store ->
        coEvery { store.recent(any()) } returns mems
    }

    private fun mockProvider(summaries: List<String>): ProviderRegistry = mockk<ProviderRegistry>(relaxed = true).also { reg ->
        val flows = summaries.map { text ->
            flowOf(
                ProviderChunk(text = text),
                ProviderChunk(finishReason = FinishReason.stop),
            )
        }
        coEvery { reg.chat(any(), any(), any(), any()) } returnsMany flows
    }

    private fun mockProviderFailing(): ProviderRegistry = mockk<ProviderRegistry>(relaxed = true).also { reg ->
        coEvery { reg.chat(any(), any(), any(), any()) } throws RuntimeException("network down")
    }

    private fun mockDao(): DreamConsolidationDao = mockk<DreamConsolidationDao>(relaxed = true)

    /**
     * Build a DreamConsolidator with all the v2-phase dependencies
     * stubbed out. The empty memories and provider stubs are
     * passed in; the new DAOs and Lazy providers are mocked to
     * no-op so the test focuses on the cluster/summarize logic
     * without exercising the routine/contradiction/densify paths.
     */
    private fun buildConsolidator(
        memoryStore: MemoryStore,
        provider: ProviderRegistry,
        dreamDao: DreamConsolidationDao = mockDao(),
        contradictionDao: ContradictionDao = mockk(relaxed = true),
        narrativeSelf: com.aura.consciousness.NarrativeSelf? = null,
    ): DreamConsolidator = DreamConsolidator(
        memoryStore = memoryStore,
        dreamDao = dreamDao,
        routineDao = mockk(relaxed = true),
        kgProposalDao = mockk(relaxed = true),
        contradictionDao = contradictionDao,
        narrativeSelf = narrativeSelf,
        providerRegistry = provider,
        embedder = mockk(relaxed = true),
        crashLogger = mockk<CrashLogger>(relaxed = true).also {
            // CrashLogger.logException is called from inside catch blocks
            // and uses android.util.Log under the hood; the JVM-unit
            // relaxed mock satisfies both.
            every { it.logException(any(), any()) } returns Unit
        },
        conversationStoreProvider = dagger.Lazy { mockk<ConversationStore>(relaxed = true) },
        userProfileStoreProvider = dagger.Lazy {
            mockk<UserProfileStore>(relaxed = true).also { store ->
                coEvery { store.awaitLoaded() } returns Unit
                coEvery { store.update() } returns Unit
            }
        },
        knowledgeGraphRepositoryProvider = dagger.Lazy {
            mockk<KnowledgeGraphRepository>(relaxed = true).also { kg ->
                coEvery { kg.recent(any()) } returns emptyList()
                coEvery { kg.allEdges() } returns emptyList()
            }
        },
    )

    @Test
    fun `runCycle on empty memory list returns zero-everything report`() = runBlocking {
        val store = mockStore(emptyList())
        val provider = mockProvider(emptyList())
        val consolidator = buildConsolidator(store, provider)
        val report = consolidator.runCycle()
        assertEquals(0, report.summariesWritten)
        assertEquals(0, report.clustersFormed)
        assertEquals(0, report.totalCharsSaved)
        assertEquals(0, report.memoriesProcessed)
    }

    @Test
    fun `runCycle with too-few memories returns no-clusters report`() = runBlocking {
        // Below MIN_MEMORIES_TO_CONSOLIDATE (3). Should return a
        // "too few" report with no work done.
        val store = mockStore(
            listOf(
                mem("a", "User likes Kotlin", 1),
                mem("b", "User is a backend engineer", 2),
            )
        )
        val provider = mockProvider(emptyList())
        val consolidator = buildConsolidator(store, provider)
        val report = consolidator.runCycle()
        assertEquals(0, report.summariesWritten)
        assertEquals(0, report.clustersFormed)
        assertEquals(2, report.memoriesProcessed)
    }

    @Test
    fun `runCycle clusters similar memories and writes summaries`() = runBlocking {
        // 6 memories: 3 with tag=1 (identical vectors) -> cluster A,
        // 3 with tag=2 (orthogonal) -> cluster B. Both clusters
        // clear MIN_CLUSTER_SIZE=3 so both should be written.
        val store = mockStore(
            listOf(
                mem("a", "User likes Kotlin", 1),
                mem("b", "User prefers Kotlin over Java", 1),
                mem("c", "User codes in Kotlin daily", 1),
                mem("d", "Lives in Baku", 2),
                mem("e", "Based in Baku Azerbaijan", 2),
                mem("f", "Travels often to Baku", 2),
            )
        )
        val provider = mockProvider(
            listOf(
                "User prefers Kotlin and uses it daily",
                "User is based in Baku",
            )
        )
        val consolidator = buildConsolidator(store, provider)
        val report = consolidator.runCycle()
        assertEquals(2, report.clustersFormed)
        assertEquals(2, report.summariesWritten)
        assertTrue("Total chars saved should be > 0", report.totalCharsSaved > 0)
    }

    @Test
    fun `tagging sources uses the tags-only path, never the user-edit path`() = runBlocking {
        // Regression: tagSourceMemories used to route through
        // MemoryStore.update() — the USER-edit path — which nulled the
        // embedding (killing vector recall until a manual rebuild), reset
        // accessedAt, and wrote a fake editedBy="user" audit row on every
        // dream cycle.
        val store = mockStore(
            listOf(
                mem("a", "User likes Kotlin", 1),
                mem("b", "User prefers Kotlin over Java", 1),
                mem("c", "User codes in Kotlin daily", 1),
            )
        )
        val provider = mockProvider(listOf("User prefers Kotlin and uses it daily"))
        val consolidator = buildConsolidator(store, provider)
        val report = consolidator.runCycle()
        assertEquals(1, report.summariesWritten)

        io.mockk.coVerify(exactly = 3) {
            store.updateTags(any(), match { it.contains("consolidated:dream_") })
        }
        io.mockk.coVerify(exactly = 0) {
            store.update(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `phase 8 detects a new summary contradicting a stored summary`() = runBlocking {
        // Regression: detectContradictions required two rows sharing a
        // clusterId — impossible by construction (unique index on
        // clusterId + runCycle's skipSet), so the phase NEVER fired and
        // the COHERENCE drive / NarrativeSelf concerns stayed empty
        // forever. New summaries must now be compared against EXISTING
        // stored summaries about similar content.
        val existingSummary = DreamSummaryEntity(
            id = "dream_oldcluster",
            clusterId = "oldcluster",
            compressedText = "User prefers light mode in every app and website.",
            sourceMemoryIds = "x,y,z",
            dominantTags = "",
            sourceCount = 3,
            modelUsed = "test:model",
            createdAt = 1_000L,
        )
        val dreamDao = mockDao().also { dao ->
            // Phase 8 reads all() to find stored summaries; the newly
            // inserted one is filtered by id, the old one is compared.
            coEvery { dao.all() } returns listOf(existingSummary)
            coEvery { dao.allClusterIds() } returns emptyList()
        }
        val contradictionDao = mockk<ContradictionDao>(relaxed = true)
        val inserted = io.mockk.slot<ContradictionEntity>()
        coEvery { contradictionDao.insert(capture(inserted)) } returns 1L

        val store = mockStore(
            listOf(
                mem("a", "User no longer uses light mode", 1),
                mem("b", "User said light mode hurts their eyes now", 1),
                mem("c", "User switched every app to dark", 1),
            )
        )
        // The new summary contains a negation trigger ("no longer") and
        // shares enough tokens with the stored summary to look related.
        val provider = mockProvider(
            listOf("User no longer prefers light mode in every app; user now uses dark mode."),
        )

        val consolidator = buildConsolidator(store, provider, dreamDao = dreamDao, contradictionDao = contradictionDao)
        val report = consolidator.runCycle()

        assertEquals(1, report.summariesWritten)
        assertEquals("contradictionsFound should count the detected pair", 1, report.contradictionsFound)
        io.mockk.coVerify(exactly = 1) { contradictionDao.insert(any()) }
        val row = inserted.captured
        assertEquals("dream_oldcluster", row.olderSummaryId)
        assertTrue("newer side must be this cycle's summary", row.newerSummaryId.startsWith("dream_"))
        assertEquals("no longer", row.triggerPhrase)
        assertEquals("UNRESOLVED", row.status)
    }

    @Test
    fun `phase 8 stays silent when the new summary is unrelated to stored ones`() = runBlocking {
        val existingSummary = DreamSummaryEntity(
            id = "dream_oldcluster",
            clusterId = "oldcluster",
            compressedText = "Quarterly financial projections favor the Berlin office.",
            sourceMemoryIds = "x",
            dominantTags = "",
            sourceCount = 3,
            modelUsed = "test:model",
            createdAt = 1_000L,
        )
        val dreamDao = mockDao().also { dao ->
            coEvery { dao.all() } returns listOf(existingSummary)
            coEvery { dao.allClusterIds() } returns emptyList()
        }
        val contradictionDao = mockk<ContradictionDao>(relaxed = true)

        val store = mockStore(
            listOf(
                mem("a", "User no longer uses light mode", 1),
                mem("b", "User switched to dark", 1),
                mem("c", "Dark everywhere", 1),
            )
        )
        // Trigger phrase present but zero topical overlap with the stored
        // summary — the relatedness prefilter must block the pair.
        val provider = mockProvider(listOf("User no longer wants notifications at night."))

        val consolidator = buildConsolidator(store, provider, dreamDao = dreamDao, contradictionDao = contradictionDao)
        val report = consolidator.runCycle()

        assertEquals(0, report.contradictionsFound)
        io.mockk.coVerify(exactly = 0) { contradictionDao.insert(any()) }
    }

    @Test
    fun `narrative phase updates the self-model from this cycle's summaries`() = runBlocking {
        // First production caller of NarrativeSelf.updateFromDream: a cycle
        // that wrote >=1 summary feeds its LLM-written compressedText into
        // recentGrowth and unresolved contradictions into activeConcerns.
        val ns = mockk<com.aura.consciousness.NarrativeSelf>(relaxed = true)
        io.mockk.every { ns.snapshot() } returns com.aura.consciousness.NarrativeState(
            unresolvedQuestions = listOf("What is the user's deadline?"),
        )
        val contradictionDao = mockk<ContradictionDao>(relaxed = true)
        coEvery { contradictionDao.byStatus("UNRESOLVED") } returns listOf(
            ContradictionEntity(
                id = "contra_1",
                olderSummaryId = "dream_a",
                newerSummaryId = "dream_b",
                olderText = "User prefers light mode in every app.",
                newerText = "User switched every app to dark mode.",
                triggerPhrase = "switched",
                confidence = 0.6f,
                status = "UNRESOLVED",
                createdAt = 1_000L,
            ),
        )
        val store = mockStore(
            listOf(
                mem("a", "User likes Kotlin", 1),
                mem("b", "User prefers Kotlin over Java", 1),
                mem("c", "User codes in Kotlin daily", 1),
            )
        )
        val provider = mockProvider(listOf("User prefers Kotlin and uses it daily"))
        val consolidator = buildConsolidator(store, provider, contradictionDao = contradictionDao, narrativeSelf = ns)

        val report = consolidator.runCycle()

        assertEquals(1, report.summariesWritten)
        assertTrue("report should record the narrative update", report.narrativeUpdated)
        io.mockk.verify(exactly = 1) {
            ns.updateFromDream(
                growthSummary = match { it.isNotBlank() && it.contains("Kotlin") },
                concerns = match { it.size == 1 && it[0].startsWith("Conflicting: ") },
                questions = listOf("What is the user's deadline?"),
            )
        }
        io.mockk.coVerify(exactly = 1) { ns.save() }
    }

    @Test
    fun `narrative phase does not fire when no summaries were written`() = runBlocking {
        // An empty cycle must not blank the narrative. Two singleton
        // clusters stay below MIN_CLUSTER_SIZE, so the cycle proceeds
        // through the later phases with summariesWritten == 0.
        val ns = mockk<com.aura.consciousness.NarrativeSelf>(relaxed = true)
        val store = mockStore(
            listOf(
                mem("a", "User likes Kotlin", 1),
                mem("b", "Lives in Baku", 2),
            )
        )
        val provider = mockProvider(emptyList())
        val consolidator = buildConsolidator(store, provider, narrativeSelf = ns)

        val report = consolidator.runCycle()

        assertEquals(0, report.summariesWritten)
        assertEquals(false, report.narrativeUpdated)
        io.mockk.verify(exactly = 0) { ns.updateFromDream(any(), any(), any()) }
        io.mockk.coVerify(exactly = 0) { ns.save() }
    }

    @Test
    fun `runCycle writes a raw-text fallback when LLM fails`() = runBlocking {
        val store = mockStore(
            listOf(
                mem("a", "Likes Kotlin", 1),
                mem("b", "Prefers Kotlin", 1),
                mem("c", "Codes in Kotlin", 1),
            )
        )
        val consolidator = buildConsolidator(
            memoryStore = store,
            provider = mockProviderFailing(),
        )
        val report = consolidator.runCycle()
        // When LLM fails, the consolidator falls back to the
        // first memory's content (truncated to 300 chars). The
        // summary IS still written - just with raw text - and
        // summariesFailedLlm=1 records the failure for the UI.
        assertEquals(1, report.clustersFormed)
        assertEquals(1, report.summariesWritten)
        assertEquals(1, report.summariesFailedLlm)
    }
}
