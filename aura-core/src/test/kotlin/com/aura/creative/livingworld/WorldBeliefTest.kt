package com.aura.creative.livingworld

import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The belief layer's contract.
 *
 * Beliefs are deviations from truth: no row means accurate common knowledge,
 * and every mechanism here — staleness, rumor, snapping, reveals — is integer
 * arithmetic on content-keyed draws. These tests pin the properties the rest
 * of the program leans on: replay stays exact with beliefs in the state, an
 * old blob without a beliefs key still decodes, deviations stay bounded, the
 * fold and the stepped path agree on a quiet stretch, and a discovery fires
 * once rather than every tick.
 *
 * Deliberately pure JVM: no Room, no Android, no network, no key.
 */
class WorldBeliefTest {

    private val json = Json { prettyPrint = false; encodeDefaults = true }

    private fun hash(state: WorldState): String =
        json.encodeToString(WorldState.serializer(), state.canonical())

    /** The WorldEngineTest fixture: three factions, one finite map, colliding famines. */
    private fun fixture(extraRules: List<Rule> = emptyList()): WorldState = WorldState(
        entities = listOf(
            SimEntity(id = "f_ash", kind = "faction", name = "Ashfall"),
            SimEntity(id = "f_bram", kind = "faction", name = "Bramwatch"),
            SimEntity(id = "f_cor", kind = "faction", name = "Cormere"),
        ),
        stocks = listOf(
            grain("f_ash"), grain("f_bram"), grain("f_cor"),
            might("f_ash", 3_000), might("f_bram", 1_000), might("f_cor", 2_000),
            territory("f_ash", 4_000), territory("f_bram", 3_000), territory("f_cor", 3_000),
        ),
        relations = listOf(
            Relation("f_ash", "f_bram", "grievance", 500, decayPerTickMilli = 1),
            Relation("f_bram", "f_cor", "grievance", 400, decayPerTickMilli = 1),
            Relation("f_cor", "f_ash", "grievance", 300, decayPerTickMilli = 1),
        ),
        rules = listOf(
            Rule(
                id = "famine",
                name = "Famine drives expansion",
                condition = Cond.StockBelow("grain", 2_000),
                effects = listOf(
                    Effect.ClaimPool("territory", "territory", 200),
                    Effect.AdjustStock("grain", 500),
                ),
                cooldownTicks = 20,
            ),
        ) + extraRules,
    )

    private fun grain(id: String, amount: Long = 5_000, flow: Long = -30) =
        Stock(id, "grain", amount, capacityMilli = 10_000, flowPerTickMilli = flow)

    private fun might(id: String, amount: Long) = Stock(id, "might", amount, scarcity = Stock.SCARCITY_ABSTRACT)

    private fun territory(id: String, amount: Long) = Stock(
        id, "territory", amount, poolId = "territory", scarcity = Stock.SCARCITY_CONSERVED,
    )

    private data class Run(val state: WorldState, val events: List<WorldEvent>, val sawBeliefs: Boolean)

    private fun run(state: WorldState, ticks: Long, seed: Long = 42L, salt: Long = 0L): Run {
        var current = state
        val all = mutableListOf<WorldEvent>()
        var saw = false
        for (tick in 1..ticks) {
            val result = WorldEngine.tick(current, "w1", seed, salt, tick)
            current = result.state
            all += result.events
            if (current.beliefs.isNotEmpty()) saw = true
        }
        return Run(current, all, saw)
    }

    @Test
    fun `replaying a thousand ticks with beliefs reproduces it exactly`() {
        val first = run(fixture(), 1_000)
        val second = run(fixture(), 1_000)

        assertTrue(first.sawBeliefs, "no belief ever formed — the layer is not exercised and this test is vacuous")
        assertEquals(hash(first.state), hash(second.state), "state diverged on replay")
        assertEquals(
            first.events.map { "${it.tick}.${it.seq}:${it.kind}:${it.actorId}:${it.reachPermille}:${it.surprisePermille}" },
            second.events.map { "${it.tick}.${it.seq}:${it.kind}:${it.actorId}:${it.reachPermille}:${it.surprisePermille}" },
            "event stream, reach or surprise diverged on replay",
        )
    }

