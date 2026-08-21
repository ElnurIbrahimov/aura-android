package com.aura.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.memory.MemoryDatabase
import com.aura.security.BackupCrypto
import com.aura.tasks.TaskDatabase
import com.aura.tasks.TaskEntity
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A sealed backup file, written to disk and read back, restores real rows into
 * real databases.
 *
 * `ENGINEERING_HISTORY` has asked for this on a phone across several features,
 * and the sentence it keeps repeating is the right one: a backup that has never
 * been restored is not a backup. It is still true that nothing here has run on a
 * device. What was *also* true, and did not have to be, is that no test anywhere
 * took the whole path either — the pieces each had coverage and the seam between
 * them had none:
 *
 *  - `BackupManagerTest` seals and unseals a string, with mocked DAOs.
 *  - `BackupCryptoTest` covers the envelope.
 *  - `RestoreSurvivesFailureTest` covers restore against real Room, from an
 *    in-memory `AuraBackup`.
 *
 * None of them wrote a file. So the one step that only happens in production —
 * JSON serialised, sealed, committed to disk, read back off disk, unsealed,
 * parsed, and written into databases — was the step nothing exercised, and it is
 * the step where an encoding, a truncation, or a schema-version mismatch would
 * actually show up.
 *
 * This closes everything except the device itself: a real file, real PBKDF2, and
 * real SQLite with the head schema.
 *
 * Writing it immediately found something, which is the argument for having
 * written it: the first version restored with REPLACE and was refused, because
 * REPLACE will not start without a pre-restore snapshot. That is the guard
 * doing its job, so it is now asserted here rather than worked around.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SealedFileRestoresTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var memoryDb: MemoryDatabase
    private lateinit var taskDb: TaskDatabase
    private lateinit var manager: BackupManager

    private val passphrase = "a passphrase the user actually typed"

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
            conversationDao = mockk(relaxed = true),
            kgDao = memoryDb.knowledgeGraphDao(),
            handDao = mockk(relaxed = true),
            taskDao = taskDb.taskDao(),
            reminderDao = taskDb.reminderDao(),
            proactiveEventDao = mockk(relaxed = true),
            userProfileDao = mockk(relaxed = true),
            providerKeys = mockk(relaxed = true),
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

    /** What the user would lose. Two databases, so this is not a one-table trick. */
    private fun backupWithRealRows() = AuraBackup(
        exportedAt = 1_700_000_000_000L,
        appVersionName = "0.66.0",
        memories = listOf(
            MemoryBackup(
                "m-1", "ARC-AGI-2 targets 95% through architecture, not scale",
                "user", "fact", "general", 0.9f, 100L, 200L, 3, 1f, "arc,research", "{}",
            ),
            MemoryBackup(
                "m-2", "Lemon Squeezy, because Stripe does not operate here",
                "user", "preference", "general", 0.8f, 101L, 201L, 1, 1f, "payments", "{}",
            ),
        ),
        tasks = listOf(
            TaskBackup(
                id = "t-1",
                title = "restore a backup from a real file",
                description = "",
                createdAt = 300L,
                dueAt = null,
                completedAt = null,
                status = "pending",
                recurrence = null,
                priority = 0,
                tags = "",
            ),
        ),
    )

    /** Export, seal, and commit to disk exactly as the worker would. */
    private fun writeSealedFile(backup: AuraBackup): File {
        val file = File(temp.newFolder("backups"), "aura-backup-20260821-120000.json")
        val sealed = BackupCrypto().seal(manager.encodeToJson(backup), passphrase)
        requireNotNull(sealed) { "seal returned null; nothing was written" }
        file.writeText(sealed)
        return file
    }

    @Test
    fun `a sealed file on disk restores rows into real databases`() = runBlocking {
        val file = writeSealedFile(backupWithRealRows())

        // Nothing above this line is in memory any more. Everything below starts
        // from bytes on disk, which is the part that only happened in production.
        val onDisk = file.readText()
        assertTrue("the file is not a sealed envelope", manager.isSealed(onDisk))
        assertNotEquals(
            "the passphrase content is sitting in the file in the clear",
            true,
            onDisk.contains("ARC-AGI-2"),
        )

        val plaintext = manager.unseal(onDisk, passphrase)
        requireNotNull(plaintext) { "unseal returned null for the correct passphrase" }
        val decoded = manager.decodeFromJson(plaintext)

        // MERGE, not REPLACE, and the reason is asserted in its own test below:
        // REPLACE refuses outright when a pre-restore snapshot cannot be taken,
        // which is the case here and is the guard working. Restoring into empty
        // databases is the fresh-device case anyway, which is what a backup is
        // for.
        val counts = manager.restore(decoded, BackupManager.RestoreMode.MERGE)

        val memories = memoryDb.memoryDao().allForExport().associateBy { it.id }
        assertEquals("both memories should be back", 2, memories.size)
        assertEquals(
            "ARC-AGI-2 targets 95% through architecture, not scale",
            memories.getValue("m-1").content,
        )
        // Not just the text: the fields that make a memory findable again.
        assertEquals(0.9f, memories.getValue("m-1").importance)
        assertEquals(3, memories.getValue("m-1").accessCount)
        assertEquals("arc,research", memories.getValue("m-1").tags)

        val tasks = taskDb.taskDao().all()
        assertEquals("the task should be back", 1, tasks.size)
        assertEquals("restore a backup from a real file", tasks.first().title)

        assertEquals(2, counts.memories)
        assertEquals(1, counts.tasks)
    }

    @Test
    fun `replace refuses when no pre-restore snapshot can be taken`() = runBlocking {
        // Found by writing the test above, which tried REPLACE and was refused.
        //
        // REPLACE purges before it writes, so starting one without a snapshot
        // behind it leaves the user one failed insert away from an empty
        // database. Refusing is recoverable; purging is not. The refusal is the
        // forward half of the same guarantee the failure path got when the
        // rollback stopped purging before checking for a spool to restore from.
        //
        // The snapshot cannot be taken here because the preference flows this
        // manager reads are empty — which is a fair stand-in for the real
        // reasons it fails: a full disk, an evicted cache file, a DataStore that
        // will not read.
        val file = writeSealedFile(backupWithRealRows())
        taskDb.taskDao().insert(TaskEntity(id = "keep", title = "already here", createdAt = 1L))

        val decoded = manager.decodeFromJson(requireNotNull(manager.unseal(file.readText(), passphrase)))
        val thrown = runCatching {
            manager.restore(decoded, BackupManager.RestoreMode.REPLACE)
        }.exceptionOrNull()

        assertTrue(
            "expected a refusal, got " + thrown,
            thrown is IllegalStateException &&
                thrown.message.orEmpty().contains("Replace-all restore refused"),
        )
        // Refused means refused: nothing purged, nothing written.
        assertEquals(1, taskDb.taskDao().all().size)
        assertEquals(0, memoryDb.memoryDao().allForExport().size)
    }

    @Test
    fun `the wrong passphrase does not restore anything`() = runBlocking {
        val file = writeSealedFile(backupWithRealRows())
        taskDb.taskDao().insert(TaskEntity(id = "keep", title = "already here", createdAt = 1L))

        assertEquals(null, manager.unseal(file.readText(), "not the passphrase"))

        // The point is what did NOT happen: a failed unseal must not have reached
        // the restore path at all, so pre-existing rows are untouched.
        assertEquals(1, taskDb.taskDao().all().size)
        assertEquals("already here", taskDb.taskDao().all().first().title)
    }

    @Test
    fun `a truncated file fails to open rather than restoring a fragment`() = runBlocking {
        val file = writeSealedFile(backupWithRealRows())
        val whole = file.readText()
        // Half a file is the shape a disk filling up mid-write leaves behind, and
        // the failure has to be "cannot open" rather than "opened, found fewer
        // rows" — a partial restore of a REPLACE is a silent deletion.
        file.writeText(whole.substring(0, whole.length / 2))

        assertEquals(null, manager.unseal(file.readText(), passphrase))
        assertEquals(0, memoryDb.memoryDao().allForExport().size)
    }
}
