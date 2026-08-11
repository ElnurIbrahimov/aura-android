package com.aura.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.memory.Embedder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A save that started earlier must not land later and drop the reply.
 *
 * `save` is fired from eleven call sites with a bare `scope.launch` and no
 * ordering between them: the user's message is saved the moment it is typed,
 * the stream saves again on completion, `cancel()` saves, and the media flows
 * save and then immediately trigger a send that saves again. Each does
 * read -> embed -> write, with a **network call** in the middle. Interleaved,
 * the write that started first can finish last and put back a `turnsJson`
 * missing the assistant's answer.
 *
 * It was invisible in use because the UI holds its own copy of the
 * conversation — the answer stayed on screen, and you only found out when you
 * reopened that chat from History.
 *
 * Real Room rather than a mocked DAO, because the defect is in the ordering of
 * reads and writes against stored state, which a `coVerify` cannot see.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ConversationStoreRaceTest {

    /**
     * An embedder that holds open the call for a chosen snapshot.
     *
     * Gated on the *content* being embedded rather than on call order: which
     * save reaches the embedder first depends on how the test dispatcher
     * schedules the `async`, and gating "the first call" made the fresh save
     * block instead of the stalled one, deadlocking the test in the full suite
     * while passing when run alone.
     */
    private class GatedEmbedder(private val shouldBlock: (String) -> Boolean = { false }) : Embedder {
        val gate = CompletableDeferred<Unit>()

        override suspend fun embed(text: String): FloatArray {
            if (shouldBlock(text)) gate.await()
            return FloatArray(384) { 0.1f }
        }

        override fun dimension() = 384
        override fun modelId() = "test-embedder"
    }

    /** Only the one-exchange snapshot — the stale one — is held at the embedder. */
    private val blockStaleSnapshot: (String) -> Boolean =
        { text -> "message 1" in text && "message 2" !in text }

    private lateinit var db: ConversationDatabase
    private lateinit var dao: ConversationDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ConversationDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.conversationDao()
    }

    @After
    fun tearDown() = db.close()

    private fun conversation(id: String, exchanges: Int) =
        (1..exchanges).fold(Conversation(id = id)) { acc, i ->
            acc.addUser("message $i").addAssistant("reply $i")
        }

    private suspend fun storedTurnCount(id: String): Int {
        val row = dao.getById(id) ?: return -1
        return Json { ignoreUnknownKeys = true }.decodeFromString<List<Turn>>(row.turnsJson).size
    }

    @Test
    fun `a stalled early save cannot overwrite a newer one`() = runTest {
        val embedder = GatedEmbedder(blockStaleSnapshot)
        val store = ConversationStore(dao, embedder)

        // Start the save that will stall, and let it run until it blocks at the
        // embedder — after it has read the stored row, which is what makes this
        // a race rather than a simple ordering check.
        val stalled = async { store.save(conversation("c1", exchanges = 1)) }
        testScheduler.advanceUntilIdle()

        // A later save runs to completion while the first is stuck.
        store.save(conversation("c1", exchanges = 3))
        assertEquals(3, storedTurnCount("c1"), "precondition: the newer snapshot landed")

        embedder.gate.complete(Unit)
        stalled.await()

        assertEquals(
            3,
            storedTurnCount("c1"),
            "the save that started first must not put back a conversation missing the later turns",
        )
    }

    @Test
    fun `clearing a conversation may still empty it`() = runTest {
        val store = ConversationStore(dao, GatedEmbedder())

        store.save(conversation("c1", exchanges = 3))
        assertEquals(3, storedTurnCount("c1"))

        // The one caller that legitimately shrinks a conversation in place.
        store.save(Conversation(id = "c1"), allowTruncation = true)

        assertEquals(0, storedTurnCount("c1"), "clear must not be mistaken for a stale write")
    }

    @Test
    fun `a save cannot resurrect a conversation deleted while it was in flight`() = runTest {
        // Block the three-exchange snapshot this time: it is the one in flight.
        val embedder = GatedEmbedder { "message 3" in it }
        val store = ConversationStore(dao, embedder)

        store.save(conversation("c1", exchanges = 2))
        assertNull(dao.getById("c1")?.deletedAt, "precondition: not deleted")

        val inFlight = async { store.save(conversation("c1", exchanges = 3)) }
        testScheduler.advanceUntilIdle()

        // …and the conversation is soft-deleted while that save is stuck.
        dao.softDelete("c1", 12_345L)

        embedder.gate.complete(Unit)
        inFlight.await()

        assertEquals(
            12_345L,
            assertNotNull(dao.getById("c1")).deletedAt,
            "deletedAt is carried from the stored row, so it must be re-read after the network call",
        )
    }
}
