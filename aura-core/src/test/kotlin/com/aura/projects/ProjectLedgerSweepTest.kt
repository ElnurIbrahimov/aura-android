package com.aura.projects

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.agent.Conversation
import com.aura.agent.ConversationStore
import com.aura.agent.Turn
import com.aura.data.UserPreferences
import com.aura.memory.MemoryDatabase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The watermark, which is where this feature can lose data permanently.
 *
 * Every other failure in the ledger is recoverable on the next pass. Advancing
 * the watermark past turns that were never read is not: those turns are never
 * offered again, whatever was decided in them never reaches the ledger, and
 * nothing anywhere reports that it happened.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ProjectLedgerSweepTest {

    private lateinit var db: MemoryDatabase
    private lateinit var projectStore: ProjectStore
    private val conversations: ConversationStore = mockk(relaxed = true)
    private val extractor: ProjectLedgerExtractor = mockk()
    private val prefs: UserPreferences = mockk()

    private lateinit var sweep: ProjectLedgerSweep

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            MemoryDatabase::class.java,
        ).allowMainThreadQueries().build()
        projectStore = ProjectStore(db.projectDao(), db.projectNoteDao())
        every { prefs.backgroundModel } returns flowOf("ollama:qwen3")
        sweep = ProjectLedgerSweep(conversations, projectStore, extractor, prefs)
    }

    @After
    fun tearDown() = db.close()

    private fun conversation(vararg at: Long) = Conversation(
        id = "c1",
        turns = at.map { Turn(user = "something", assistant = "ok", timestamp = it) },
    )

    private fun wire(conv: Conversation, projectName: String, watermark: Long) {
        coEvery { conversations.recent(any()) } returns listOf(conv)
        every { conversations.projectOf(conv) } returns projectName
        every { conversations.ledgerWatermarkOf(conv) } returns watermark
    }

    @Test
    fun `only turns newer than the watermark are extracted`() {
        val p = runBlocking { projectStore.create("ARC-AGI-2")!! }
        wire(conversation(100L, 200L, 300L), "ARC-AGI-2", watermark = 200L)
        val turns = slot<List<Turn>>()
        coEvery {
            extractor.extract(p.id, "c1", capture(turns), any())
        } returns ProjectLedgerExtractor.Outcome(notesWritten = 1, ran = true)

        runBlocking { sweep.sweep() }

        assertEquals("the watermarked turns must not be re-read", 1, turns.captured.size)
        assertEquals(300L, turns.captured.single().timestamp)
    }

    @Test
    fun `the watermark advances to the newest turn read, not to now`() {
        val p = runBlocking { projectStore.create("ARC-AGI-2")!! }
        wire(conversation(100L, 300L), "ARC-AGI-2", watermark = 0L)
        coEvery { extractor.extract(any(), any(), any(), any()) } returns
            ProjectLedgerExtractor.Outcome(notesWritten = 1, ran = true)

        runBlocking { sweep.sweep() }

        // Advancing to `now` would put a turn that arrived mid-sweep behind the
        // watermark, and it would then never be read.
        coVerify(exactly = 1) { conversations.setLedgerWatermark("c1", 300L) }
    }

    /**
     * The silent, permanent one. A skip means the turns were not read; advancing
     * past them discards whatever they contained with no error anywhere.
     */
    @Test
    fun `a skipped extraction does not advance the watermark`() {
        runBlocking { projectStore.create("ARC-AGI-2") }
        wire(conversation(100L, 300L), "ARC-AGI-2", watermark = 0L)
        coEvery { extractor.extract(any(), any(), any(), any()) } returns
            ProjectLedgerExtractor.Outcome.skipped("daily background budget spent")

        val outcome = runBlocking { sweep.sweep() }

        coVerify(exactly = 0) { conversations.setLedgerWatermark(any(), any()) }
        assertEquals(0, outcome.conversationsRead)
        assertEquals("nothing new said", outcome.reason)
    }

    @Test
    fun `a conversation with nothing new is not sent to the model at all`() {
        runBlocking { projectStore.create("ARC-AGI-2") }
        wire(conversation(100L), "ARC-AGI-2", watermark = 100L)

        runBlocking { sweep.sweep() }

        coVerify(exactly = 0) { extractor.extract(any(), any(), any(), any()) }
    }

    @Test
    fun `a tag naming no known project is skipped rather than creating one`() {
        wire(conversation(300L), "Some Old Tag", watermark = 0L)

        val outcome = runBlocking { sweep.sweep() }

        coVerify(exactly = 0) { extractor.extract(any(), any(), any(), any()) }
        assertEquals(0, outcome.conversationsRead)
    }

    @Test
    fun `no background model means no sweep, with a reason`() {
        every { prefs.backgroundModel } returns flowOf(null)
        sweep = ProjectLedgerSweep(conversations, projectStore, extractor, prefs)

        val outcome = runBlocking { sweep.sweep() }

        assertEquals("no background model configured", outcome.reason)
        coVerify(exactly = 0) { extractor.extract(any(), any(), any(), any()) }
    }

    @Test
    fun `a successful pass attributes the turn to the project`() {
        val p = runBlocking { projectStore.create("ARC-AGI-2")!! }
        wire(conversation(300L), "ARC-AGI-2", watermark = 0L)
        coEvery { extractor.extract(any(), any(), any(), any()) } returns
            ProjectLedgerExtractor.Outcome(notesWritten = 2, ran = true)

        val outcome = runBlocking { sweep.sweep() }

        assertEquals(1, outcome.conversationsRead)
        assertEquals(2, outcome.notesWritten)
        val after = runBlocking { projectStore.get(p.id) }!!
        assertEquals(1, after.turnCount)
        assertEquals(300L, after.lastTurnAt)
    }

    @Test
    fun `a sweep is bounded so one run cannot spend the whole day's budget`() {
        val convs = (1..10).map { i ->
            Conversation(id = "c$i", turns = listOf(Turn(user = "x", timestamp = 300L)))
        }
        runBlocking { projectStore.create("ARC-AGI-2") }
        coEvery { conversations.recent(any()) } returns convs
        convs.forEach {
            every { conversations.projectOf(it) } returns "ARC-AGI-2"
            every { conversations.ledgerWatermarkOf(it) } returns 0L
        }
        coEvery { extractor.extract(any(), any(), any(), any()) } returns
            ProjectLedgerExtractor.Outcome(notesWritten = 1, ran = true)

        val outcome = runBlocking { sweep.sweep() }

        assertEquals(ProjectLedgerSweep.MAX_PER_SWEEP, outcome.conversationsRead)
        coVerify(exactly = ProjectLedgerSweep.MAX_PER_SWEEP) {
            extractor.extract(any(), any(), any(), any())
        }
    }
}