    @Test
    fun `a rule that never fires changes nothing, reveal stream included`() {
        // The belief step draws on content-keyed substreams. If rule presence
        // alone shifted them, every world would rewrite its future when the
        // author added a law — the exact failure WorldRng exists to prevent.
        val never = Rule(
            id = "zz_never",
            name = "Never fires",
            condition = Cond.StockBelow("grain", -1),
            effects = listOf(Effect.AdjustStock("grain", 1)),
        )
        val bare = run(fixture(), 500)
        val extra = run(fixture(listOf(never)), 500)

        assertEquals(
            hash(bare.state.copy(rules = emptyList())),
            hash(extra.state.copy(rules = emptyList())),
            "stocks, relations or beliefs diverged when an inert rule was added",
        )
        assertEquals(
            bare.events.map { "${it.tick}:${it.kind}:${it.actorId}:${it.reachPermille}:${it.surprisePermille}" },
            extra.events.map { "${it.tick}:${it.kind}:${it.actorId}:${it.reachPermille}:${it.surprisePermille}" },
            "event or reveal stream diverged when an inert rule was added",
        )
    }

    @Test
    fun `a state serialized before beliefs existed decodes to accurate common knowledge`() {
        val revived = Json { ignoreUnknownKeys = true }.decodeFromString(
            WorldState.serializer(),
            """{"entities":[],"stocks":[],"relations":[],"rules":[]}""",
        )
        assertTrue(revived.beliefs.isEmpty(), "an old blob must decode to an empty belief table, not fail")
    }

    @Test
    fun `full mutual visibility never forms a deviation`() {
        val ids = listOf("f_ash", "f_bram", "f_cor")
        val watchful = fixture().copy(
            relations = ids.flatMap { from ->
                ids.filter { it != from }.map { to -> Relation(from, to, "grievance", 600, decayPerTickMilli = 0) }
            },
        )
        var current = watchful
        for (tick in 1..300L) {
            current = WorldEngine.tick(current, "w1", 42L, 0L, tick).state
            assertTrue(
                current.beliefs.isEmpty(),
                "tick $tick formed a belief although every faction watches every other",
            )
        }
    }

    @Test
    fun `an unwitnessed claim leaves the bystander wrong by exactly the transfer`() {
        val state = WorldState(
            entities = listOf(
                SimEntity(id = "f_a", kind = "faction", name = "A"),
                SimEntity(id = "f_b", kind = "faction", name = "B"),
                SimEntity(id = "f_c", kind = "faction", name = "C"),
            ),
            stocks = listOf(
                grain("f_a", amount = 1_000, flow = 0), grain("f_b", flow = 0), grain("f_c", flow = 0),
                territory("f_a", 4_000), territory("f_b", 3_000), territory("f_c", 3_000),
            ),
            relations = emptyList(), // nobody watches anybody
            rules = listOf(
                Rule(
                    id = "famine",
                    name = "Famine",
                    condition = Cond.StockBelow("grain", 2_000),
                    effects = listOf(Effect.ClaimPool("territory", "territory", 200)),
                    cooldownTicks = 999,
                ),
            ),
        )
        // Pick a seed whose rumor draw misses, so C genuinely does not hear.
        // Computing the draw the engine's own way is the point: the key, not a
        // counter, decides — so the miss is a property of the seed, not luck.
        val seed = (1L..500L).first { s ->
            val tickSeed = WorldRng.tickSeed(s, 0L, 1L)
            val draw = WorldRng.substream(tickSeed, "belief:f_c:claim_won:f_a:f_b:territory")
            WorldRng.bounded(draw, 1_000L) >= WorldEngine.RUMOR_CHANCE_PERMILLE
        }

        val result = WorldEngine.tick(state, "w1", seed, 0L, 1L)
        val aboutWinner = result.state.beliefs.first { it.observerId == "f_c" && it.subjectId == "f_a" && it.key == "territory" }
        val aboutDonor = result.state.beliefs.first { it.observerId == "f_c" && it.subjectId == "f_b" && it.key == "territory" }

        assertEquals(-200L, aboutWinner.deviationMilli, "C should believe the winner still holds the old amount")
        assertEquals(200L, aboutDonor.deviationMilli, "C should believe the donor still holds the old amount")
        assertEquals(Belief.PROVENANCE_STALE, aboutWinner.provenance)
    }

