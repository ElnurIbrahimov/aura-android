package com.aura.creative.livingworld

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Where two timelines part ways — and a fork's inherited past, page-merged. */
class TimelineDiffTest {

    private fun event(
        tick: Long,
        seq: Int = 0,
        kind: String = WorldEngine.KIND_STOCK_SHIFT,
        actor: String = "f_a",
        magnitude: Long = 100L,
        worldId: String = "w1",
    ) = LivingEventEntity(
        id = "$worldId#$tick.$seq",
        worldId = worldId,
        branchId = "b1",
        tickIndex = tick,
        seq = seq,
        kind = kind,
        actorId = actor,
        magnitudeMilli = magnitude,
        summary = "s",
    )

    @Test
    fun `identical streams have no divergence`() {
        val a = listOf(event(1), event(2), event(3))
        val b = listOf(event(1), event(2), event(3))
        assertNull(TimelineDiff.firstDivergence(a, b))
    }

    @Test
    fun `the first field-wise mismatch is the divergence`() {
        val a = listOf(event(1), event(2, magnitude = 100), event(3))
        val b = listOf(event(1), event(2, magnitude = 900), event(3))
        val divergence = TimelineDiff.firstDivergence(a, b)
        assertEquals(2L, divergence?.a?.tickIndex)
        assertEquals(900L, divergence?.b?.magnitudeMilli)
    }

    @Test
    fun `one branch simply having more is a divergence with an empty side`() {
        val a = listOf(event(1), event(2))
        val b = listOf(event(1))
        val divergence = TimelineDiff.firstDivergence(a, b)
        assertEquals(2L, divergence?.a?.tickIndex)
        assertNull(divergence?.b)
    }

    @Test
    fun `standings deltas name the faction and move in whole units`() {
        val before = WorldState(
            entities = listOf(SimEntity(id = "f_a", kind = "faction", name = "Ashfall")),
            stocks = listOf(Stock("f_a", "territory", 4_000, poolId = "territory")),
        )
        val after = WorldState(
            entities = listOf(SimEntity(id = "f_a", kind = "faction", name = "Ashfall")),
            stocks = listOf(Stock("f_a", "territory", 3_000, poolId = "territory")),
        )
        assertEquals(listOf("Ashfall: territory 4 -> 3"), TimelineDiff.standingsDiff(before, after))
        assertTrue(TimelineDiff.standingsDiff(before, before).isEmpty(), "no change, no lines")
    }

    @Test
    fun `a fork's deep timeline inherits only the parent's rows at or before the boundary`() = runBlocking {
        val worldDao = mockk<LivingWorldDao>(relaxed = true)
        val eventDao = mockk<LivingEventDao>(relaxed = true)
        val child = LivingWorldEntity(
            id = "w-child", projectId = "p1", branchId = "b-fork", rootSeed = 7L,
            branchSalt = 99L, parentWorldId = "w-parent", forkedAtTick = 40L,
            worldEpochMs = 0L, currentTick = 45L, stateJson = "{}", createdAt = 0L, updatedAt = 0L,
        )
        every { eventDao.observeRecent("w-child", any()) } returns flowOf(
            listOf(event(45, worldId = "w-child"), event(42, worldId = "w-child")),
        )
        coEvery { worldDao.byId("w-parent") } returns LivingWorldEntity(
            id = "w-parent", projectId = "p1", branchId = "b-main", rootSeed = 7L,
            worldEpochMs = 0L, currentTick = 60L, stateJson = "{}", createdAt = 0L, updatedAt = 0L,
        )
        coEvery { eventDao.recentUpTo("w-parent", 40L, any()) } returns
            listOf(event(40, worldId = "w-parent"), event(12, worldId = "w-parent"))

        val merged = LivingWorldStore(worldDao, eventDao).observeEventsDeep(child, limit = 50).first()

        assertEquals(listOf("w-child", "w-child", "w-parent", "w-parent"), merged.map { it.worldId })
        assertTrue(
            merged.drop(2).all { it.tickIndex <= 40L },
            "an ancestor's post-fork rows are the parent's own future, not the child's past",
        )
    }
}
