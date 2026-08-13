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

        val kept = dao.recent(50)
        assertEquals(
            listOf("TriggerWorker"),
            kept.map { it.worker },
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

        assertEquals(0, dao.recent(50).size)
    }
}
