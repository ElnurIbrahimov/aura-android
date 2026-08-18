package com.aura.proactive

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkerParameters
import com.aura.data.UserPreferences
import com.aura.health.WorkerRunEntity
import com.aura.health.WorkerRunRecorder
import com.aura.memory.MemoryStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DecayWorkerTest {

    private var db: ProactiveEventDatabase? = null

    @After
    fun tearDown() {
        db?.close()
    }

    /** A real recorder over an in-memory database — the prune has to delete rows. */
    private fun realRecorder(): WorkerRunRecorder {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val opened = Room.inMemoryDatabaseBuilder(context, ProactiveEventDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db = opened
        return WorkerRunRecorder(opened.workerRunDao())
    }

    @Test
    fun `doWork calls runDecayPass and returns success`() = runBlocking {
        val mockContext = mockk<Context>(relaxed = true)
        val mockParams = mockk<WorkerParameters>(relaxed = true)
        val mockMemoryStore = mockk<MemoryStore>(relaxed = true)
        val mockPrefs = mockk<UserPreferences>(relaxed = true)
        every { mockPrefs.decayEnabled } returns flowOf(true)

        val worker = DecayWorker(mockContext, mockParams, mockMemoryStore, mockPrefs)

        val result = worker.doWork()

        coVerify(exactly = 1) { mockMemoryStore.runDecayPass() }
        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
    }

    @Test
    fun `doWork skips decay when disabled`() = runBlocking {
        val mockContext = mockk<Context>(relaxed = true)
        val mockParams = mockk<WorkerParameters>(relaxed = true)
        val mockMemoryStore = mockk<MemoryStore>(relaxed = true)
        val mockPrefs = mockk<UserPreferences>(relaxed = true)
        every { mockPrefs.decayEnabled } returns flowOf(false)

        val worker = DecayWorker(mockContext, mockParams, mockMemoryStore, mockPrefs)

        val result = worker.doWork()

        coVerify(exactly = 0) { mockMemoryStore.runDecayPass() }
        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
    }

    /**
     * The run log is a log, not a record, and something has to say so.
     *
     * `WorkerRunRecorder.prune()` shipped with a retention window, a unit test,
     * a KDoc naming the sweep that called it — and no caller. The sweep it named
     * prunes two other tables. Nothing in the app bounded this one, and nothing
     * disagreed, because the only test of `prune()` called it directly.
     */
    @Test
    fun `the worker run log is pruned by the sweep`() = runBlocking {
        val recorder = realRecorder()
        val dao = db!!.workerRunDao()
        val now = System.currentTimeMillis()
        val day = 24L * 60 * 60 * 1000

        dao.insert(WorkerRunEntity(worker = "DreamWorker", startedAt = now - 40 * day))
        dao.insert(WorkerRunEntity(worker = "DaemonWorker", startedAt = now - 31 * day))
        dao.insert(WorkerRunEntity(worker = "TriggerWorker", startedAt = now - 2 * day))
        assertEquals(3, dao.recent(50).size)

        val prefs = mockk<UserPreferences>(relaxed = true)
        every { prefs.decayEnabled } returns flowOf(true)
        DecayWorker(
            mockk<Context>(relaxed = true),
            mockk<WorkerParameters>(relaxed = true),
            mockk<MemoryStore>(relaxed = true),
            prefs,
            workerRunRecorder = recorder,
        ).doWork()

        // DecayWorker now records its own run, and record() inserts that row
        // before running the block — so the sweep sees a fresh row of its own
        // and correctly keeps it. Excluded here so the assertion stays about
        // retention rather than about this worker's bookkeeping.
        val kept = dao.recent(50).map { it.worker }.filterNot { it == DecayWorker.WORKER_NAME }
        assertEquals(
            listOf("TriggerWorker"),
            kept,
            "the 6-hourly sweep did not bound the worker run log — it grows a row per run forever",
        )
    }

    /**
     * The half that would silently regress. `decayEnabled` means "do not let my
     * memories fade"; moving this call below that gate would quietly turn
     * retention off for anyone who set it, which is the same shape of mistake as
     * having no caller at all.
     */
    @Test
    fun `the run log is pruned even when memory decay is switched off`() = runBlocking {
        val recorder = realRecorder()
        val dao = db!!.workerRunDao()
        val now = System.currentTimeMillis()

        dao.insert(WorkerRunEntity(worker = "DreamWorker", startedAt = now - 40L * 24 * 60 * 60 * 1000))
        assertEquals(1, dao.recent(50).size)

        val prefs = mockk<UserPreferences>(relaxed = true)
        every { prefs.decayEnabled } returns flowOf(false)
        DecayWorker(
            mockk<Context>(relaxed = true),
            mockk<WorkerParameters>(relaxed = true),
            mockk<MemoryStore>(relaxed = true),
            prefs,
            workerRunRecorder = recorder,
        ).doWork()

        assertEquals(
            0,
            dao.recent(50).count { it.worker != DecayWorker.WORKER_NAME },
            "retention stopped applying when the user switched decay off",
        )
    }

    // ---- the worker's own run record --------------------------------------

    /**
     * This worker held a [WorkerRunRecorder] and used it only to call `prune()`,
     * so the job that sweeps the run log was the one job missing from it. Six
     * hours of real work leaving no evidence is indistinguishable, in
     * BackgroundHealth, from never having been scheduled.
     */
    @Test
    fun `a completed pass records what it did`() = runBlocking {
        val recorder = realRecorder()
        val dao = db!!.workerRunDao()
        val memoryStore = mockk<MemoryStore>(relaxed = true)
        coEvery { memoryStore.runDecayPass() } returns 7
        val prefs = mockk<UserPreferences>(relaxed = true)
        every { prefs.decayEnabled } returns flowOf(true)

        DecayWorker(
            mockk<Context>(relaxed = true),
            mockk<WorkerParameters>(relaxed = true),
            memoryStore,
            prefs,
            workerRunRecorder = recorder,
        ).doWork()

        val row = dao.recent(50).single { it.worker == DecayWorker.WORKER_NAME }
        assertEquals(WorkerRunEntity.OUTCOME_OK, row.outcome)
        assertEquals("7 memories faded", row.detail)
    }

    @Test
    fun `a switched-off pass records that, rather than nothing`() = runBlocking {
        val recorder = realRecorder()
        val dao = db!!.workerRunDao()
        val prefs = mockk<UserPreferences>(relaxed = true)
        every { prefs.decayEnabled } returns flowOf(false)

        DecayWorker(
            mockk<Context>(relaxed = true),
            mockk<WorkerParameters>(relaxed = true),
            mockk<MemoryStore>(relaxed = true),
            prefs,
            workerRunRecorder = recorder,
        ).doWork()

        val row = dao.recent(50).single { it.worker == DecayWorker.WORKER_NAME }
        assertEquals(WorkerRunEntity.OUTCOME_SKIPPED, row.outcome)
        assertEquals("memory decay is switched off", row.detail)
    }


    /**
     * Sixth sweep, same property as the run-log prune above: world-history
     * compaction is retention, not decay, so it runs whether or not the user
     * switched memory decay off.
     */
    @Test
    fun `world history is compacted even when memory decay is switched off`() = runBlocking {
        val worldDao = mockk<com.aura.creative.livingworld.LivingWorldDao>(relaxed = true)
        val eventDao = mockk<com.aura.creative.livingworld.LivingEventDao>(relaxed = true)
        coEvery { worldDao.all() } returns listOf(
            com.aura.creative.livingworld.LivingWorldEntity(
                id = "w1", projectId = "p1", branchId = "b1", rootSeed = 1L,
                worldEpochMs = 0L, currentTick = 1_000L, stateJson = "{}",
                createdAt = 0L, updatedAt = 0L,
            ),
        )
        coEvery { eventDao.count("w1") } returns 10

        val prefs = mockk<UserPreferences>(relaxed = true)
        every { prefs.decayEnabled } returns flowOf(false)
        DecayWorker(
            mockk<Context>(relaxed = true),
            mockk<WorkerParameters>(relaxed = true),
            mockk<MemoryStore>(relaxed = true),
            prefs,
            livingWorldStore = com.aura.creative.livingworld.LivingWorldStore(worldDao, eventDao),
        ).doWork()

        coVerify(exactly = 1) {
            eventDao.trimNoiseBefore(
                "w1",
                1_000L - com.aura.creative.livingworld.LivingWorldStore.COMPACTION_HORIZON_TICKS,
                com.aura.creative.livingworld.NotabilityScorer.DEFAULT_FLOOR,
            )
        }
    }
}
