package com.aura.creative.livingworld

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fog's contract.
 *
 * [PlayerView] is the difference between a spectator surface and a seat. The
 * living-world section is omniscient — it reads `WorldState` and renders truth —
 * which is correct for watching and fatal for playing. These tests pin the
 * properties the game rests on: a deviation moves what you see by exactly its
 * size, your own side is never fogged, and — the one that matters — a view built
 * over a lie is **indistinguishable** from a view of a world where that lie
 * happens to be true.
 *
 * That last one is the whole feature. If a player can tell from the interface
 * that they are being deceived, they are not deceived.
 *
 * Deliberately pure JVM: no Room, no Android, no model.
 */
class PlayerViewTest {

    private fun might(entityId: String, amount: Long) =
        Stock(entityId = entityId, key = "might", amountMilli = amount, scarcity = Stock.SCARCITY_ABSTRACT)

    private fun grain(entityId: String, amount: Long) =
        Stock(entityId = entityId, key = "grain", amountMilli = amount)

    /**
     * One keep, two rival houses, and a character who leads Ashfall.
     *
     * Bramwatch stands in the keep with the player; Cormere is elsewhere. That
     * split is what [PlayerView.SeenEntity.canObserve] is for.
     */
    private fun fixture(beliefs: List<Belief> = emptyList()): WorldState = WorldState(
        entities = listOf(
            SimEntity(id = "loc_keep", kind = "location", name = "The Keep"),
            SimEntity(id = "loc_road", kind = "location", name = "The Low Road"),
            SimEntity(id = "c_you", kind = "character", name = "You", parentId = "loc_keep"),
            SimEntity(id = "f_ash", kind = "faction", name = "Ashfall"),
            SimEntity(id = "f_bram", kind = "faction", name = "Bramwatch", parentId = "loc_keep"),
            SimEntity(id = "f_cor", kind = "faction", name = "Cormere", parentId = "loc_road"),
        ),
        stocks = listOf(
            might("f_ash", 3_000), grain("f_ash", 500),
            might("f_bram", 1_000),
            might("f_cor", 2_000),
        ),
        relations = listOf(
            Relation("f_ash", "f_bram", "grievance", 500),
            Relation("f_bram", "f_cor", "grievance", 400),
        ),
        beliefs = beliefs,
    )

    private fun view(state: WorldState) = PlayerView.of(state, observerId = "c_you", factionId = "f_ash")

    private fun mightSeenOf(v: PlayerView.View, id: String): Long =
        v.others.first { it.id == id }.stocks.first { it.key == "might" }.amountMilli

    @Test
    fun `with no beliefs the view is the truth`() {
        val v = view(fixture())
        assertEquals(1_000L, mightSeenOf(v, "f_bram"))
        assertEquals(2_000L, mightSeenOf(v, "f_cor"))
    }

    @Test
    fun `a deviation moves what you see by exactly its size`() {
        val v = view(
            fixture(
                listOf(
                    Belief(
                        observerId = "f_ash",
                        subjectId = "f_bram",
                        key = "might",
                        deviationMilli = 4_000,
                        provenance = Belief.PROVENANCE_LIED_TO,
                        sourceId = "f_bram",
                    ),
                ),
            ),
        )
        // Bramwatch holds 1,000 and you think they hold 5,000. This is the
        // number ClaimPool would weigh a contested draw with.
        assertEquals(5_000L, mightSeenOf(v, "f_bram"))
        assertEquals(2_000L, mightSeenOf(v, "f_cor"))
    }

    @Test
    fun `beliefs belong to your house, not to you`() {
        // Only factions hold beliefs in this engine — SpreadLie plants
        // deviations in "every other faction's" table and nothing ever writes
        // one for a character. A view that read the character's table would be
        // reading a table that is always empty, and would render a player who
        // can never be wrong about anything.
        val keyedToYou = view(
            fixture(
                listOf(
                    Belief(observerId = "c_you", subjectId = "f_bram", key = "might", deviationMilli = 4_000),
                ),
            ),
        )
        assertEquals(1_000L, mightSeenOf(keyedToYou, "f_bram"))

        val keyedToYourHouse = view(
            fixture(
                listOf(
                    Belief(observerId = "f_ash", subjectId = "f_bram", key = "might", deviationMilli = 4_000),
                ),
            ),
        )
        assertEquals(5_000L, mightSeenOf(keyedToYourHouse, "f_bram"))
    }

