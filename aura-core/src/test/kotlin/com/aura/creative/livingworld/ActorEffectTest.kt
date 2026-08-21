package com.aura.creative.livingworld

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a player move does to a tick.
 *
 * Two of these are the presence verbs the seat was added for — [Effect.MoveTo]
 * and [Effect.Observe] — and the rest pin the seams where an actor's effect
 * meets machinery built for rules: it is journalled, the world can answer it
 * inside the same tick, and a stale one cannot stop the world.
 *
 * The fixture puts your house in the keep and your character on the road, on
 * purpose. Power and presence being in different places is the whole tension
 * the seat exists to create, and a fixture where they coincide would let a
 * broken [Effect.Observe] pass by accident.
 */
class ActorEffectTest {

    private fun world(beliefs: List<Belief> = emptyList(), rules: List<Rule> = emptyList()) = WorldState(
        entities = listOf(
            SimEntity(id = "loc_keep", kind = "location", name = "The Keep"),
            SimEntity(id = "loc_road", kind = "location", name = "The Low Road"),
            // Your eyes are on the road; your house sits in the keep.
            SimEntity(id = "c_you", kind = "character", name = "You", parentId = "loc_road"),
            SimEntity(id = "f_ash", kind = "faction", name = "Ashfall", parentId = "loc_keep"),
            SimEntity(id = "f_bram", kind = "faction", name = "Bramwatch", parentId = "loc_road"),
            SimEntity(id = "f_cor", kind = "faction", name = "Cormere", parentId = "loc_keep"),
        ),
        stocks = listOf(
            Stock("f_ash", "grain", 5_000, capacityMilli = 10_000),
            Stock("f_bram", "grain", 4_000, capacityMilli = 10_000),
            Stock("f_cor", "grain", 4_000, capacityMilli = 10_000),
        ),
        relations = listOf(Relation("f_ash", "f_bram", "grievance", 400)),
        rules = rules,
        beliefs = beliefs,
    )

    private fun ashBelieves(subjectId: String, deviation: Long) =
        Belief(observerId = "f_ash", subjectId = subjectId, key = "grain", deviationMilli = deviation)

    private fun tick(state: WorldState, vararg actions: ActorEffect, at: Long = 1L) =
        WorldEngine.tick(state, "w1", 7L, 0L, at, actions.toList())

    private fun placeOf(state: WorldState, id: String) = state.entities.first { it.id == id }.parentId

    private fun stillWrongAbout(state: WorldState, subjectId: String) =
        state.beliefs.any { it.observerId == "f_ash" && it.subjectId == subjectId && it.deviationMilli != 0L }

    /** Your character looks; your house is what learns. */
    private val look = ActorEffect("c_you", Effect.Observe(forId = "f_ash"))

    // ---- MoveTo ------------------------------------------------------------

    @Test
    fun `moving puts you where you said you were going`() {
        val after = tick(world(), ActorEffect("c_you", Effect.MoveTo("loc_keep"))).state
        assertEquals("loc_keep", placeOf(after, "c_you"))
    }

    @Test
    fun `the tick you spend travelling happens where you set out from`() {
        // Arriving at the end of the tick rather than the start is what makes
        // travel cost something. If the move landed first, leaving would be a
        // way to be already gone, and standing in the wrong place would cost
        // nothing at all.
        val after = tick(
            world(listOf(ashBelieves("f_cor", 2_000))),
            ActorEffect("c_you", Effect.MoveTo("loc_keep")),
            look,
        ).state
        assertEquals("loc_keep", placeOf(after, "c_you"))
        // Cormere is in the keep. You looked before you got there, so you are
        // still wrong about them on arrival.
        assertTrue(stillWrongAbout(after, "f_cor"))
    }

    @Test
    fun `a destination that is not in the world is refused rather than obeyed`() {
        // A bogus parentId would drop the character out of every co-location
        // check at once — unreachable, unobservable, unable to observe. That is
        // a deletion wearing a move's clothes.
        val after = tick(world(), ActorEffect("c_you", Effect.MoveTo("loc_nowhere"))).state
        assertEquals("loc_road", placeOf(after, "c_you"))
    }

    @Test
    fun `arriving is narrated once, and going nowhere is not narrated at all`() {
        val moved = tick(world(), ActorEffect("c_you", Effect.MoveTo("loc_keep")))
        assertEquals(1, moved.events.count { it.kind == WorldEngine.KIND_MOVED })

        val stayed = tick(world(), ActorEffect("c_you", Effect.MoveTo("loc_road")))
        assertEquals(0, stayed.events.count { it.kind == WorldEngine.KIND_MOVED })
    }

    // ---- Observe -----------------------------------------------------------

    @Test
    fun `a house learns what its eyes are standing next to, not what is next to the house`() {
        // Bramwatch shares the road with your character. Cormere shares the
        // keep with your house. Only the first is counted, because looking is
        // something the character does and the character is on the road.
        val after = tick(
            world(listOf(ashBelieves("f_bram", 3_000), ashBelieves("f_cor", 2_000))),
            look,
        ).state
        assertFalse(stillWrongAbout(after, "f_bram"), "you counted it with your own eyes")
        assertTrue(stillWrongAbout(after, "f_cor"), "your house saw through a wall")
    }

