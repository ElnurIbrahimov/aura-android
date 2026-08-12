package com.aura.curiosity

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.data.UserPreferences
import com.aura.memory.FakeEmbedder
import com.aura.memory.MemoryDatabase
import com.aura.memory.MemoryFtsSchema
import com.aura.memory.MemoryStore
import com.aura.memory.WriteGate
import com.aura.providers.ProviderRegistry
import com.aura.providers.ProviderChunk
import com.aura.tools.WebSearchResult
import com.aura.tools.WebSearchTool
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Aura answering its own questions.
 *
 * The single open-question slot is scarce and should be spent on the things
 * only the user knows. What matters here is not that research works — it is
 * that a fact Aura went and found can never be mistaken for one the user gave
 * it, since the two carry very different weight and only one of them was ever
 * confirmed by anybody.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SelfServeResearcherTest {

    private lateinit var db: MemoryDatabase
    private lateinit var memoryStore: MemoryStore
    private lateinit var curiosityStore: CuriosityStore
    private lateinit var researcher: SelfServeResearcher

    private val search = mockk<WebSearchTool>()
    private val providers = mockk<ProviderRegistry>()
    private val prefs = mockk<UserPreferences>()

    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(MemoryFtsSchema.triggerCallback)
            .build()
        memoryStore = MemoryStore(
            db.memoryDao(),
            FakeEmbedder(384),
            WriteGate(),
            db.memoryEditDao(),
            db.memoryFeedbackDao(),
        )
        curiosityStore = CuriosityStore(
            db.openQuestionDao(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            memoryStore,
        )
        every { prefs.backgroundModel } returns flowOf("cheap-model")
        researcher = SelfServeResearcher(
            db.openQuestionDao(),
            curiosityStore,
            search,
            providers,
            prefs,
            memoryStore,
        )
    }

    @After
    fun tearDown() = db.close()

    private fun modelSays(text: String) {
        coEvery { providers.chat(any(), any(), any()) } returns flowOf(ProviderChunk(text = text))
    }

    private fun searchFinds(vararg snippets: String) {
        coEvery { search.search(any(), any()) } returns snippets.map {
            WebSearchResult(title = "t", url = "https://example.com", snippet = it)
        }
    }

    private suspend fun openQuestion(
        answerable: String = OpenQuestionEntity.ANSWERABLE_WORLD,
        question: String = "What is a Kalman filter?",
        id: String = "q1",
    ) = db.openQuestionDao().insert(
        OpenQuestionEntity(
            id = id,
            kind = OpenQuestionEntity.KIND_GAP,
            subjectKind = OpenQuestionEntity.SUBJECT_KG_NODE,
            subjectId = "n-$id",
            question = question,
            answerable = answerable,
            createdAt = now,
        ),
    )

    @Test
    fun `a world question is answered without asking anyone`(): Unit = runBlocking {
        openQuestion()
        searchFinds("A recursive state estimator.")
        modelSays("A recursive estimator for the state of a linear system.")

        assertEquals(1, researcher.research(now))

        val closed = db.openQuestionDao().byId("q1")!!
        assertEquals(OpenQuestionEntity.STATUS_RESEARCHED, closed.status)
        val memory = memoryStore.get(closed.answerMemoryId!!)
        assertNotNull(memory)
        // The marker travels with the fact, because a "source" column is
        // invisible the moment the text is quoted into a system prompt.
        assertTrue(memory.content.contains("Aura looked this up"), memory.content)
        assertTrue(memory.tags.contains("inferred"))
        assertTrue(memory.importance < 0.7f, "a fact nobody confirmed cannot outrank one they did")
    }

    @Test
    fun `a question only the user can answer is left for the user`(): Unit = runBlocking {
        openQuestion(answerable = OpenQuestionEntity.ANSWERABLE_USER, question = "Who is Leyla to you?")

        assertEquals(0, researcher.research(now))
        assertEquals(OpenQuestionEntity.STATUS_OPEN, db.openQuestionDao().byId("q1")!!.status)
    }

    @Test
    fun `no answer in the results is not an answer`(): Unit = runBlocking {
        openQuestion()
        searchFinds("Unrelated page about kitchens.")
        modelSays("UNKNOWN")

        assertEquals(0, researcher.research(now))
        // Left open so the user can still be asked, rather than closed with a
        // fabrication.
        assertEquals(OpenQuestionEntity.STATUS_OPEN, db.openQuestionDao().byId("q1")!!.status)
    }

    @Test
    fun `an empty search is not an answer`(): Unit = runBlocking {
        openQuestion()
        searchFinds()

        assertEquals(0, researcher.research(now))
        assertEquals(OpenQuestionEntity.STATUS_OPEN, db.openQuestionDao().byId("q1")!!.status)
    }

    @Test
    fun `at most one a day`(): Unit = runBlocking {
        openQuestion(id = "q1")
        openQuestion(id = "q2", question = "What is a particle filter?")
        searchFinds("Something.")
        modelSays("An answer.")

        assertEquals(1, researcher.research(now))
        assertEquals(0, researcher.research(now + 60_000))
        // Tomorrow is fine.
        assertEquals(1, researcher.research(now + 25 * 60 * 60 * 1000L))
    }

    @Test
    fun `nothing happens without a background model`(): Unit = runBlocking {
        every { prefs.backgroundModel } returns flowOf(null)
        openQuestion()

        assertEquals(0, researcher.research(now))
    }
}
