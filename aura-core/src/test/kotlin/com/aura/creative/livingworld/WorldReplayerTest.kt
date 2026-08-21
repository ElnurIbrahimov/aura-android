package com.aura.creative.livingworld

import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Replay's honesty pins.
 *
 * The replayer promises to reproduce history *as it was computed* — fold spans
 * included — from genesis. If these fail, fork-at-past would quietly produce a
 * world that never happened.
 */
class WorldReplayerTest {

    private val json = Json { prettyPrint = false; encodeDefaults = true }

    private fun hash(state: WorldState): String =
        json.encodeToString(WorldState.serializer(), state.canonical())

    private fun genesis(): WorldState = WorldState(
        entities = listOf(
            SimEntity(id = "f_a", kind = "faction", name = "A"),
            SimEntity(id = "f_b", kind = "faction", name = "B"),
            SimEntity(id = "f_c", kind = "faction", name = "C"),
        ),
        stocks = listOf(
            Stock("f_a", "grain", 5_000, capacityMilli = 10_000, flowPerTickMilli = -60),
            Stock("f_b", "grain", 5_000, capacityMilli = 10_000, flowPerTickMilli = -60),
            Stock("f_c", "grain", 5_000, capacityMilli = 10_000, flowPerTickMilli = -60),
            Stock("f_a", "territory", 4_000, poolId = "territory", scarcity = Stock.SCARCITY_CONSERVED),
            Stock("f_b", "territory", 3_000, poolId = "territory", scarcity = Stock.SCARCITY_CONSERVED),
            Stock("f_c", "territory", 3_000, poolId = "territory", scarcity = Stock.SCARCITY_CONSERVED),
        ),
        rules = listOf(
            Rule(
                id = "famine",
                name = "Famine",
                condition = Cond.StockBelow("grain", 2_000),
                effects = listOf(
                    Effect.ClaimPool("territory", "territory", 200),
                    Effect.AdjustStock("grain", 500),
                ),
                cooldownTicks = 5,
            ),
        ),
    )

    /** The runner's road: detailed to 30, one fold to 80, detailed to 100. */
    private fun runnerHistory(): WorldState {
        var state = genesis()
        for (tick in 1..30L) state = WorldEngine.tick(state, "w1", 7L, 0L, tick).state
        state = WorldEngine.fold(state, 50L, atTick = 80L).state
        for (tick in 81..100L) state = WorldEngine.tick(state, "w1", 7L, 0L, tick).state
        return state
    }

    private fun segments(through: Long) =
        listOf(WorldReplayer.Segment("w1", 7L, 0L, 0L, through))

    private val folds = listOf(WorldReplayer.FoldSpan(atTick = 80L, ticks = 50L))

    @Test
    fun `replaying a recorded fold span reproduces the straight run exactly`() {
        val straight = runnerHistory()
        val replayed = WorldReplayer.stateAt(genesis(), segments(100L), folds, emptyList(), 100L)
        assertEquals(hash(straight), hash(replayed), "the replay took a different road than the runner")
    }

    @Test
    fun `a target inside a folded span is refused, not approximated`() {
        assertFailsWith<IllegalArgumentException> {
            WorldReplayer.stateAt(genesis(), segments(60L), folds, emptyList(), 60L)
        }
    }

    @Test
    fun `a fork-of-fork switches salts at its recorded boundary`() {
        // One chain: root salt 0 through tick 20, child salt 999 after.
        val chained = listOf(
            WorldReplayer.Segment("w-root", 7L, 0L, 0L, 20L),
            WorldReplayer.Segment("w-child", 7L, 999L, 20L, 40L),
        )
        val replayed = WorldReplayer.stateAt(genesis(), chained, emptyList(), emptyList(), 40L)

        var manual = genesis()
        for (tick in 1..20L) manual = WorldEngine.tick(manual, "w-root", 7L, 0L, tick).state
        for (tick in 21..40L) manual = WorldEngine.tick(manual, "w-child", 7L, 999L, tick).state

        assertEquals(hash(manual), hash(replayed), "the salt switch did not land on the boundary")
    }
}
