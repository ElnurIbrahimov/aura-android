package com.aura.creative.livingworld

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** One line per living faction, whole units, top resentment named. Pure. */
class WorldStateBriefTest {

    @Test
    fun `renders standings in whole units with the hottest grudge named`() {
        val state = WorldState(
            entities = listOf(
                SimEntity(id = "f_a", kind = "faction", name = "Ashfall"),
                SimEntity(id = "f_b", kind = "faction", name = "Bramwatch"),
            ),
            stocks = listOf(
                Stock("f_a", "territory", 4_000, poolId = "territory", scarcity = Stock.SCARCITY_CONSERVED),
                Stock("f_a", "grain", 5_500, capacityMilli = 10_000),
                Stock("f_b", "territory", 3_000, poolId = "territory", scarcity = Stock.SCARCITY_CONSERVED),
            ),
            relations = listOf(
                Relation("f_a", "f_b", "grievance", 600),
            ),
        )

        val brief = WorldStateBrief.render(state)

        assertTrue(brief.contains("- Ashfall: "), brief)
        assertTrue(brief.contains("territory 4"), "amounts must render in whole units")
        assertTrue(brief.contains("resents Bramwatch"), brief)
        assertTrue(!brief.contains("resents Ashfall"), "Bramwatch holds no grudge")
    }

    @Test
    fun `an empty world renders nothing at all`() {
        assertEquals("", WorldStateBrief.render(WorldState()))
    }
}
