package com.aura.creative.livingworld

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The thesis test, and the six verbs around it.
 *
 * The plan this came from put it plainly: *a claim that is illegal against
 * truth but legal against belief is offered to the player, and resolves against
 * truth. If that test cannot be written, the fog is not real.* It is
 * [`a move sized against a lie is offered, and resolves against the truth`].
 *
 * The rest guard the same rule from the other side — that nothing in the move
 * list, not a label, not a size, not a greyed-out reason, differs between a
 * world you have been lied about and a world where the lie happens to be true.
 */
class PlayerMovesTest {

    private fun might(entityId: String, amount: Long) =
        Stock(entityId = entityId, key = "might", amountMilli = amount, scarcity = Stock.SCARCITY_ABSTRACT)

    private fun territory(entityId: String, amount: Long) = Stock(
        entityId = entityId,
        key = "territory",
        amountMilli = amount,
        poolId = "territory",
        scarcity = Stock.SCARCITY_CONSERVED,
    )

    /**
     * You lead Ashfall from the keep, where Bramwatch also stands. Cormere is
     * out on the road. Bramwatch truly holds 1,000 of the territory pool.
     */
    private fun fixture(
        bramTerritory: Long = 1_000,
        beliefs: List<Belief> = emptyList(),
        youAt: String = "loc_keep",
    ) = WorldState(
        entities = listOf(
            SimEntity(id = "loc_keep", kind = "location", name = "The Keep"),
            SimEntity(id = "loc_road", kind = "location", name = "The Low Road"),
            SimEntity(id = "c_you", kind = "character", name = "You", parentId = youAt),
            SimEntity(id = "f_ash", kind = "faction", name = "Ashfall", parentId = "loc_keep"),
            SimEntity(id = "f_bram", kind = "faction", name = "Bramwatch", parentId = "loc_keep"),
            SimEntity(id = "f_cor", kind = "faction", name = "Cormere", parentId = "loc_road"),
        ),
        stocks = listOf(
            might("f_ash", 3_000),
            Stock("f_ash", "grain", 2_000, capacityMilli = 10_000),
            territory("f_ash", 500),
            territory("f_bram", bramTerritory),
            territory("f_cor", 400),
        ),
        relations = listOf(Relation("f_ash", "f_bram", "grievance", 500)),
        beliefs = beliefs,
    )

    private fun movesFor(state: WorldState) =
        PlayerMoves.available(PlayerView.of(state, observerId = "c_you", factionId = "f_ash"))

    private fun verb(state: WorldState, verb: String) = movesFor(state).filter { it.verb == verb }

    private fun inflatedBelief(deviation: Long) = listOf(
        Belief(
            observerId = "f_ash",
            subjectId = "f_bram",
            key = "territory",
            deviationMilli = deviation,
            provenance = Belief.PROVENANCE_LIED_TO,
            sourceId = "f_bram",
            sinceTick = 12,
        ),
    )

    // ---- the thesis --------------------------------------------------------

    @Test
    fun `a move sized against a lie is offered, and resolves against the truth`() {
        // Bramwatch holds 1,000 and has convinced you of 5,000.
        val deceived = fixture(beliefs = inflatedBelief(4_000))
        val claim = verb(deceived, PlayerMoves.CLAIM).single()

        // The claim is offered, and it is sized off the lie: a quarter of
        // 5,000, not a quarter of 1,000. Nothing greyed it out, because
        // greying it out would have told you the pool is emptier than you
        // think — which is the one thing the fog exists to withhold.
        assertTrue(claim.legal)
        assertEquals(
            Effect.ClaimPool("territory", "territory", 1_250),
            claim.effect?.effect,
            "the move was sized against ground truth",
        )

        // And then it resolves against truth. The pool is conserved, so what
        // Ashfall gains is bounded by what Bramwatch actually had.
        val after = WorldEngine.tick(deceived, "w1", 7L, 0L, 1L, listOf(requireNotNull(claim.effect))).state
        val ash = after.stocks.first { it.entityId == "f_ash" && it.key == "territory" }.amountMilli
        val bram = after.stocks.first { it.entityId == "f_bram" && it.key == "territory" }.amountMilli
        assertTrue(ash > 500L, "nothing moved at all")
        assertTrue(ash <= 1_500L, "the world paid out against a number that only existed in your head")
        assertEquals(1_900L, ash + bram + after.stocks.first { it.entityId == "f_cor" }.amountMilli)
    }

