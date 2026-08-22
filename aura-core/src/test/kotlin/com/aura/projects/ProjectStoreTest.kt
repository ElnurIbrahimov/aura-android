package com.aura.projects

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.memory.MemoryDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two invariants [ProjectStore] holds that the schema cannot.
 *
 * SQLite can express neither "at most one active note per (project, kind,
 * subject)" — that needs a partial unique index — nor "subject is never blank".
 * Both are maintained in Kotlin, so both are only as real as this file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ProjectStoreTest {

    private lateinit var db: MemoryDatabase
    private lateinit var store: ProjectStore
    private lateinit var noteDao: ProjectNoteDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        noteDao = db.projectNoteDao()
        store = ProjectStore(db.projectDao(), noteDao)
    }

    @After
    fun tearDown() = db.close()

    private fun project(name: String = "ARC-AGI-2") = runBlocking { store.create(name)!! }

    private fun note(
        projectId: String,
        subject: String,
        body: String,
        kind: String = ProjectNoteEntity.KIND_DECISION,
    ) = runBlocking {
        store.recordNote(
            projectId = projectId,
            kind = kind,
            subject = subject,
            body = body,
            sourceConversationId = "conv-1",
            sourceTurnAt = 1_000L,
        )
    }

    // ── Refusals ─────────────────────────────────────────────────────────

    @Test
    fun `a blank subject is refused rather than defaulted`() {
        val p = project()
        assertNull("a blank subject must not be written", note(p.id, "   ", "we chose Epoint"))
        assertTrue(runBlocking { store.activeNotes(p.id) }.isEmpty())
    }

    @Test
    fun `a blank body is refused`() {
        val p = project()
        assertNull(note(p.id, "payments", "   "))
    }

    @Test
    fun `an unknown kind is refused`() {
        val p = project()
        assertNull(note(p.id, "payments", "we chose Epoint", kind = "musing"))
    }

    @Test
    fun `a note for an unknown project is refused before the foreign key rejects it`() {
        assertNull(note("no-such-project", "payments", "we chose Epoint"))
    }

    // ── Supersession ─────────────────────────────────────────────────────

    @Test
    fun `a second decision on the same subject supersedes the first`() {
        val p = project()
        val first = note(p.id, "payments", "going with Epoint")!!
        val second = note(p.id, "payments", "switching to Lemon Squeezy")!!

        val active = runBlocking { store.activeNotes(p.id) }
        assertEquals(1, active.size)
        assertEquals(second.id, active.single().id)

        val old = runBlocking { noteDao.byId(first.id) }!!
        assertEquals(ProjectNoteEntity.STATE_SUPERSEDED, old.state)
        assertEquals("the displaced row must point at what replaced it", second.id, old.supersededBy)
    }

    @Test
    fun `the superseded row survives as history rather than being overwritten`() {
        val p = project()
        val first = note(p.id, "payments", "going with Epoint")!!
        note(p.id, "payments", "switching to Lemon Squeezy")

        val history = runBlocking {
            store.historyFor(p.id, ProjectNoteEntity.KIND_DECISION, "payments")
        }
        assertEquals(2, history.size)
        assertTrue(
            "the original wording has to still be readable",
            history.any { it.id == first.id && it.body == "going with Epoint" },
        )
    }

    @Test
    fun `a different subject does not supersede`() {
        val p = project()
        note(p.id, "payments", "going with Epoint")
        note(p.id, "hosting", "staying on Vercel")
        assertEquals(2, runBlocking { store.activeNotes(p.id) }.size)
    }

    @Test
    fun `the same kind and subject in a different project does not supersede`() {
        val a = project("RentEase")
        val b = project("Nebz")
        note(a.id, "payments", "going with Epoint")
        note(b.id, "payments", "going with Lemon Squeezy")
        assertEquals(1, runBlocking { store.activeNotes(a.id) }.size)
        assertEquals(1, runBlocking { store.activeNotes(b.id) }.size)
    }

    @Test
    fun `a blocker does not supersede a decision on the same subject`() {
        val p = project()
        note(p.id, "payments", "going with Epoint")
        note(p.id, "payments", "Epoint has not approved the account", ProjectNoteEntity.KIND_BLOCKER)
        assertEquals(2, runBlocking { store.activeNotes(p.id) }.size)
    }

    @Test
    fun `subjects are normalised so casing and spacing cannot fork a subject`() {
        val p = project()
        note(p.id, "Payments", "going with Epoint")
        note(p.id, "  payments  ", "switching to Lemon Squeezy")
        assertEquals(1, runBlocking { store.activeNotes(p.id) }.size)
    }

    @Test
    fun `restating the same thing keeps the original row and its date`() {
        val p = project()
        val first = note(p.id, "payments", "going with Epoint")!!
        val again = note(p.id, "payments", "Going With Epoint")!!

        assertEquals("a restatement must not start a new history entry", first.id, again.id)
        val active = runBlocking { store.activeNotes(p.id) }
        assertEquals(1, active.size)
        assertEquals(first.id, active.single().id)
        assertEquals(first.createdAt, active.single().createdAt)
    }

    /**
     * `recordNote` inserts before it supersedes and is deliberately not a
     * transaction, so a worker killed between the two steps leaves two active
     * rows. That is the intended failure direction — recoverable, unlike a
     * retired row with no replacement. This asserts it actually recovers.
     */
    @Test
    fun `two active rows left by an interrupted write are healed by the next one`() {
        val p = project()
        val a = note(p.id, "payments", "going with Epoint")!!
        // Simulate the interrupted write: a second active row on the same
        // subject, inserted without the supersession step having run.
        val orphan = a.copy(
            id = "orphan",
            body = "going with Stripe",
            createdAt = a.createdAt + 1,
        )
        runBlocking { noteDao.insert(orphan) }
        assertEquals(2, runBlocking { store.activeNotes(p.id) }.size)

        note(p.id, "payments", "switching to Lemon Squeezy")

        val active = runBlocking { store.activeNotes(p.id) }
        assertEquals("both stale rows must be retired, not just the newest", 1, active.size)
        assertEquals("switching to Lemon Squeezy", active.single().body)
    }

    // ── The cascade hazard ───────────────────────────────────────────────

    /**
     * The regression this whole DAO is shaped around.
     *
     * `projects` is a CASCADE parent. If any write to it were
     * `@Insert(onConflict = REPLACE)` — an implicit DELETE then INSERT — then
     * attributing a turn would silently empty the ledger and put the project
     * back, leaving a table that looks intact. That is not hypothetical:
     * `CreativeProjectDao` carries the post-mortem of it costing thirteen
     * drafted scenes.
     */
    @Test
    fun `attributing a turn does not delete the ledger`() {
        val p = project()
        note(p.id, "payments", "going with Epoint")
        note(p.id, "hosting", "staying on Vercel")

        runBlocking {
            repeat(3) { store.touch(p.id, 2_000L + it) }
            store.setStatus(p.id, ProjectEntity.STATUS_PAUSED)
        }

        assertEquals("notes must survive a parent write", 2, runBlocking { store.activeNotes(p.id) }.size)
        val after = runBlocking { store.get(p.id) }!!
        assertEquals(3, after.turnCount)
        assertEquals(ProjectEntity.STATUS_PAUSED, after.status)
    }

    @Test
    fun `deleting a project deletes its notes`() {
        val p = project()
        note(p.id, "payments", "going with Epoint")
        runBlocking { store.delete(p.id) }
        assertTrue(runBlocking { noteDao.allForBackup() }.isEmpty())
    }

    // ── Projects ─────────────────────────────────────────────────────────

    @Test
    fun `create is idempotent on name rather than throwing on the unique index`() {
        val first = project("RentEase")
        val second = runBlocking { store.create("RentEase") }
        assertEquals(first.id, second?.id)
    }

    @Test
    fun `a blank name is refused`() {
        assertNull(runBlocking { store.create("   ") })
    }

    @Test
    fun `lookup by name tolerates the casing a model will send`() {
        project("ARC-AGI-2")
        assertNotNull(runBlocking { store.byName("arc-agi-2") })
    }

    @Test
    fun `an unknown status is refused so the picker cannot be poisoned`() {
        val p = project()
        assertTrue(runBlocking { !store.setStatus(p.id, "shipped") })
        assertEquals(ProjectEntity.STATUS_ACTIVE, runBlocking { store.get(p.id) }!!.status)
    }

    @Test
    fun `active subjects are the vocabulary the extractor gets handed`() {
        val p = project()
        note(p.id, "payments", "going with Epoint")
        note(p.id, "hosting", "staying on Vercel")
        note(p.id, "payments", "switching to Lemon Squeezy")

        val subjects = runBlocking { store.activeSubjects(p.id) }.sorted()
        assertEquals(listOf("hosting", "payments"), subjects)
    }
}
