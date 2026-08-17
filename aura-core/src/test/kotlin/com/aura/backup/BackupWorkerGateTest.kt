package com.aura.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkerParameters
import com.aura.data.UserPreferences
import com.aura.health.WorkerRunEntity
import com.aura.health.WorkerRunRecorder
import com.aura.proactive.ProactiveEventDatabase
import com.aura.security.SecureDataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import com.aura.backup.BackupService
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A backup that quietly does nothing is the failure it exists to prevent.
 *
 * Every other background worker in this app gets another go tomorrow and loses
 * nothing by skipping. This one is protecting the week it was supposed to run
 * in, so *why* it did not run has to be recoverable afterwards — which means a
 * reason, in words, in both the run log and the preference the Settings screen
 * reads.
 *
 * The three preconditions below are all reachable states a person can leave the
 * app in: switched on before picking a folder, folder revoked by the system,
 * passphrase never set. None of them are errors and none of them should look
 * like success.
 *
 * What is deliberately NOT here: writing the file. `DocumentFile.fromTreeUri`
 * against a real SAF provider is a device concern, and a Robolectric shadow of
 * it would be asserting that the mock works. That half is in the manual device
 * plan, where it belongs — and until it runs, this feature is not verified.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class BackupWorkerGateTest {

    private fun worker(
        enabled: Boolean = true,
        folder: String? = "content://tree/backups",
        passphrase: String? = "a good long passphrase",
        prefs: UserPreferences = mockk(relaxed = true),
    ): Pair<BackupWorker, UserPreferences> {
        every { prefs.autoBackupEnabled } returns flowOf(enabled)
        every { prefs.backupFolderUri } returns flowOf(folder)
        val secure = mockk<SecureDataStore>(relaxed = true)
        coEvery { secure.getString(UserPreferences.BACKUP_PASSPHRASE_KEY) } returns passphrase
        val worker = BackupWorker(
            mockk<Context>(relaxed = true),
            mockk<WorkerParameters>(relaxed = true),
            mockk<BackupService>(relaxed = true),
            prefs,
            secure,
            recorder = null,
        )
        return worker to prefs
    }

    @Test
    fun `switched off writes nothing`() = runBlocking {
        val (worker, prefs) = worker(enabled = false)

        assertFalse(worker.runNow())
        // Not an error: the user chose this. Recording one would put a red row
        // in Settings for a switch working exactly as set.
        coVerify(exactly = 0) { prefs.recordBackupOutcome(any(), any()) }
    }

    @Test
    fun `switched on with no folder chosen writes nothing`() = runBlocking {
        val (worker, prefs) = worker(folder = null)

        assertFalse(worker.runNow())
        coVerify(exactly = 0) { prefs.recordBackupOutcome(any(), any()) }
    }

    @Test
    fun `switched on with no passphrase writes nothing`() = runBlocking {
        val (worker, prefs) = worker(passphrase = null)

        assertFalse(worker.runNow())
        coVerify(exactly = 0) { prefs.recordBackupOutcome(any(), any()) }
    }

    /**
     * The half that matters most. A revoked folder grant, a full disk, a
     * provider that has gone away — all throw somewhere inside the write, and
     * all of them have to end as a recorded reason rather than a crash or a
     * silent success.
     */
    @Test
    fun `a failed write is recorded, not thrown`() = runBlocking {
        val prefs = mockk<UserPreferences>(relaxed = true)
        val (worker, _) = worker(prefs = prefs)

        // The mock Context has no real ContentResolver, so the write fails the
        // way a revoked grant would.
        assertFalse(worker.runNow(now = 1_700_000_000_000L))

        coVerify(exactly = 1) {
            prefs.recordBackupOutcome(1_700_000_000_000L, match { it.isNotBlank() })
        }
    }

    /**
     * The recorder is optional across every worker in this repo, and a health
     * record must never be able to break the thing it observes.
     */
    @Test
    fun `it runs with no recorder attached`() = runBlocking {
        val (worker, _) = worker(enabled = false)
        assertFalse(worker.runNow())
    }

    /**
     * The reason has to reach the run log in words, because that log is what
     * BackgroundHealth reads and "why is nothing happening" is the question that
     * screen exists to answer.
     *
     * A real recorder over an in-memory database rather than a mock: what is
     * being checked is the *text a person will read*, and a mock can only confirm
     * that a method was called.
     */
    @Test
    fun `a skip reaches the run log with its reason`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ProactiveEventDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val prefs = mockk<UserPreferences>(relaxed = true)
            every { prefs.autoBackupEnabled } returns flowOf(true)
            every { prefs.backupFolderUri } returns flowOf(null)
            val secure = mockk<SecureDataStore>(relaxed = true)
            coEvery { secure.getString(any()) } returns "a good long passphrase"

            BackupWorker(
                mockk<Context>(relaxed = true),
                mockk<WorkerParameters>(relaxed = true),
                mockk<BackupService>(relaxed = true),
                prefs,
                secure,
                WorkerRunRecorder(db.workerRunDao()),
            ).doWork()

            val row = db.workerRunDao().recent(10).single()
            assertEquals(BackupWorker.WORKER_NAME, row.worker)
            assertEquals(WorkerRunEntity.OUTCOME_SKIPPED, row.outcome)
            assertEquals("no backup folder has been chosen", row.detail)
        } finally {
            db.close()
        }
    }

    /**
     * The elvis form of this — `recorder?.record(...) ?: runNow()` — ran the
     * whole snapshot, key derivation and write a second time whenever `record`
     * returned null, which is precisely the failure path. Found by this test
     * suite rather than by reading it.
     */
    @Test
    fun `a failing run is attempted once, not twice`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, ProactiveEventDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val prefs = mockk<UserPreferences>(relaxed = true)
            every { prefs.autoBackupEnabled } returns flowOf(true)
            every { prefs.backupFolderUri } returns flowOf("content://tree/backups")
            val secure = mockk<SecureDataStore>(relaxed = true)
            coEvery { secure.getString(any()) } returns "a good long passphrase"
            val manager = mockk<BackupService>(relaxed = true)

            BackupWorker(
                mockk<Context>(relaxed = true),
                mockk<WorkerParameters>(relaxed = true),
                manager,
                prefs,
                secure,
                WorkerRunRecorder(db.workerRunDao()),
            ).doWork()

            coVerify(exactly = 1) { manager.snapshot(any()) }
            assertEquals(1, db.workerRunDao().recent(10).size)
        } finally {
            db.close()
        }
    }
}
