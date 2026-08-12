package com.aura.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.evolution.EvolutionDatabase
import com.aura.evolution.EvolutionEvidenceRecorder
import com.aura.evolution.EvolutionHooks
import com.aura.provenance.ConversationProvenance
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Recall evidence has to say which turn it served.
 *
 * Both call sites passed `runId`, `conversationId` and `turnTimestamp` as
 * literal nulls, so the rows recorded that a memory had been recalled at some
 * moment and nothing else. Correcting an answer means naming the memory that
 * produced it, and with six memories in play that is the hard part — so this is
 * the join the correction spine is built on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MemoryRecallProvenanceTest {

    private lateinit var db: MemoryDatabase
    private lateinit var evolutionDb: EvolutionDatabase
    private lateinit var store: MemoryStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(MemoryFtsSchema.triggerCallback)
            .build()
        evolutionDb = Room.inMemoryDatabaseBuilder(context, EvolutionDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = MemoryStore(
            db.memoryDao(),
            FakeEmbedder(384),
            WriteGate(),
            db.memoryEditDao(),
            db.memoryFeedbackDao(),
            evolutionHooks = EvolutionHooks(EvolutionEvidenceRecorder(evolutionDb.evidenceDao())),
        )
    }

    @After
    fun tearDown() {
        db.close()
        evolutionDb.close()
    }

    private suspend fun recallEvidence() = evolutionDb.evidenceDao()
        .byKind("MEMORY", "memory_recalled", 100)

    @Test
    fun `a recall serving a turn records which turn it served`(): Unit = runBlocking {
        val id = store.store("Elnur lives in Baku", "user", "fact", 0.8f)

        store.query(
            "Baku",
            MemoryStore.RecallOptions(
                limit = 5,
                provenance = ConversationProvenance("conv-7", 1_700_000_000_000L),
                runId = "run-3",
            ),
        )

        val row = recallEvidence().single { it.sourceEntityId == id }
        assertEquals("conv-7", row.conversationId)
        assertEquals(1_700_000_000_000L, row.turnTimestamp)
        assertEquals("run-3", row.runId)
    }

    @Test
    fun `a recall that is not serving a turn claims no turn`(): Unit = runBlocking {
        // Tool reads and eval runs go through the same path. An empty
        // provenance must stay null rather than persist "" and 0, which would
        // read as a real turn at the epoch.
        val id = store.store("Elnur lives in Baku", "user", "fact", 0.8f)

        store.query("Baku", MemoryStore.RecallOptions(limit = 5))

        val row = recallEvidence().single { it.sourceEntityId == id }
        assertNull(row.conversationId)
        assertNull(row.turnTimestamp)
        assertNull(row.runId)
    }

    @Test
    fun `every memory that served the turn is attributable to it`(): Unit = runBlocking {
        val a = store.store("Elnur lives in Baku", "user", "fact", 0.8f)
        val b = store.store("Baku is on the Caspian", "user", "fact", 0.8f)

        val hits = store.query(
            "Baku",
            MemoryStore.RecallOptions(
                limit = 5,
                provenance = ConversationProvenance("conv-7", 42L),
            ),
        )
        assertTrue(hits.map { it.id }.containsAll(listOf(a, b)))

        val forTurn = recallEvidence()
            .filter { it.conversationId == "conv-7" && it.turnTimestamp == 42L }
            .map { it.sourceEntityId }
            .toSet()
        assertEquals(setOf(a, b), forTurn)
    }
}