    @Test
    fun `somebody else's error is not yours`() {
        val v = view(
            fixture(
                listOf(
                    Belief(observerId = "f_cor", subjectId = "f_bram", key = "might", deviationMilli = 9_000),
                ),
            ),
        )
        assertEquals(1_000L, mightSeenOf(v, "f_bram"))
    }

    @Test
    fun `your own side reads true even when the table says otherwise`() {
        // A world that tried to fog the player about themselves. You know your
        // own strength; being deceived about yourself is a different feature.
        val v = view(
            fixture(
                listOf(
                    Belief(observerId = "f_ash", subjectId = "f_ash", key = "might", deviationMilli = -2_500),
                ),
            ),
        )
        val ownMight = v.faction!!.stocks.first { it.key == "might" }.amountMilli
        assertEquals(3_000L, ownMight)
    }

    @Test
    fun `a lie is indistinguishable from a world where the lie is true`() {
        // The thesis. Bramwatch really holds 1,000 and has convinced you of
        // 5,000; a second world where they genuinely hold 5,000 and nobody has
        // lied must render identically. Any field that told these apart —
        // provenance, deviation size, a staleness marker, an `isBelieved` flag —
        // would hand the player the one fact the fog exists to withhold.
        val deceived = view(
            fixture(
                listOf(
                    Belief(
                        observerId = "f_ash",
                        subjectId = "f_bram",
                        key = "might",
                        deviationMilli = 4_000,
                        provenance = Belief.PROVENANCE_LIED_TO,
                        sourceId = "f_bram",
                        sinceTick = 12,
                    ),
                ),
            ),
        )

        val honest = view(
            WorldState(
                entities = fixture().entities,
                stocks = listOf(
                    might("f_ash", 3_000), grain("f_ash", 500),
                    might("f_bram", 5_000),
                    might("f_cor", 2_000),
                ),
                relations = fixture().relations,
                beliefs = emptyList(),
            ),
        )

        assertEquals(honest, deceived, "the interface can tell a lie from the truth")
    }

    @Test
    fun `stale and lied-to look the same from the inside`() {
        fun withProvenance(p: String, since: Long) = view(
            fixture(
                listOf(
                    Belief(
                        observerId = "f_ash",
                        subjectId = "f_cor",
                        key = "might",
                        deviationMilli = 750,
                        provenance = p,
                        sourceId = if (p == Belief.PROVENANCE_LIED_TO) "f_bram" else "",
                        sinceTick = since,
                    ),
                ),
            ),
        )
        assertEquals(
            withProvenance(Belief.PROVENANCE_STALE, 3),
            withProvenance(Belief.PROVENANCE_LIED_TO, 40),
            "how you came to be wrong reaches the player",
        )
    }

    @Test
    fun `co-location is reported and is about geography, not information`() {
        val v = view(
            fixture(
                listOf(
                    Belief(observerId = "f_ash", subjectId = "f_bram", key = "might", deviationMilli = 4_000),
                ),
            ),
        )
        // Standing next to Bramwatch makes Observe legal. It does not make your
        // information about them good, and it must not say whether it is.
        assertTrue(v.others.first { it.id == "f_bram" }.canObserve)
        assertFalse(v.others.first { it.id == "f_cor" }.canObserve)
        assertEquals(5_000L, mightSeenOf(v, "f_bram"))
    }

    @Test
    fun `an unplaced character can observe nothing`() {
        val state = fixture().let { s ->
            s.copy(entities = s.entities.map { if (it.id == "c_you") it.copy(parentId = "") else it })
        }
        val v = view(state)
        assertEquals("", v.locationId)
        assertTrue(v.others.none { it.canObserve })
    }

    @Test
    fun `the dead are not in the view`() {
        val state = fixture().let { s ->
            s.copy(entities = s.entities.map { if (it.id == "f_cor") it.copy(diedAtTick = 9) else it })
        }
        assertTrue(view(state).others.none { it.id == "f_cor" })
    }

    @Test
    fun `only your own ties are visible`() {
        val v = view(fixture())
        assertTrue(v.relations.all { it.fromId == "f_ash" || it.fromId == "c_you" })
        assertTrue(v.relations.none { it.fromId == "f_bram" })
    }

    @Test
    fun `a seat that was never assigned yields no self rather than the truth`() {
        val v = PlayerView.of(fixture(), observerId = "c_nobody", factionId = "f_nobody")
        assertNull(v.self)
        assertNull(v.faction)
        // And crucially it does not fall back to showing everything as true —
        // the entities are still there, still fogged by an empty belief set.
        assertTrue(v.others.isNotEmpty())
    }
}
