package com.aura.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.agent.ConversationDao
import com.aura.memory.MemoryDatabase
import com.aura.memory.MemoryEntity
import com.aura.tasks.TaskDatabase
import com.aura.tasks.TaskEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFailsWith

/**
 * A failed restore must not empty databases it was never able to refill.
 *
 * Every other test of this path runs against `mockk(relaxed = true)` DAOs, where
 * "the table was purged" is a recorded call rather than an absent row. That is
 * why the defect below survived: `restore()` called `purgeAll()` and only then
 * looked for a snapshot to restore from, so when there was no snapshot the
 * tables stayed empty and the only evidence was a log line. A mock cannot tell
 * you your data is gone.
 *
 * Two ordinary things produce no snapshot. The spool is written to disk and can
 * fail or be unreadable, and MERGE never required one in the first place —
 * `restore()` refuses to start without a snapshot only when the mode is REPLACE.
 * A merge is additive (every delete inside `writeEverything` is guarded on
 * REPLACE), which is exactly what made the purge on its failure path indefensible:
 * it destroyed rows the import had not touched, on the operation the confirmation
 * dialog describes as "nothing is deleted".
 *
 * These run against real Room databases so the assertions are about rows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class RestoreSurvivesFailureTest {

    private lateinit var memoryDb: MemoryDatabase
    private lateinit var taskDb: TaskDatabase

    /** Fails the strict pre-restore snapshot AND the import, at different points. */
    private val conversationDao = mockk<ConversationDao>(relaxed = true)

    private lateinit var manager: BackupManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        memoryDb = Room.inMemoryDatabaseBuilder(context, MemoryDatabase::class.java)
            .allowMainThreadQueries().build()
        taskDb = Room.inMemoryDatabaseBuilder(context, TaskDatabase::class.java)
            .allowMainThreadQueries().build()

        manager = BackupManager(
            context = context,
            memoryDao = memoryDb.memoryDao(),
            memoryEditDao = memoryDb.memoryEditDao(),
            documentDao = memoryDb.documentDao(),
            creativeProjectDao = memoryDb.creativeProjectDao(),
            conversationDao = conversationDao,
            kgDao = memoryDb.knowledgeGraphDao(),
            handDao = mockk(relaxed = true),
            taskDao = taskDb.taskDao(),
            reminderDao = taskDb.reminderDao(),
            proactiveEventDao = mockk(relaxed = true),
            userProfileDao = mockk(relaxed = true),
            providerKeys = mockk(relaxed = true),
            // Left as a bare relaxed mock on purpose: its preference flows are
            // empty, so the strict snapshot fails and `writeRollbackSnapshot`
            // returns null. That is the condition under test, not a shortcut.
            userPreferences = mockk(relaxed = true),
            reminderScheduler = mockk(relaxed = true),
            handScheduler = mockk(relaxed = true),
            usageTracker = mockk(relaxed = true),
            evolutionProposalDao = mockk(relaxed = true),
            evolutionSettingsDao = mockk(relaxed = true),
            evolutionRevisionDao = mockk(relaxed = true),
            agentDao = mockk(relaxed = true),
        )
    }

    @After
    fun tearDown() {
        memoryDb.close()
        taskDb.close()
    }

    private fun preExisting() = MemoryEntity(
        id = "pre-1",
        content = "the thing the user actually typed",
        source = "user",
        category = "fact",
    )

    private fun importing() = AuraBackup(
        exportedAt = 0L,
        appVersionName = "0.1.0",
        memories = listOf(
            MemoryBackup(
                "im-1", "imported", "user", "fact", "general",
                0.5f, 1L, 1L, 0, 1f, "", "{}",
            ),
        ),
        conversations = listOf(
            ConversationBackup("c1", "t", 1L, 2L, null, "m", "{}", "[]"),
        ),
    )

    @Test
    fun `a merge with no snapshot leaves pre-existing rows in place when the import fails`() = runBlocking {
        memoryDb.memoryDao().insertAll(listOf(preExisting()))
        taskDb.taskDao().insert(TaskEntity(id = "t-1", title = "unrelated task", createdAt = 1L))
        conversationDao.let { coEvery { it.insertAll(any()) } throws RuntimeException("disk full") }

        assertFailsWith<RuntimeException> {
            manager.restore(importing(), BackupManager.RestoreMode.MERGE)
        }

        val memories = memoryDb.memoryDao().allForExport()
        assertTrue(
            "the user's pre-existing memory was destroyed by a failed merge: $memories",
            memories.any { it.id == "pre-1" },
        )
        assertEquals("an unrelated table was emptied too", 1, taskDb.taskDao().all().size)
    }

    @Test
    fun `the failed import's own rows may survive, but nothing else is touched`() = runBlocking {
        // Rows written before the failure are left as-is. That is worse than a
        // clean rollback and much better than an empty database, and it is what
        // the interrupted-restore marker exists to tell the next launch about.
        memoryDb.memoryDao().insertAll(listOf(preExisting()))
        conversationDao.let { coEvery { it.insertAll(any()) } throws RuntimeException("disk full") }

        assertFailsWith<RuntimeException> {
            manager.restore(importing(), BackupManager.RestoreMode.MERGE)
        }

        val ids = memoryDb.memoryDao().allForExport().map { it.id }.toSet()
        assertTrue("pre-existing row must survive", "pre-1" in ids)
    }

    @Test
    fun `a merge that succeeds adds rows without removing any`() = runBlocking {
        memoryDb.memoryDao().insertAll(listOf(preExisting()))

        manager.restore(importing(), BackupManager.RestoreMode.MERGE)

        val ids = memoryDb.memoryDao().allForExport().map { it.id }.toSet()
        assertTrue("import did not land: $ids", "im-1" in ids)
        assertTrue("merge removed a pre-existing row: $ids", "pre-1" in ids)
    }
}