    @Test
    fun `the move list cannot tell a lie from a world where the lie is true`() {
        // The same argument PlayerViewTest makes about the view, made about
        // the buttons. A label, a size, or a greyed-out reason that differed
        // here would leak exactly what the projection refuses to.
        val deceived = movesFor(fixture(bramTerritory = 1_000, beliefs = inflatedBelief(4_000)))
        val honest = movesFor(fixture(bramTerritory = 5_000))
        assertEquals(honest, deceived, "the move list can tell a lie from the truth")
    }

    @Test
    fun `legality is computed from the view alone`() {
        // Structural, not behavioural: available() takes a View and nothing
        // else, so it cannot consult WorldState even by accident. This test
        // exists so that adding a WorldState parameter breaks something loudly
        // rather than quietly ending the fog.
        val signature = PlayerMoves::class.java.methods.first { it.name == "available" }
        assertEquals(listOf(PlayerView.View::class.java), signature.parameterTypes.toList())
    }

    // ---- the six verbs -----------------------------------------------------

    @Test
    fun `all six verbs are offered`() {
        val verbs = movesFor(fixture()).map { it.verb }.distinct().sorted()
        assertEquals(
            listOf(
                PlayerMoves.BLUFF, PlayerMoves.CLAIM, PlayerMoves.LOOK,
                PlayerMoves.MEND, PlayerMoves.SPEND, PlayerMoves.TRAVEL,
            ).sorted(),
            verbs,
        )
    }

    @Test
    fun `an illegal move is offered with a reason rather than hidden`() {
        // A verb that vanishes teaches nothing. "There is nobody here but you"
        // is a fact the player is entitled to, and one they can act on by
        // travelling.
        // Cormere is out on the road, so riding there and looking is legal.
        val onTheRoad = fixture(youAt = "loc_road")
        assertTrue(verb(onTheRoad, PlayerMoves.LOOK).single().legal)

        // Call Cormere home and the same spot has nobody in it. The verb stays
        // on the list, and the gate is geography and nothing else.
        val emptyRoad = onTheRoad.copy(
            entities = onTheRoad.entities.map { if (it.id == "f_cor") it.copy(parentId = "loc_keep") else it },
        )
        val look = verb(emptyRoad, PlayerMoves.LOOK).single()
        assertFalse(look.legal)
        assertNull(look.effect)
        assertEquals("There is nobody here but you.", look.blockedBy)
    }

    @Test
    fun `looking is your character's move and claiming is your house's`() {
        val moves = movesFor(fixture())
        assertEquals("c_you", verb(fixture(), PlayerMoves.LOOK).single().effect?.actorId ?: "c_you")
        assertTrue(
            moves.filter { it.verb == PlayerMoves.TRAVEL }.all { it.effect?.actorId == "c_you" },
            "your house went for a ride",
        )
        assertTrue(
            moves.filter { it.verb in setOf(PlayerMoves.CLAIM, PlayerMoves.BLUFF, PlayerMoves.SPEND) }
                .mapNotNull { it.effect }.all { it.actorId == "f_ash" },
            "your character tried to move an army by themselves",
        )
    }

    @Test
    fun `your character looks and your house is what learns`() {
        // Only factions hold beliefs, so a look that named the character as
        // the learner would clear rows that never exist and correct nothing.
        val look = verb(fixture(), PlayerMoves.LOOK).single()
        assertEquals(ActorEffect("c_you", Effect.Observe(forId = "f_ash")), look.effect)
    }

    @Test
    fun `travel is offered to everywhere but here`() {
        val travels = verb(fixture(), PlayerMoves.TRAVEL)
        assertEquals(listOf("loc_road"), travels.mapNotNull { (it.effect?.effect as? Effect.MoveTo)?.locationId })
    }

    @Test
    fun `spending offers only what is not contested ground`() {
        // Pooled stock moves by claim and by nothing else — the engine refuses
        // a direct adjustment on it — so offering to spend it would be a
        // button that silently does nothing.
        val spends = verb(fixture(), PlayerMoves.SPEND)
        assertEquals(listOf("grain", "might"), spends.map { (it.effect?.effect as Effect.AdjustStock).key })
        val grain = spends.first().effect?.effect as Effect.AdjustStock
        assertEquals(-200L, grain.deltaMilli, "a tenth of 2,000")
        // Territory is in a pool and is absent from the list entirely.
        assertTrue(spends.none { (it.effect?.effect as Effect.AdjustStock).key == "territory" })
    }

    @Test
    fun `a seat that was never assigned offers nothing but an explanation`() {
        val orphan = PlayerMoves.available(
            PlayerView.of(fixture(), observerId = "c_nobody", factionId = "f_nobody"),
        )
        assertEquals(1, orphan.size)
        assertFalse(orphan.single().legal)
        assertEquals("You have no seat in this world.", orphan.single().blockedBy)
    }
}