    @Test
    fun `a discovery fires once, zeroes the error, and does not fire again`() {
        val state = WorldState(
            entities = listOf(
                SimEntity(id = "f_a", kind = "faction", name = "A"),
                SimEntity(id = "f_b", kind = "faction", name = "B"),
                SimEntity(id = "f_c", kind = "faction", name = "C"),
            ),
            stocks = listOf(
                grain("f_a", amount = 1_000, flow = 0), grain("f_b", flow = 0), grain("f_c", flow = 0),
                territory("f_a", 4_000), territory("f_b", 3_000), territory("f_c", 3_000),
            ),
            relations = listOf(
                // C watches A, so the claim is witnessed and the standing error snaps.
                Relation("f_c", "f_a", "grievance", 600, decayPerTickMilli = 0),
            ),
            rules = listOf(
                Rule(
                    id = "famine",
                    name = "Famine",
                    condition = Cond.StockBelow("grain", 2_000),
                    effects = listOf(Effect.ClaimPool("territory", "territory", 100)),
                    cooldownTicks = 0,
                ),
            ),
            beliefs = listOf(
                Belief("f_c", "f_a", "territory", deviationMilli = 2_000, decayPerTickMilli = 0),
            ),
        )

        var current = state
        val reveals = mutableListOf<WorldEvent>()
        for (tick in 1..5L) {
            val result = WorldEngine.tick(current, "w1", 42L, 0L, tick)
            current = result.state
            reveals += result.events.filter { it.kind == WorldEngine.KIND_BELIEF_REVEAL }
        }

        assertEquals(1, reveals.size, "one standing error must produce exactly one discovery")
        assertEquals("f_c", reveals.first().actorId)
        assertEquals("f_a", reveals.first().targetId)
        assertEquals(2_000L, reveals.first().magnitudeMilli)
        assertTrue(
            current.beliefs.none { it.observerId == "f_c" && it.subjectId == "f_a" && it.key == "territory" },
            "the discovered error must be gone",
        )
    }

    @Test
    fun `a standing lie changes who wins a contested pool`() {
        fun contest(withLie: Boolean): WorldState = WorldState(
            entities = listOf(
                SimEntity(id = "f_a", kind = "faction", name = "A"),
                SimEntity(id = "f_b", kind = "faction", name = "B"),
                SimEntity(id = "f_c", kind = "faction", name = "C"),
            ),
            stocks = listOf(
                grain("f_a", amount = 1_000, flow = 0), grain("f_b", amount = 1_000, flow = 0),
                grain("f_c", flow = 0),
                might("f_a", 500), might("f_b", 3_000),
                territory("f_a", 2_000), territory("f_b", 2_000), territory("f_c", 6_000),
            ),
            relations = emptyList(),
            rules = listOf(
                Rule(
                    id = "famine",
                    name = "Famine",
                    condition = Cond.StockBelow("grain", 2_000),
                    effects = listOf(Effect.ClaimPool("territory", "territory", 300)),
                    cooldownTicks = 999,
                ),
            ),
            beliefs = if (!withLie) emptyList() else listOf(
                // B has been told A is a colossus. Only the draw's weighting
                // sees this row; nothing real moved.
                Belief(
                    "f_b", "f_a", "might", deviationMilli = 8_000,
                    provenance = Belief.PROVENANCE_LIED_TO, sourceId = "f_a", decayPerTickMilli = 0,
                ),
            ),
        )

        fun winner(state: WorldState, seed: Long): String =
            WorldEngine.tick(state, "w1", seed, 0L, 1L)
                .events.first { it.kind == WorldEngine.KIND_CLAIM_WON }.actorId

        val flipped = (1L..500L).any { seed -> winner(contest(false), seed) != winner(contest(true), seed) }
        assertTrue(flipped, "no seed in 1..500 flipped the winner — the claim draw is not reading beliefs")
    }

    @Test
    fun `a spread lie plants the same error in everyone else, sourced to the liar`() {
        val state = WorldState(
            entities = listOf(
                SimEntity(id = "f_a", kind = "faction", name = "A"),
                SimEntity(id = "f_b", kind = "faction", name = "B"),
                SimEntity(id = "f_c", kind = "faction", name = "C"),
            ),
            stocks = listOf(
                might("f_a", 400), might("f_b", 3_000), might("f_c", 3_000),
                grain("f_a", flow = 0), grain("f_b", flow = 0), grain("f_c", flow = 0),
            ),
            relations = emptyList(),
            rules = listOf(
                Rule(
                    id = "bluster",
                    name = "Bluster",
                    condition = Cond.StockBelow("might", 500),
                    effects = listOf(Effect.SpreadLie("might", 700)),
                    cooldownTicks = 999,
                ),
            ),
        )

        val result = WorldEngine.tick(state, "w1", 42L, 0L, 1L)

        val lie = result.events.single { it.kind == WorldEngine.KIND_LIE_TOLD }
        assertEquals("f_a", lie.actorId)
        assertEquals(1_000L, lie.reachPermille, "propaganda reaches everyone by construction")

        for (observer in listOf("f_b", "f_c")) {
            val planted = result.state.beliefs.first {
                it.observerId == observer && it.subjectId == "f_a" && it.key == "might"
            }
            assertEquals(700L, planted.deviationMilli, "$observer should overestimate the liar by the lie")
            assertEquals(Belief.PROVENANCE_LIED_TO, planted.provenance)
            assertEquals("f_a", planted.sourceId)
        }
        assertTrue(
            result.state.beliefs.none { it.observerId == "f_a" && it.subjectId == "f_a" },
            "the liar's own book stays true",
        )
    }

