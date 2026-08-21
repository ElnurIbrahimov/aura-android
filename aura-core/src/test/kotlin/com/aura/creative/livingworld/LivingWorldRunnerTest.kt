package com.aura.creative.livingworld

import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The property that decides whether coming back after a long absence is
 * pleasant or punishing: **catch-up cost must not scale with how long the user
 * was gone.**
 *
 * Without the fold, three months away would be 2,160 simulated ticks and as
 * many events, arriving as an unreadable wall and a worker that never finishes
 * its slice. With it, three days and three months cost the same.
 */
class LivingWorldRunnerTest {

    private val worldDao = mockk<LivingWorldDao>(relaxed = true)
    private val eventDao = mockk<LivingEventDao>(relaxed = true)
    private val store = LivingWorldStore(worldDao, eventDao)
    private val runner = LivingWorldRunner(store)

    init {
        // commitTick returns rows-changed, and a relaxed mock answers 0 — which
        // is the signal for "somebody else moved the world first, throw this
        // slice away". Stated once here so a test that is not about contention
        // does not silently become one.
        coEvery { worldDao.commitTick(any(), any(), any(), any(), any()) } returns 1
    }

    private val epoch = 1_700_000_000_000L
    private val hour = WorldClock.TICK_REAL_MS

    private fun world(currentTick: Long = 0L): LivingWorldEntity {
        val state = WorldSeeder().seed(
            com.aura.creative.WorldBible(
                factions = listOf(
                    com.aura.creative.WorldFaction(id = "a", name = "Ashfall", rivals = listOf("Bramwatch")),
                    com.aura.creative.WorldFaction(id = "b", name = "Bramwatch", rivals = listOf("Ashfall")),
                    com.aura.creative.WorldFaction(id = "c", name = "Cormere"),
                ),
            ),
        )
        return LivingWorldEntity(
            id = "w1",
            projectId = "p1",
            branchId = "main",
            rootSeed = 99L,
            worldEpochMs = epoch,
            currentTick = currentTick,
            stateJson = store.encode(state),
        )
    }

    private fun capturedTick(): Long {
        val tick = slot<Long>()
        coVerify { worldDao.commitTick(any(), capture(tick), any(), any(), any()) }
        return tick.captured
    }

    @Test
    fun `a ninety day absence costs one fold plus one detail slice`() = runTest {
        val w = world()
        coEvery { worldDao.byId("w1") } returns w
        val events = slot<List<LivingEventEntity>>()
        coEvery { eventDao.upsertAll(capture(events)) } returns Unit

        val now = epoch + 90 * 24 * hour // 2,160 ticks owed
        val outcome = runner.runSlice("w1", deadlineMs = Long.MAX_VALUE, isStopped = { false }, now = { now })

        assertEquals(TickOutcome.PAUSED_FOR_TIME, outcome)
        // Folded straight to (due - detail window), then simulated one slice.
        val expected = (2_160L - LivingWorldRunner.DETAIL_WINDOW_TICKS) + LivingWorldRunner.MAX_TICKS_PER_SLICE
        assertEquals(expected, capturedTick())

        val quiet = events.captured.count { it.kind == WorldEngine.KIND_QUIET_INTERVAL }
        assertEquals(1, quiet, "a long absence should collapse into exactly one quiet interval")
        assertTrue(
            events.captured.size < 200,
            "a 90-day absence produced ${events.captured.size} events — the fold is not bounding the catch-up",
        )
    }

    @Test
    fun `three days and three months cost the same number of simulated ticks`() = runTest {
        suspend fun ticksFor(days: Long): Long {
            val w = world()
            coEvery { worldDao.byId("w1") } returns w
            val tick = slot<Long>()
            coEvery { worldDao.commitTick(any(), capture(tick), any(), any(), any()) } returns 1
            val now = epoch + days * 24 * hour
            runner.runSlice("w1", Long.MAX_VALUE, { false }, { now })
            val due = days * 24
            // Everything beyond the detail window is folded, so what was
            // actually simulated is whatever the slice covered.
            return tick.captured - (due - LivingWorldRunner.DETAIL_WINDOW_TICKS)
        }

        assertEquals(ticksFor(3), ticksFor(90), "simulated work grew with the length of the absence")
    }

