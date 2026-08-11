package com.aura.tasks

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskDecayPassTest {

    private val day = 86_400_000L
    private val now = 1_760_000_000_000L
    private val dao = mockk<TaskDao>(relaxed = true)
    private val pass = TaskDecayPass(dao)

    private fun task(
        id: String,
        lastTouchedAt: Long = now,
        salience: Double = 1.0,
        description: String = "",
    ) = TaskEntity(
        id = id, title = "t", description = description, createdAt = now - 100 * day,
        lastTouchedAt = lastTouchedAt, salience = salience,
    )

    @Test
    fun `a task that crosses the threshold is marked with when it went quiet`() = runTest {
        coEvery { dao.allPending() } returns listOf(task("t1", lastTouchedAt = now - 200 * day))
        val saved = slot<TaskEntity>()
        coEvery { dao.update(capture(saved)) } returns Unit

        assertEquals(1, pass.run(now))
        assertTrue(TaskSalience.isQuiet(saved.captured.salience))
        assertEquals(now, saved.captured.quietSince, "quietSince was not stamped")
    }

    @Test
    fun `a task already quiet is not counted again`() = runTest {
        coEvery { dao.allPending() } returns listOf(task("t1", lastTouchedAt = now - 200 * day, salience = 0.05))
        assertEquals(0, pass.run(now), "an already-quiet task was reported as newly quiet")
    }

    @Test
    fun `an untouched pass writes nothing`() = runTest {
        // A hundred stable tasks on a quiet afternoon should cost no writes at
        // all — this runs every six hours forever.
        coEvery { dao.allPending() } returns listOf(task("t1", lastTouchedAt = now))
        pass.run(now)
        coVerify(exactly = 0) { dao.update(any()) }
    }

    @Test
    fun `rows the trigger engine parks in the tasks table are left alone`() = runTest {
        // These are content hashes for watched URLs, not things anyone wrote
        // down. Scoring them would be meaningless, and writing to them would be
        // corrupting somebody else's storage.
        val phantom = task("t1", lastTouchedAt = now - 500 * day, description = "trigger-hash:https://example.com")
        coEvery { dao.allPending() } returns listOf(phantom)

        assertEquals(0, pass.run(now))
        coVerify(exactly = 0) { dao.update(any()) }
    }

    @Test
    fun `a read failure does not take the decay worker down with it`() = runTest {
        coEvery { dao.allPending() } throws IllegalStateException("db gone")
        assertEquals(0, pass.run(now))
    }

    @Test
    fun `coming back out of quiet clears the stamp`() = runTest {
        // Due tomorrow pulls it back above the threshold; the "quiet since"
        // stamp must not survive, or the UI would claim it is still quiet.
        val revived = task("t1", lastTouchedAt = now - 200 * day, salience = 0.05)
            .copy(dueAt = now + day, quietSince = now - 10 * day)
        coEvery { dao.allPending() } returns listOf(revived)
        val saved = slot<TaskEntity>()
        coEvery { dao.update(capture(saved)) } returns Unit

        pass.run(now)
        assertTrue(!TaskSalience.isQuiet(saved.captured.salience))
        assertEquals(0L, saved.captured.quietSince)
    }
}
