package com.aura.agent

import com.aura.memory.Embedder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationStoreTest {
    private val dao = mockk<ConversationDao>(relaxed = true)
    private val embedder = mockk<Embedder>(relaxed = true)

    @Test
    fun `save then load returns equivalent conversation`() = runTest {
        val store = ConversationStore(dao, embedder)
        val conv = Conversation(
            id = "test-id",
            title = "Test chat",
            systemPrompt = "be helpful",
            model = "test:model",
            contextSummary = "Earlier decisions are durable.",
            summaryThroughTurn = 1,
            turns = listOf(
                Turn(user = "hi"),
                Turn(assistant = "hello", toolTurns = listOf(ToolTurn("t1", "echo", "{}", "echoed"))),
            ),
        )
        coEvery { dao.insert(any()) } returns Unit
        coEvery { dao.getById("test-id") } returns null // save then load with no DAO roundtrip = null
        // For a true roundtrip test we'd need an in-memory Room. Instead, validate
        // the JSON encoding path by capturing what we wrote.
        var captured: ConversationEntity? = null
        coEvery { dao.insert(capture(slot<ConversationEntity>())) } answers { captured = firstArg() }
        store.save(conv)
        assertNotNull(captured)
        assertEquals("test-id", captured!!.id)
        assertEquals("Test chat", captured!!.title)
        assertEquals("be helpful", captured!!.systemPrompt)
        assertEquals("test:model", captured!!.model)
        assertEquals("Earlier decisions are durable.", captured!!.contextSummary)
        assertEquals(1, captured!!.summaryThroughTurn)
    }

    @Test
    fun `mostRecent returns null when DAO is empty`() = runTest {
        val store = ConversationStore(dao, embedder)
        coEvery { dao.mostRecent() } returns null
        assertNull(store.mostRecent())
    }

    @Test
    fun `mostRecent returns null when entity is missing`() = runTest {
        val store = ConversationStore(dao, embedder)
        coEvery { dao.getById("missing") } returns null
        assertNull(store.load("missing"))
    }

    @Test
    fun `load returns null for missing id`() = runTest {
        val store = ConversationStore(dao, embedder)
        coEvery { dao.getById("nope") } returns null
        assertNull(store.load("nope"))
    }

    @Test
    fun `delete delegates to DAO`() = runTest {
        val store = ConversationStore(dao, embedder)
        coEvery { dao.softDelete("id1", any()) } returns Unit
        store.delete("id1")
        coVerify { dao.softDelete("id1", any()) }
    }

    @Test
    fun `delete stamps a near-current timestamp`() = runTest {
        // Regression guard: a zero or negative `deletedAt` would make the
        // row invisible (deletedAt IS NULL fails) AND un-purgeable
        // (purgeDeletedBefore uses deletedAt < cutoff). A future change
        // to `delete` must keep the timestamp current.
        val store = ConversationStore(dao, embedder)
        val captured = slot<Long>()
        coEvery { dao.softDelete("id1", capture(captured)) } returns Unit
        val before = System.currentTimeMillis()
        store.delete("id1")
        val after = System.currentTimeMillis()
        val stamped = captured.captured
        assertTrue(stamped in before..after, "stamped=$stamped before=$before after=$after")
    }

    @Test
    fun `restore clears the tombstone and returns the conversation`() = runTest {
        // The Undo snackbar depends on this round-trip: restore() must
        // call dao.restore() AND surface the now-visible conversation
        // back to the caller.
        val restored = ConversationEntity(
            id = "id1",
            title = "Restored",
            createdAt = 1L,
            updatedAt = 2L,
            systemPrompt = null,
            model = null,
            metadataJson = "{}",
            turnsJson = "[]",
        )
        coEvery { dao.restore("id1") } returns Unit
        coEvery { dao.getById("id1") } returns restored
        val store = ConversationStore(dao, embedder)
        val result = store.restore("id1")
        coVerify { dao.restore("id1") }
        assertNotNull(result)
        assertEquals("Restored", result.title)
    }

    @Test
    fun `purgeDeletedOlderThan calls DAO with cutoff=now-minus-retention`() = runTest {
        // The 7-day retention sweep: cutoff = now - 7d. A regression that
        // passes 0 or a negative cutoff would either purge everything
        // or nothing. This test pins the contract: cutoff must be
        // exactly 7 days in the past (within a 1s tolerance for the
        // gap between this read and the implementation's read).
        val store = ConversationStore(dao, embedder)
        val captured = slot<Long>()
        coEvery { dao.purgeDeletedBefore(capture(captured)) } returns 0
        val before = System.currentTimeMillis() - ConversationEntity.SOFT_DELETE_RETENTION_MS
        store.purgeDeletedOlderThan()
        val after = System.currentTimeMillis() - ConversationEntity.SOFT_DELETE_RETENTION_MS
        val cutoff = captured.captured
        assertTrue(cutoff in before..after, "cutoff=$cutoff expected in [$before, $after]")
    }

    @Test
    fun `purgeDeletedOlderThan with custom retention`() = runTest {
        // Operator override: 0 ms retention = purge every tombstone now.
        val store = ConversationStore(dao, embedder)
        val captured = slot<Long>()
        coEvery { dao.purgeDeletedBefore(capture(captured)) } returns 0
        store.purgeDeletedOlderThan(retentionMs = 0L)
        // cutoff = now - 0 = now
        val now = System.currentTimeMillis()
        val cutoff = captured.captured
        assertTrue(cutoff in (now - 1000)..now, "cutoff=$cutoff should be near now")
    }

    @Test
    fun `save carries forward agentId from previous row`() = runTest {
        // Regression guard for the agentId data-loss bug: save() must
        // not lose the agent association when persisting a Conversation
        // (which doesn't carry agentId in its domain model).
        val store = ConversationStore(dao, embedder)
        coEvery { dao.getById("agent-conv") } returns ConversationEntity(
            id = "agent-conv",
            title = "Agent chat",
            createdAt = 1L,
            updatedAt = 2L,
            systemPrompt = null,
            model = null,
            metadataJson = "{}",
            turnsJson = "[]",
            agentId = "coder",
        )
        val captured = slot<ConversationEntity>()
        coEvery { dao.insert(capture(captured)) } answers { captured.captured }
        store.save(Conversation(id = "agent-conv", title = "Agent chat"))
        assertEquals("coder", captured.captured.agentId)
    }

    @Test
    fun `save preserves soft-delete tombstone from previous row`() = runTest {
        // Regression guard: a save() must NEVER resurrect a soft-deleted
        // conversation. The previous row was tombstoned; the new save
        // must carry forward the deletedAt timestamp.
        val store = ConversationStore(dao, embedder)
        coEvery { dao.getById("dead") } returns ConversationEntity(
            id = "dead",
            title = "Old chat",
            createdAt = 1L,
            updatedAt = 2L,
            systemPrompt = null,
            model = null,
            metadataJson = "{}",
            turnsJson = "[]",
            deletedAt = 12345L,
        )
        val captured = slot<ConversationEntity>()
        coEvery { dao.insert(capture(captured)) } answers { captured.captured }
        store.save(Conversation(id = "dead", title = "Old chat"))
        assertEquals(12345L, captured.captured.deletedAt)
    }

    @Test
    fun `load restores durable summary boundary`() = runTest {
        val store = ConversationStore(dao, embedder)
        coEvery { dao.getById("summarized") } returns ConversationEntity(
            id = "summarized",
            title = "Long chat",
            createdAt = 1L,
            updatedAt = 2L,
            systemPrompt = null,
            model = "test:model",
            turnsJson = """[{"user":"old"},{"user":"recent"}]""",
            contextSummary = "Old context preserved.",
            summaryThroughTurn = 1,
        )

        val result = store.load("summarized")

        assertNotNull(result)
        assertEquals("Old context preserved.", result.contextSummary)
        assertEquals(1, result.summaryThroughTurn)
        assertEquals(2, result.turns.size)
    }

    @Test
    fun `recent maps DAO entities to Conversations`() = runTest {
        val store = ConversationStore(dao, embedder)
        coEvery { dao.recentVisible(50) } returns listOf(
            ConversationEntity(id = "1", title = "First", createdAt = 1L, updatedAt = 2L, systemPrompt = null, model = null, metadataJson = "{}", turnsJson = "[]"),
            ConversationEntity(id = "2", title = "Second", createdAt = 3L, updatedAt = 4L, systemPrompt = null, model = null, metadataJson = "{}", turnsJson = "[]"),
        )
        val result = store.recent(50)
        assertEquals(2, result.size)
        assertEquals("First", result[0].title)
        assertEquals("Second", result[1].title)
    }

    @Test
    fun `save persists an embedding for the latest user turn`() = runTest {
        val store = ConversationStore(dao, embedder)
        val expected = floatArrayOf(1f, 2f)
        val saved = slot<ConversationEntity>()
        coEvery { dao.getById("embedded") } returns null
        coEvery { embedder.embed("latest question") } returns expected
        coEvery { dao.insert(capture(saved)) } returns Unit

        store.save(
            Conversation(
                id = "embedded",
                title = "Searchable chat",
                turns = listOf(
                    Turn(user = "old question"),
                    Turn(assistant = "old answer"),
                    Turn(user = "latest question"),
                ),
            ),
        )

        assertContentEquals(Embedder.toBytes(expected), saved.captured.embedding)
        coVerify(exactly = 1) { embedder.embed("latest question") }
    }

    @Test
    fun `semantic search backfills missing conversation embeddings before ranking`() = runTest {
        val store = ConversationStore(dao, embedder)
        val vector = floatArrayOf(1f, 0f)
        val missing = ConversationEntity(
            id = "legacy",
            title = "Legacy chat",
            createdAt = 1L,
            updatedAt = 2L,
            systemPrompt = null,
            model = "test:model",
            turnsJson = """[{"user":"Kotlin coroutines"}]""",
        )
        coEvery { dao.missingEmbeddings(24) } returns listOf(missing)
        coEvery { embedder.embed("Kotlin coroutines") } returns vector
        coEvery { embedder.embed("structured concurrency") } returns vector
        coEvery { dao.allWithEmbeddings() } returns listOf(
            missing.copy(embedding = Embedder.toBytes(vector)),
        )

        val results = store.semanticSearch("structured concurrency")

        coVerify(exactly = 1) {
            dao.updateEmbedding("legacy", match { it.contentEquals(Embedder.toBytes(vector)) })
        }
        assertEquals(listOf("legacy"), results.map { it.id })
    }
}
