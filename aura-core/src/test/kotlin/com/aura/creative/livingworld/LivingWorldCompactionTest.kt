package com.aura.creative.livingworld

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Compaction's contract: shed only what nobody will miss.
 *
 * The noise trim deletes sub-floor, never-narrated rows older than the
 * horizon — the notable spine, paid narration and every quiet_interval
 * survive, because replay-based forking walks them. The hard cap is an
 * emergency valve for a runaway world, not policy.
 */
class LivingWorldCompactionTest {

    private fun world(id: String, tick: Long) = LivingWorldEntity(
        id = id, projectId = "p1", branchId = "b1", rootSeed = 1L,
        worldEpochMs = 0L, currentTick = tick, stateJson = "{}",
        createdAt = 0L, updatedAt = 0L,
    )

    @Test
    fun `the noise trim runs against the thirty-day horizon at the default floor`() = runBlocking {
        val worldDao = mockk<LivingWorldDao>(relaxed = true)
        val eventDao = mockk<LivingEventDao>(relaxed = true)
        coEvery { worldDao.all() } returns listOf(world("w1", tick = 2_000L))
        coEvery { eventDao.count("w1") } returns 500

        LivingWorldStore(worldDao, eventDao).compactAll()

        coVerify(exactly = 1) {
            eventDao.trimNoiseBefore(
                "w1",
                2_000L - LivingWorldStore.COMPACTION_HORIZON_TICKS,
                NotabilityScorer.DEFAULT_FLOOR,
            )
        }
        coVerify(exactly = 0) { eventDao.trimBefore(any(), any()) }
    }

    @Test
    fun `a world younger than the horizon is not trimmed at all`() = runBlocking {
        val worldDao = mockk<LivingWorldDao>(relaxed = true)
        val eventDao = mockk<LivingEventDao>(relaxed = true)
        coEvery { worldDao.all() } returns listOf(world("w1", tick = 100L))
        coEvery { eventDao.count("w1") } returns 500

        LivingWorldStore(worldDao, eventDao).compactAll()

        coVerify(exactly = 0) { eventDao.trimNoiseBefore(any(), any(), any()) }
    }

    @Test
    fun `the hard cap opens only past the row bound and cuts at the overflow tick`() = runBlocking {
        val worldDao = mockk<LivingWorldDao>(relaxed = true)
        val eventDao = mockk<LivingEventDao>(relaxed = true)
        coEvery { worldDao.all() } returns listOf(world("w1", tick = 30_000L))
        coEvery { eventDao.count("w1") } returns LivingWorldStore.HARD_CAP_ROWS + 1_000
        coEvery { eventDao.tickAtOffset("w1", LivingWorldStore.HARD_CAP_ROWS) } returns 4_321L

        LivingWorldStore(worldDao, eventDao).compactAll()

        coVerify(exactly = 1) { eventDao.trimBefore("w1", 4_321L) }
    }

    @Test
    fun `a bounded world never sees the emergency valve`() = runBlocking {
        val worldDao = mockk<LivingWorldDao>(relaxed = true)
        val eventDao = mockk<LivingEventDao>(relaxed = true)
        coEvery { worldDao.all() } returns listOf(world("w1", tick = 30_000L))
        coEvery { eventDao.count("w1") } returns LivingWorldStore.HARD_CAP_ROWS - 1

        LivingWorldStore(worldDao, eventDao).compactAll()

        coVerify(exactly = 0) { eventDao.trimBefore(any(), any()) }
    }
}