    @Test
    fun `somebody else's error survives your looking`() {
        val after = tick(
            world(
                listOf(Belief(observerId = "f_bram", subjectId = "f_ash", key = "grain", deviationMilli = 3_000)),
            ),
            look,
        ).state
        assertTrue(after.beliefs.any { it.observerId == "f_bram" && it.deviationMilli != 0L })
    }

    @Test
    fun `an unplaced looker sees nothing`() {
        val state = world(listOf(ashBelieves("f_bram", 3_000)))
            .let { s -> s.copy(entities = s.entities.map { if (it.id == "c_you") it.copy(parentId = "") else it }) }
        val after = tick(state, look).state
        // Nowhere is not a place called "", and nothing else unplaced is
        // standing next to you.
        assertTrue(stillWrongAbout(after, "f_bram"))
    }

    @Test
    fun `a lie told the same tick you looked still lands on you`() {
        // Observing is a check on yesterday's propaganda, never a shield
        // against today's. If looking beat a lie told in the same tick, the
        // dominant strategy would be to look every tick forever.
        val liar = Rule(
            id = "shout",
            name = "Shout",
            subjectKind = "faction",
            effects = listOf(Effect.SpreadLie("grain", 2_500)),
        )
        val after = tick(world(listOf(ashBelieves("f_bram", 3_000)), listOf(liar)), look).state
        assertTrue(stillWrongAbout(after, "f_bram"), "looking made you immune to being lied to")
    }

    @Test
    fun `looking twice in one tick is looking once`() {
        val result = tick(world(listOf(ashBelieves("f_bram", 3_000))), look, look)
        assertEquals(1, result.events.count { it.kind == WorldEngine.KIND_BELIEF_REVEAL })
    }

    @Test
    fun `a faction standing somewhere can look for itself`() {
        // The blank default. Cormere shares the keep with your house, so a
        // house that looks around learns about its neighbour without any
        // character involved.
        val after = tick(
            world(listOf(ashBelieves("f_cor", 2_000), ashBelieves("f_bram", 3_000))),
            ActorEffect("f_ash", Effect.Observe()),
        ).state
        assertFalse(stillWrongAbout(after, "f_cor"))
        assertTrue(stillWrongAbout(after, "f_bram"), "the keep can see down the road")
    }

    // ---- the seams ---------------------------------------------------------

    @Test
    fun `the world answers what you did inside the tick you did it in`() {
        // The famine rule fires below 2,000. Ashfall starts at 5,000, so it is
        // quiet — until you spend 3,500 in this same tick. If actor effects
        // applied after rule conditions were evaluated, the rule would not fire
        // until next tick and an action would be a suggestion.
        val famine = Rule(
            id = "famine",
            name = "Famine",
            condition = Cond.StockBelow("grain", 2_000),
            effects = listOf(Effect.AdjustRelation("grievance", 500)),
        )
        val quiet = tick(world(rules = listOf(famine)))
        assertEquals(0, quiet.events.count { it.ruleId == "famine" })

        val spent = tick(world(rules = listOf(famine)), ActorEffect("f_ash", Effect.AdjustStock("grain", -3_500)))
        assertTrue(spent.events.any { it.ruleId == "famine" }, "the world did not notice until next tick")
    }

    @Test
    fun `every action is journalled with the effect that produced it`() {
        val result = tick(
            world(),
            ActorEffect("c_you", Effect.MoveTo("loc_keep")),
            ActorEffect("f_ash", Effect.AdjustStock("grain", -100)),
        )
        val journalled = result.events.filter { it.kind == WorldEngine.KIND_PLAYER_ACTION }
        assertEquals(2, journalled.size)
        assertEquals(listOf("c_you", "f_ash"), journalled.map { it.actorId })
        assertEquals(Effect.MoveTo("loc_keep"), journalled[0].payload)
        assertEquals(Effect.AdjustStock("grain", -100), journalled[1].payload)
        // And nothing the engine produced itself carries one, which is what the
        // replayer's decoder relies on to tell them apart.
        assertTrue(result.events.filter { it.kind != WorldEngine.KIND_PLAYER_ACTION }.all { it.payload == null })
    }

    @Test
    fun `an action from somebody who is not there cannot stop the world`() {
        // Actions are submitted into a tick that has not run yet, and an actor
        // can die in between. A world that refused to tick over one stale order
        // would be a world one bad row stops forever.
        val result = tick(
            world(),
            ActorEffect("c_ghost", Effect.MoveTo("loc_keep")),
            ActorEffect("f_ash", Effect.AdjustStock("grain", -500)),
        )
        assertEquals(1, result.events.count { it.kind == WorldEngine.KIND_PLAYER_ACTION })
        assertEquals(4_500L, result.state.stocks.first { it.entityId == "f_ash" && it.key == "grain" }.amountMilli)
    }

    @Test
    fun `acting leaves no rule behind`() {
        // Player moves wear a synthetic rule so they can travel the same path a
        // rule's effect does. If that rule leaked into the persisted rule list
        // it would start accruing cooldowns, and acting would exhaust something
        // invisible.
        val famine = Rule(id = "famine", name = "Famine", cooldownTicks = 5)
        val after = tick(world(rules = listOf(famine)), ActorEffect("f_ash", Effect.AdjustStock("grain", -100))).state
        assertEquals(listOf("famine"), after.rules.map { it.id })
    }
}