    @Test
    fun `deviations never exceed the stock's cap over a thousand ticks`() {
        var current = fixture()
        for (tick in 1..1_000L) {
            current = WorldEngine.tick(current, "w1", 42L, 0L, tick).state
            for (belief in current.beliefs) {
                val capacity = current.stocks
                    .firstOrNull { it.entityId == belief.subjectId && it.key == belief.key }
                    ?.capacityMilli ?: 0L
                val cap = if (capacity > 0L) capacity else WorldEngine.DEVIATION_CAP_MILLI
                assertTrue(
                    belief.deviationMilli in -cap..cap,
                    "tick $tick: ${belief.observerId} is ${belief.deviationMilli} wrong about " +
                        "${belief.subjectId}'s ${belief.key}, past the cap of $cap",
                )
            }
        }
    }

    @Test
    fun `folding a quiet stretch matches stepping it with beliefs present`() {
        val quiet = WorldState(
            entities = listOf(
                SimEntity(id = "f_a", kind = "faction", name = "A"),
                SimEntity(id = "f_b", kind = "faction", name = "B"),
            ),
            stocks = listOf(grain("f_a"), grain("f_b")),
            relations = listOf(Relation("f_a", "f_b", "grievance", 400, decayPerTickMilli = 1)),
            rules = emptyList(),
            beliefs = listOf(
                Belief("f_a", "f_b", "grain", deviationMilli = 90, decayPerTickMilli = 2),
                Belief("f_b", "f_a", "grain", deviationMilli = -70, decayPerTickMilli = 2),
            ),
        )

        var stepped = quiet
        for (tick in 1..50L) stepped = WorldEngine.tick(stepped, "w1", 42L, 0L, tick).state
        val folded = WorldEngine.fold(quiet, 50, atTick = 50).state

        assertEquals(hash(stepped), hash(folded), "fold and step disagree on a stretch where no rule fires")
    }

    @Test
    fun `at most four discoveries become events in one tick, the rest apply silently`() {
        val others = listOf("f_b", "f_c", "f_d", "f_e", "f_f")
        val state = WorldState(
            entities = (listOf("f_a") + others).map { SimEntity(id = it, kind = "faction", name = it.uppercase()) },
            stocks = listOf(grain("f_a", amount = 1_000, flow = 0)) +
                others.map { grain(it, flow = 0) } +
                (listOf("f_a" to 4_000L) + others.map { it to 3_000L }).map { (id, amount) -> territory(id, amount) },
            relations = others.map { Relation(it, "f_a", "grievance", 600, decayPerTickMilli = 0) },
            rules = listOf(
                Rule(
                    id = "famine",
                    name = "Famine",
                    condition = Cond.StockBelow("grain", 2_000),
                    effects = listOf(Effect.ClaimPool("territory", "territory", 100)),
                    cooldownTicks = 999,
                ),
            ),
            beliefs = others.map { Belief(it, "f_a", "territory", deviationMilli = 2_000, decayPerTickMilli = 0) },
        )

        val result = WorldEngine.tick(state, "w1", 42L, 0L, 1L)
        val reveals = result.events.filter { it.kind == WorldEngine.KIND_BELIEF_REVEAL }

        assertEquals(
            WorldEngine.MAX_BELIEF_EVENTS_PER_TICK, reveals.size,
            "five standing errors snapped at once must surface as exactly the capped number of events",
        )
        assertTrue(
            result.state.beliefs.none { it.subjectId == "f_a" && it.key == "territory" },
            "the silent discoveries must still have cleared their errors",
        )
    }
}
