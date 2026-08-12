package com.aura.curiosity

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.dream.ContradictionDao
import com.aura.dream.ContradictionEntity
import com.aura.dream.DreamConsolidationDatabase
import com.aura.kg.EdgeEntity
import com.aura.kg.NodeEntity
import com.aura.memory.FakeEmbedder
import com.aura.memory.MemoryDatabase
import com.aura.memory.MemoryFtsSchema
import com.aura.memory.MemoryStore
import com.aura.memory.WriteGate
import io.mockk.coEvery
import io.mockk.mockk
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
 * Aura noticing that it does not know something, and asking.
 *
 * The drive existed before any of this: `IntrinsicMotivation.CURIOSITY` has
 * always been computed from `gapNodeCount()`, rendered into the prompt as a
 * count of unexplored topics, and satisfied when a search tool happened to run.
 * What it could never do is name a single one of those topics or put a question
 * in front of anyone. `NarrativeSelf.unresolvedQuestions`, the field built to
 * hold them, has been empty for every user since it shipped because its only
 * writer seeds it from its own previous value.
 *
 * What is pinned here is the part that makes this a mechanism rather than a
 * mood: a question names a real row, there is never more than one, and a
 * refusal is permanent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CuriosityStoreTest {

    private lateinit var db: MemoryDatabase
    private lateinit var dreamDb: DreamConsolidationDatabase
    private lateinit var scanner: QuestionScanner
    private lateinit var store: CuriosityStore
    private lateinit var memoryStore: MemoryStore
    private val author = mockk<QuestionAuthor>()

    private val now = 1_700_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(MemoryFtsSchema.triggerCallback)
            .build()
        dreamDb = Room.inMemoryDatabaseBuilder(context, DreamConsolidationDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        memoryStore = MemoryStore(
            db.memoryDao(),
            FakeEmbedder(384),
            WriteGate(),
            db.memoryEditDao(),
            db.memoryFeedbackDao(),
        )
        scanner = QuestionScanner(
            db.knowledgeGraphDao(),
            dreamDb.contradictionDao(),
            db.memoryDao(),
            db.openQuestionDao(),
        )
        store = CuriosityStore(db.openQuestionDao(), scanner, author, memoryStore)
    }

    @After
    fun tearDown() {
        db.close()
        dreamDb.close()
    }

    /** The author is the one model call; its phrasing is not what is under test. */
    private fun authorWrites(question: String = "What is Causeway?") {
        coEvery { author.author(any()) } answers {
            firstArg<List<QuestionScanner.Subject>>().take(1).map {
                QuestionAuthor.Authored(it, question, OpenQuestionEntity.ANSWERABLE_USER)
            }
        }
    }

    private suspend fun seedGapNode(id: String = "n1", label: String = "Causeway") {
        db.knowledgeGraphDao().insertNode(NodeEntity(id = id, label = label, type = "project"))
    }

    @Test
    fun `a graph node Aura knows nothing about becomes a question`(): Unit = runBlocking {
        seedGapNode()
        authorWrites()

        assertEquals(1, store.scanAndAuthor(now))

        val question = store.current()
        assertNotNull(question)
        assertEquals(OpenQuestionEntity.KIND_GAP, question.kind)
        // The subject is a real row, which is what makes "is this still a gap"
        // re-checkable and lets asking be something that finishes.
        assertEquals("n1", question.subjectId)
        assertEquals("What is Causeway?", question.question)
    }

    @Test
    fun `a well-connected node is not a gap`(): Unit = runBlocking {
        val kg = db.knowledgeGraphDao()
        kg.insertNode(NodeEntity(id = "hub", label = "Causeway", type = "project"))
        kg.insertNode(NodeEntity(id = "a", label = "A", type = "topic"))
        kg.insertNode(NodeEntity(id = "b", label = "B", type = "topic"))
        kg.insertEdge(EdgeEntity(id = "e1", type = "rel", sourceId = "hub", targetId = "a"))
        kg.insertEdge(EdgeEntity(id = "e2", type = "rel", sourceId = "hub", targetId = "b"))

        assertTrue(scanner.scan(now = now).none { it.subjectId == "hub" })
    }

    @Test
    fun `a well-connected node nobody ever looks at is its own kind of gap`(): Unit = runBlocking {
        // The inverse signal, inherited from the CuriosityScanner this
        // replaced: Aura has built a web around something and never once
        // reached for it, which usually means it recorded the shape of a topic
        // without understanding it.
        val kg = db.knowledgeGraphDao()
        kg.insertNode(NodeEntity(id = "hub", label = "Causeway", type = "project", accessCount = 0))
        repeat(6) { i ->
            kg.insertNode(NodeEntity(id = "leaf$i", label = "L$i", type = "topic"))
            kg.insertEdge(EdgeEntity(id = "e$i", type = "rel", sourceId = "hub", targetId = "leaf$i"))
        }

        val subjects = scanner.scan(now = now)
        assertTrue(
            subjects.any { it.subjectId == "hub" && it.kind == OpenQuestionEntity.KIND_SHALLOW },
            "a hub node with no accesses should be asked about: $subjects",
        )
    }

    @Test
    fun `an unresolved contradiction outranks a gap`(): Unit = runBlocking {
        seedGapNode()
        dreamDb.contradictionDao().insert(
            ContradictionEntity(
                id = "c1",
                olderSummaryId = "s1",
                newerSummaryId = "s2",
                olderText = "Elnur lives in Baku",
                newerText = "Elnur no longer lives in Baku",
                triggerPhrase = "no longer",
                confidence = 0.8f,
            ),
        )

        val subjects = scanner.scan(now = now)
        // One of these two beliefs is false and Aura is currently using both.
        assertEquals(OpenQuestionEntity.KIND_CONTRADICTION, subjects.first().kind)
    }

    @Test
    fun `an old important fact that was never confirmed becomes a question`(): Unit = runBlocking {
        val fresh = memoryStore.store("Elnur works at X", "user", "fact", 0.9f)
        val stale = memoryStore.store("Elnur lives in Baku", "user", "fact", 0.9f)
        // Backdate one of them past the six-month bar.
        db.memoryDao().update(db.memoryDao().getById(stale)!!.copy(createdAt = now - 200 * day))

        val subjects = scanner.scan(now = now)
        assertEquals(listOf(stale), subjects.filter { it.kind == OpenQuestionEntity.KIND_STALE }.map { it.subjectId })
        assertTrue(subjects.none { it.subjectId == fresh })
    }

    @Test
    fun `only one question is ever open`(): Unit = runBlocking {
        db.knowledgeGraphDao().insertNode(NodeEntity(id = "n1", label = "Causeway", type = "project"))
        db.knowledgeGraphDao().insertNode(NodeEntity(id = "n2", label = "FluxMind", type = "project"))
        authorWrites()

        assertEquals(1, store.scanAndAuthor(now))
        // An assistant that can queue questions will eventually ask all of them.
        assertEquals(0, store.scanAndAuthor(now + day))
        assertEquals(1, db.openQuestionDao().openCount())
    }

    @Test
    fun `a refusal is permanent for that subject`(): Unit = runBlocking {
        seedGapNode()
        authorWrites()
        store.scanAndAuthor(now)
        val question = store.current()!!

        store.dismiss(question.id, now)

        assertNull(store.current())
        // The row stays, which is what makes "never ask about this" enforceable
        // rather than a rule someone has to remember.
        assertTrue(scanner.scan(now = now + 365 * day).none { it.subjectId == "n1" })
        assertEquals(0, store.scanAndAuthor(now + 365 * day))
    }

    @Test
    fun `answering stores the fact with its question and closes the loop`(): Unit = runBlocking {
        seedGapNode()
        authorWrites("What is Causeway?")
        store.scanAndAuthor(now)
        val question = store.current()!!

        val memoryId = store.answer(question.id, "A causal inference project", now = now)

        assertNotNull(memoryId)
        // The question travels with the answer: "A causal inference project" on
        // its own is not a memory of anything.
        val memory = memoryStore.get(memoryId)!!
        assertTrue(memory.content.contains("What is Causeway?"))
        assertTrue(memory.content.contains("A causal inference project"))

        val closed = db.openQuestionDao().byId(question.id)!!
        assertEquals(OpenQuestionEntity.STATUS_ANSWERED, closed.status)
        assertEquals(memoryId, closed.answerMemoryId)
        assertNull(store.current())
    }

    @Test
    fun `an answered question cannot be answered twice`(): Unit = runBlocking {
        seedGapNode()
        authorWrites()
        store.scanAndAuthor(now)
        val id = store.current()!!.id
        assertNotNull(store.answer(id, "first", now = now))

        assertNull(store.answer(id, "second", now = now + 1))
    }

    @Test
    fun `a blank answer is not an answer`(): Unit = runBlocking {
        seedGapNode()
        authorWrites()
        store.scanAndAuthor(now)
        val id = store.current()!!.id

        assertNull(store.answer(id, "   ", now = now))
        assertNotNull(store.current())
    }

    @Test
    fun `nothing is asked when there is nothing to ask about`(): Unit = runBlocking {
        authorWrites()
        assertEquals(0, store.scanAndAuthor(now))
        assertNull(store.current())
    }
}
