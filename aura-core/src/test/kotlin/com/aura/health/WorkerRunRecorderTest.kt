package com.aura.health

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aura.proactive.ProactiveEventDatabase
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
 * Making Aura's background life legible.
 *
 * The property that matters is not that failures are recorded — it is that
 * **a skip is recorded, with its reason**. Almost everything Aura does in the
 * background no-ops on a missing precondition, and a worker that ran, found its
 * toggle off, and returned success is indistinguishable from one that worked
 * perfectly and from one that never fired at all. Three states, one appearance.
 *
 * And the recorder must never be able to break what it observes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class WorkerRunRecorderTest {

    private lateinit var db: ProactiveEventDatabase
    private lateinit var recorder: WorkerRunRecorder

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ProactiveEventDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        recorder = WorkerRunRecorder(db.workerRunDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `a skip is recorded with the reason, not silently`(): Unit = runBlocking {
        recorder.record("DreamWorker") {
            Unit to WorkerRunRecorder.Result.skipped("dreams are switched off")
        }

        val run = recorder.latestPerWorker().single()
        assertEquals(WorkerRunEntity.OUTCOME_SKIPPED, run.outcome)
        assertEquals("dreams are switched off", run.detail)
    }

    @Test
    fun `a successful run says what it produced`(): Unit = runBlocking {
        recorder.record("DreamWorker") { 3 to WorkerRunRecorder.Result.ok("3 summaries, 2 clusters") }

        val run = recorder.latestPerWorker().single()
        assertEquals(WorkerRunEntity.OUTCOME_OK, run.outcome)
        assertEquals("3 summaries, 2 clusters", run.detail)
        assertTrue(run.finishedAt >= run.startedAt)
    }

    @Test
    fun `a thrown failure is recorded and swallowed`(): Unit = runBlocking {
        // A health record that can break the thing it observes is worse than no
        // health record, so the throw does not escape.
        val result = recorder.record<Unit>("DaemonWorker") { error("no network") }

        assertNull(result)
        val run = recorder.latestPerWorker().single()
        assertEquals(WorkerRunEntity.OUTCOME_FAILED, run.outcome)
        assertEquals("no network", run.detail)
    }

    @Test
    fun `a run that never finishes still leaves evidence it started`(): Unit = runBlocking {
        // Written before the work, because the usual reason a worker produces
        // nothing is being killed rather than failing — and a killed run that
        // left no row is indistinguishable from one that never started.
        db.workerRunDao().insert(WorkerRunEntity(worker = "DreamWorker", startedAt = 1_000L))

        val run = recorder.latestPerWorker().single()
        assertEquals(0L, run.finishedAt, "an unfinished row is itself the finding")
    }

    @Test
    fun `the latest run of each worker is what a health view shows`(): Unit = runBlocking {
        recorder.record("DreamWorker") { Unit to WorkerRunRecorder.Result.ok("first") }
        recorder.record("DreamWorker") { Unit to WorkerRunRecorder.Result.ok("second") }
        recorder.record("DaemonWorker") { Unit to WorkerRunRecorder.Result.ok("daemon") }

        val latest = recorder.latestPerWorker()
        assertEquals(2, latest.size)
        assertEquals("second", latest.first { it.worker == "DreamWorker" }.detail)
    }

    @Test
    fun `the log is pruned rather than kept forever`(): Unit = runBlocking {
        val now = System.currentTimeMillis()
        db.workerRunDao().insert(WorkerRunEntity(worker = "Old", startedAt = now - 40L * 24 * 60 * 60 * 1000))
        db.workerRunDao().insert(WorkerRunEntity(worker = "Recent", startedAt = now))

        assertEquals(1, recorder.prune(now))
        assertEquals(listOf("Recent"), recorder.recent().map { it.worker })
    }

    @Test
    fun `the value from the block is returned unchanged`(): Unit = runBlocking {
        val value = recorder.record("W") { "payload" to WorkerRunRecorder.Result.ok("") }
        assertNotNull(value)
        assertEquals("payload", value)
    }
}
