package com.aura.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.evolution.EvolutionDatabase
import com.aura.evolution.EvolutionEvidenceRecorder
import com.aura.evolution.EvolutionHooks
import com.aura.kg.EdgeEntity
import com.aura.kg.NodeEntity
import com.aura.provenance.ConversationProvenance
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The user telling Aura it was wrong, and it mattering.
 *
 * Every property here is one the app previously lacked. `memory_feedback` had
 * no readers, so a downvote changed nothing; decay treated "that was never
 * true" and "that changed last month" identically; and there was no way at all
 * to say a memory was true but had surfaced for the wrong question. What is
 * pinned below is not that a correction is *recorded* — that part always
 * worked — but that each kind has its own, different, observable effect.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CorrectionStoreTest {

    private lateinit var db: MemoryDatabase
    private lateinit var evolutionDb: EvolutionDatabase
    private lateinit var store: MemoryStore
    private lateinit var corrections: CorrectionStore

    private val turn = ConversationProvenance("conv-1", 1_000L)

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
        val embedder = FakeEmbedder(384)
        store = MemoryStore(
            db.memoryDao(),
            embedder,
            WriteGate(),
            db.memoryEditDao(),
            db.memoryFeedbackDao(),
            config = RetrievalConfig.DEFAULT,
            correctionDao = db.correctionDao(),
        )
        corrections = CorrectionStore(
            db.correctionDao(),
            store,
            embedder,
            db.knowledgeGraphDao(),
            EvolutionHooks(EvolutionEvidenceRecorder(evolutionDb.evidenceDao())),
        )
    }

    @After
    fun tearDown() {
        db.close()
        evolutionDb.close()
    }

    private suspend fun storeFromTurn(content: String, category: String = "fact"): String =
        store.store(content, "user", category, 0.7f, provenance = turn)

    @Test
    fun `never true stops the memory being recalled`(): Unit = runBlocking {
        val id = storeFromTurn("Elnur lives in Baku")
        assertTrue(store.searchByText("Baku").any { it.id == id })

        val report = corrections.neverTrue(id, provenance = turn)

        assertTrue(store.searchByText("Baku").none { it.id == id })
        assertEquals(REASON_RETRACTED, store.get(id)!!.retiredReason)
        assertTrue(report.summary.startsWith("Retracted"), report.summary)
    }

    @Test
    fun `no longer true keeps the old fact as history and lets the new one answer`(): Unit = runBlocking {
        // The distinction the app could not make: "I never lived in Baku" and
        // "I moved away from Baku" used to produce the identical slow fade.
        val id = storeFromTurn("Elnur lives in Baku")

        corrections.noLongerTrue(id, "Elnur lives in Istanbul", provenance = turn)

        val old = store.get(id)!!
        assertNotNull(old.retiredAt)
        assertEquals(REASON_SUPERSEDED, old.retiredReason)
        val replacement = store.get(old.supersededBy!!)!!
        assertEquals("Elnur lives in Istanbul", replacement.content)
        // The successor inherits standing: what changed is the fact, not how
        // much it matters or who may see it.
        assertEquals(old.importance, replacement.importance)
        assertEquals(old.scope, replacement.scope)
        assertEquals(old.category, replacement.category)

        assertTrue(store.searchByText("Istanbul").any { it.id == replacement.id })
        assertTrue(store.searchByText("Baku").none { it.id == id })
    }

    @Test
    fun `irrelevant here demotes for that question only, and keeps the memory`(): Unit = runBlocking {
        val offTopic = storeFromTurn("Elnur is allergic to peanuts", category = "fact")
        val onTopic = storeFromTurn("Elnur wants to learn Python", category = "fact")

        // FakeEmbedder is a hash sketch, so "the same question" has to be the
        // same string here. What this pins is the demotion, not the similarity
        // model.
        val question = "Elnur"
        val before = store.query(question, MemoryStore.RecallOptions(limit = 5)).map { it.id }
        assertTrue(before.containsAll(listOf(offTopic, onTopic)), "both memories must be in play: $before")

        corrections.irrelevantHere(offTopic, question, provenance = turn)

        // Still there — this is a claim about the question, not the fact.
        assertNull(store.get(offTopic)!!.retiredAt)
        assertTrue(store.searchByText("peanuts").any { it.id == offTopic })

        val after = store.query(question, MemoryStore.RecallOptions(limit = 5)).map { it.id }
        assertEquals(offTopic, after.last(), "the corrected memory should rank last for that question")
        assertTrue(onTopic in after, "the other memory must be unaffected")
    }

    @Test
    fun `a scoped demotion does not follow the memory to other questions`(): Unit = runBlocking {
        val memory = storeFromTurn("Elnur is allergic to peanuts")
        storeFromTurn("Elnur wants to learn Python")
        corrections.irrelevantHere(memory, "Elnur", provenance = turn)

        // A different question entirely. "Irrelevant here" is scoped to the
        // question that was wrong; a global penalty would hide a true fact from
        // the one place it matters most.
        val elsewhere = store.query("peanuts", MemoryStore.RecallOptions(limit = 5)).map { it.id }
        assertEquals(memory, elsewhere.firstOrNull(), "the memory should still lead its own question")
    }

    @Test
    fun `a bad answer names a real skill, which nothing else has ever done`(): Unit = runBlocking {
        // The only other writer of skill_failed recorded the literal id
        // "_unknown_", so the PATCH_SKILL detector had never seen a skill id it
        // could resolve.
        corrections.badAnswer("skill-42", provenance = turn)

        val evidence = evolutionDb.evidenceDao().byKind("SKILL", "skill_failed", 10)
        assertEquals(1, evidence.size)
        assertEquals("skill-42", evidence.single().sourceEntityId)
        assertEquals("conv-1", evidence.single().conversationId)
    }

    @Test
    fun `retracting reaches the claims from the same turn, and no further`(): Unit = runBlocking {
        val graph = db.knowledgeGraphDao()
        graph.insertNode(NodeEntity(id = "n1", label = "Elnur", type = "person"))
        graph.insertNode(NodeEntity(id = "n2", label = "Baku", type = "place"))
        graph.insertEdge(
            EdgeEntity(
                id = "e-same-turn",
                type = "lives_in",
                sourceId = "n1",
                targetId = "n2",
                sourceConversationId = "conv-1",
                sourceTurnTimestamp = 1_000L,
            ),
        )
        // A claim from a different turn. Unbounded propagation through a graph
        // is how one correction silently rewrites a history nobody questioned,
        // so this must survive.
        graph.insertEdge(
            EdgeEntity(
                id = "e-other-turn",
                type = "visited",
                sourceId = "n1",
                targetId = "n2",
                sourceConversationId = "conv-1",
                sourceTurnTimestamp = 9_999L,
            ),
        )
        val id = storeFromTurn("Elnur lives in Baku")

        val report = corrections.neverTrue(id, provenance = turn)

        assertEquals(1, report.propagated)
        assertNull(graph.getEdge("e-same-turn"))
        assertNotNull(graph.getEdge("e-other-turn"))
        assertTrue(report.summary.contains("1 connected fact"), report.summary)
    }

    @Test
    fun `undoing a supersession removes the replacement it created`(): Unit = runBlocking {
        val id = storeFromTurn("Elnur lives in Baku")
        val report = corrections.noLongerTrue(id, "Elnur lives in Istanbul", provenance = turn)
        val replacementId = store.get(id)!!.supersededBy!!

        corrections.undo(report.correctionId)

        assertNull(store.get(replacementId))
        assertNull(store.get(id)!!.retiredAt)
        assertTrue(store.searchByText("Baku").any { it.id == id })
    }

    @Test
    fun `a corrected memory is no longer offered for consolidation`(): Unit = runBlocking {
        // Recall count used to be the only memory signal any detector read, so
        // a memory the user had objected to ten times was a *stronger*
        // consolidation candidate than one they never saw. Merging it would
        // rewrite the wording they objected to into text they never read, and
        // the merged result carries no correction — the objection is lost.
        store.store("Elnur prefers tea", "user", "preference", 0.5f)
        store.store("Elnur prefers tea", "user", "preference", 0.5f)
        assertEquals(1, store.findNearDuplicateClusters().size)

        val disputed = store.searchByText("tea").first().id
        corrections.irrelevantHere(disputed, "drinks", provenance = turn)

        assertTrue(
            store.findNearDuplicateClusters().isEmpty(),
            "a memory the user has objected to must not be proposed for merging",
        )
    }

    @Test
    fun `a downvote is read, which it never was before`(): Unit = runBlocking {
        // memory_feedback rows have been accumulating since the Helpful / Not
        // helpful control shipped, read by nothing at all.
        store.store("Elnur prefers tea", "user", "preference", 0.5f)
        store.store("Elnur prefers tea", "user", "preference", 0.5f)
        assertEquals(1, store.findNearDuplicateClusters().size)

        store.recordFeedback(store.searchByText("tea").first().id, "downvote")

        assertTrue(store.findNearDuplicateClusters().isEmpty())
    }

    @Test
    fun `an upvote that outweighs a downvote leaves the memory eligible`(): Unit = runBlocking {
        store.store("Elnur prefers tea", "user", "preference", 0.5f)
        store.store("Elnur prefers tea", "user", "preference", 0.5f)
        val id = store.searchByText("tea").first().id

        store.recordFeedback(id, "downvote")
        store.recordFeedback(id, "upvote")
        store.recordFeedback(id, "upvote")

        assertEquals(1, store.findNearDuplicateClusters().size, "net-positive feedback is not an objection")
    }

    @Test
    fun `correcting a memory that is already gone says so instead of failing`(): Unit = runBlocking {
        val report = corrections.neverTrue("nope", provenance = turn)
        assertEquals("", report.correctionId)
        assertTrue(report.summary.contains("already gone"), report.summary)
    }
}
