package com.aura.creative.livingworld

import com.aura.creative.WorldBible
import com.aura.creative.WorldCharacter
import com.aura.creative.WorldFaction
import com.aura.creative.WorldLocation
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Seeding is where an authored world bible becomes a runnable one.
 *
 * The case worth guarding is the split of the conserved pool: integer division
 * across three factions loses a remainder, and a pool that starts out not
 * summing to its declared total is not conserved no matter how carefully the
 * engine transfers it afterwards.
 */
class WorldSeederTest {

    private val seeder = WorldSeeder()

    private fun bible(vararg factions: WorldFaction) = WorldBible(factions = factions.toList())

    @Test
    fun `the conserved pool sums to exactly the configured total even when it does not divide evenly`() {
        val setup = WorldSetup(territoryTotalMilli = 10_000L)
        val state = seeder.seed(
            bible(
                WorldFaction(id = "a", name = "Ashfall"),
                WorldFaction(id = "b", name = "Bramwatch"),
                WorldFaction(id = "c", name = "Cormere"),
            ),
            setup,
        )
        val total = state.stocks.filter { it.poolId == WorldSeeder.POOL_TERRITORY }.sumOf { it.amountMilli }
        assertEquals(10_000L, total, "the seeded pool does not sum to its declared total")
    }

    @Test
    fun `the remainder goes to a stable holder rather than wherever iteration lands`() {
        val setup = WorldSetup(territoryTotalMilli = 10_000L)
        val first = seeder.seed(bible(f("a"), f("b"), f("c")), setup)
        val second = seeder.seed(bible(f("c"), f("a"), f("b")), setup)

        fun territory(state: WorldState) = state.stocks
            .filter { it.poolId == WorldSeeder.POOL_TERRITORY }
            .sortedBy { it.entityId }
            .map { it.entityId to it.amountMilli }

        assertEquals(
            territory(first),
            territory(second),
            "the same factions in a different order produced a different opening position",
        )
    }

    private fun f(id: String) = WorldFaction(id = id, name = id.uppercase())

    @Test
    fun `declared rivalries become directed grievance`() {
        val state = seeder.seed(
            bible(
                WorldFaction(id = "a", name = "Ashfall", rivals = listOf("Bramwatch")),
                WorldFaction(id = "b", name = "Bramwatch"),
            ),
        )
        val grievances = state.relations.filter { it.kind == WorldSeeder.REL_GRIEVANCE }
        assertEquals(1, grievances.size, "rivalry is directed — only the faction that declared it holds the grudge")
        assertEquals("a", grievances.first().fromId)
        assertEquals("b", grievances.first().toId)
    }

    @Test
    fun `a rival that names nobody in the bible is skipped rather than invented`() {
        val state = seeder.seed(
            bible(
                WorldFaction(id = "a", name = "Ashfall", rivals = listOf("The Unwritten")),
                WorldFaction(id = "b", name = "Bramwatch"),
            ),
        )
        assertTrue(state.relations.isEmpty(), "an unmatched rival name conjured a relation")
        assertEquals(2, state.entities.count { it.kind == WorldSeeder.KIND_FACTION })
    }

    @Test
    fun `entity ids are the bible's own ids so a re-seed can merge instead of wiping`() {
        val state = seeder.seed(
            WorldBible(
                factions = listOf(WorldFaction(id = "faction-1", name = "Ashfall"), WorldFaction(id = "faction-2", name = "Bram")),
                locations = listOf(WorldLocation(id = "loc-1", name = "The Reach")),
                characters = listOf(WorldCharacter(id = "char-1", name = "Eda")),
            ),
        )
        assertEquals(
            listOf("char-1", "faction-1", "faction-2", "loc-1"),
            state.entities.map { it.id }.sorted(),
        )
        assertTrue(state.entities.all { it.sourceBibleId == it.id })
    }

    @Test
    fun `a world with fewer than two factions cannot be seeded`() {
        assertFalse(seeder.canSeed(bible(f("a"))))
        assertFalse(seeder.canSeed(WorldBible()))
        assertTrue(seeder.canSeed(bible(f("a"), f("b"))))
    }

    @Test
    fun `a seeded world actually moves when it is ticked`() {
        // A seeder that produces a valid but inert world would pass every
        // structural assertion above and still ship a world where nothing ever
        // happens, which is the failure mode that matters.
        var state = seeder.seed(bible(f("a"), f("b"), f("c")))
        val events = mutableListOf<WorldEvent>()
        for (tick in 1..400L) {
            val result = WorldEngine.tick(state, "w1", 7L, 0L, tick)
            state = result.state
            events += result.events
        }
        assertTrue(events.isNotEmpty(), "a seeded world produced no events in 400 days")
        assertTrue(
            events.any { it.kind == WorldEngine.KIND_CLAIM_WON },
            "no faction ever took land — the default rules never contend, so nothing is at stake",
        )
    }

    // ---- geography ---------------------------------------------------------

    private fun peopled() = WorldBible(
        factions = listOf(
            WorldFaction(id = "f_a", name = "Ashfall", members = listOf("c_1")),
            WorldFaction(id = "f_b", name = "Bramwatch", members = listOf("c_2")),
        ),
        locations = listOf(
            WorldLocation(id = "l_1", name = "The Keep"),
            WorldLocation(id = "l_2", name = "The Low Road"),
        ),
        characters = listOf(
            WorldCharacter(id = "c_1", name = "Alder"),
            WorldCharacter(id = "c_2", name = "Bryn"),
            WorldCharacter(id = "c_3", name = "Corr"),
        ),
    )

    private fun placeOf(state: WorldState, id: String) = state.entities.first { it.id == id }.parentId

    @Test
    fun `houses are dealt around the map rather than piled in one hall`() {
        // A bible names places but never says who is where. If the seeder left
        // that blank, every co-location check in the game would fail at once —
        // looking around would always find an empty room and presence would
        // stop meaning anything.
        val state = seeder.seed(peopled())
        assertEquals("l_1", placeOf(state, "f_a"))
        assertEquals("l_2", placeOf(state, "f_b"))
    }

    @Test
    fun `a character stands with the house that claims them`() {
        val state = seeder.seed(peopled())
        assertEquals(placeOf(state, "f_a"), placeOf(state, "c_1"))
        assertEquals(placeOf(state, "f_b"), placeOf(state, "c_2"))
        // Unaffiliated, so spread rather than piled — a seat taken at random
        // still starts somewhere with something to look at.
        assertTrue(placeOf(state, "c_3").isNotBlank())
    }

    @Test
    fun `placement does not depend on the order the bible happens to list things`() {
        val forward = seeder.seed(peopled())
        val shuffled = peopled().let { b ->
            b.copy(
                factions = b.factions.reversed(),
                locations = b.locations.reversed(),
                characters = b.characters.reversed(),
            )
        }
        val other = seeder.seed(shuffled)
        for (id in listOf("f_a", "f_b", "c_1", "c_2", "c_3")) {
            assertEquals(placeOf(forward, id), placeOf(other, id), "$id moved when the bible was reordered")
        }
    }

    @Test
    fun `a world with nowhere to stand leaves everyone unplaced`() {
        // The honest answer rather than a fabricated location. Blank is already
        // a valid state everywhere downstream: an unplaced looker sees nothing.
        val state = seeder.seed(peopled().copy(locations = emptyList()))
        assertEquals("", placeOf(state, "f_a"))
        assertEquals("", placeOf(state, "c_1"))
    }

}