    @Test
    fun `a short absence is simulated in full with no fold`() = runTest {
        coEvery { worldDao.byId("w1") } returns world()

        val now = epoch + 10 * hour
        val outcome = runner.runSlice("w1", Long.MAX_VALUE, { false }, { now })

        assertEquals(TickOutcome.CAUGHT_UP, outcome)
        // Landing exactly on the owed tick is itself the proof that nothing was
        // folded: a fold jumps the counter forward in one step.
        assertEquals(10L, capturedTick())
        // Asserted as "never written" rather than by inspecting a captured
        // batch, because ten quiet days legitimately produce no events at all
        // and a capture-then-inspect version would fail on an empty slot.
        coVerify(exactly = 0) {
            eventDao.upsertAll(match { batch -> batch.any { it.kind == WorldEngine.KIND_QUIET_INTERVAL } })
        }
    }

    @Test
    fun `nothing is owed when the world is level with its clock`() = runTest {
        coEvery { worldDao.byId("w1") } returns world(currentTick = 10L)
        val outcome = runner.runSlice("w1", Long.MAX_VALUE, { false }, { epoch + 10 * hour })
        assertEquals(TickOutcome.NOTHING_DUE, outcome)
        coVerify(exactly = 0) { worldDao.commitTick(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a paused world is not advanced`() = runTest {
        coEvery { worldDao.byId("w1") } returns world().copy(status = LivingWorldEntity.STATUS_PAUSED)
        val outcome = runner.runSlice("w1", Long.MAX_VALUE, { false }, { epoch + 100 * hour })
        assertEquals(TickOutcome.NOTHING_DUE, outcome)
        coVerify(exactly = 0) { worldDao.commitTick(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `events are written before the state advances`() = runTest {
        coEvery { worldDao.byId("w1") } returns world()
        // Long enough that the catch-up folds and therefore definitely produces
        // at least one event; a handful of quiet days would write none, and the
        // ordering assertion would then be vacuously true.
        val now = epoch + 120 * hour
        runner.runSlice("w1", Long.MAX_VALUE, { false }, { now })

        // Order matters: if the state commit landed first and the process died,
        // the ticks that produced these events would never be re-run and the
        // events would be lost for good.
        coVerify(ordering = io.mockk.Ordering.ORDERED) {
            eventDao.upsertAll(any())
            worldDao.commitTick(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `event ids are derived from the tick so a redone slice replaces rather than duplicates`() = runTest {
        coEvery { worldDao.byId("w1") } returns world()
        val first = slot<List<LivingEventEntity>>()
        coEvery { eventDao.upsertAll(capture(first)) } returns Unit
        val now = epoch + 120 * hour
        runner.runSlice("w1", Long.MAX_VALUE, { false }, { now })
        val firstIds = first.captured.map { it.id }

        // Same world, same seed, same starting tick — a retried slice.
        coEvery { worldDao.byId("w1") } returns world()
        val second = slot<List<LivingEventEntity>>()
        coEvery { eventDao.upsertAll(capture(second)) } returns Unit
        runner.runSlice("w1", Long.MAX_VALUE, { false }, { now })

        assertTrue(firstIds.isNotEmpty(), "no events were produced — the test proves nothing")
        assertEquals(firstIds, second.captured.map { it.id }, "a retried slice produced different event ids")
    }

    @Test
    fun `stopping mid-slice still commits what was already advanced`() = runTest {
        coEvery { worldDao.byId("w1") } returns world()
        var calls = 0
        val outcome = runner.runSlice(
            "w1",
            deadlineMs = Long.MAX_VALUE,
            isStopped = { calls++ > 3 },
            now = { epoch + 40 * hour },
        )
        assertEquals(TickOutcome.PAUSED_FOR_TIME, outcome)
        assertTrue(capturedTick() > 0L, "a stopped slice threw away the ticks it had already computed")
    }
}
