package com.aura.creative.livingworld

import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A fork is a ref, not a dice roll.
 *
 * The salt pins that: derived, not drawn, so recreating the same-named fork of
 * the same moment yields the same world — and different from the parent's, so
 * the futures genuinely diverge while the shared prefix stays shared.
 */
class WorldForkTest {

    private fun parent(tick: Long = 40L) = LivingWorldEntity(
        id = "w-parent", projectId = "p1", branchId = "b-main", rootSeed = 99L,
        branchSalt = 0L, worldEpochMs = 0L, currentTick = tick,
        stateJson = "STATE", genesisJson = "GENESIS", createdAt = 0L, updatedAt = 0L,
    )

    @Test
    fun `the same fork of the same moment derives the same salt`() {
        val a = LivingWorldStore.deriveBranchSalt(parent(), 40L, "what-if-she-knew")
        val b = LivingWorldStore.deriveBranchSalt(parent(), 40L, "what-if-she-knew")
        assertEquals(a, b, "a fork must be reproducible")
    }

    @Test
    fun `a fork's salt never equals its parent's`() {
        for (name in listOf("a", "b", "what-if", "the-long-road", "x".repeat(60))) {
            for (tick in listOf(0L, 1L, 40L, 9_999L)) {
                assertTrue(
                    LivingWorldStore.deriveBranchSalt(parent(tick), tick, name) != 0L,
                    "fork '$name' at $tick landed on the parent's salt",
                )
            }
        }
    }

    @Test
    fun `fork copies the moment and keeps the identity`() = runBlocking {
        val worldDao = mockk<LivingWorldDao>(relaxed = true)
        val eventDao = mockk<LivingEventDao>(relaxed = true)
        val forked = slot<LivingWorldEntity>()
        coEvery { worldDao.upsert(capture(forked)) } returns Unit

        LivingWorldStore(worldDao, eventDao).fork(parent(), branchId = "b-fork", branchName = "what-if")

        val child = forked.captured
        assertEquals(99L, child.rootSeed, "the pre-fork past is shared identity")
        assertEquals("w-parent", child.parentWorldId)
        assertEquals(40L, child.forkedAtTick)
        assertEquals(40L, child.currentTick)
        assertEquals(0L, child.worldEpochMs, "tick N is due at the same wall time on every branch")
        assertEquals("STATE", child.stateJson)
        assertEquals("GENESIS", child.genesisJson)
        assertTrue(child.branchSalt != parent().branchSalt)
    }

    @Test
    fun `siblings share their prefix and then genuinely diverge`() {
        val fixture = WorldState(
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
        // Shared prefix: the parent's first forty ticks.
        var shared = fixture
        for (tick in 1..40L) shared = WorldEngine.tick(shared, "w", 99L, 0L, tick).state

        val childSalt = LivingWorldStore.deriveBranchSalt(parent(40L), 40L, "what-if")
        fun history(salt: Long): List<String> {
            var state = shared
            val events = mutableListOf<String>()
            for (tick in 41..120L) {
                val result = WorldEngine.tick(state, "w", 99L, salt, tick)
                state = result.state
                events += result.events.map { "${it.tick}:${it.kind}:${it.actorId}:${it.targetId}" }
            }
            return events
        }

        val parentFuture = history(0L)
        val childFuture = history(childSalt)
        val childAgain = history(childSalt)

        assertEquals(childFuture, childAgain, "a branch must replay itself exactly")
        assertTrue(parentFuture != childFuture, "different salts must produce different futures")
    }
}
