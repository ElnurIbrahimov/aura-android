package com.aura.creative.livingworld

import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tests the whole feature rests on.
 *
 * Touring the past, rewinding, and forking a branch all assume that replaying a
 * tick reproduces it exactly. That assumption is invisible in ordinary use — a
 * world that drifts still runs, still writes events, and still looks alive; it
 * just quietly stops agreeing with its own history. So it has to be pinned by a
 * test rather than noticed by a user.
 *
 * Deliberately pure JVM: no Room, no Android, no network, no key.
 */
class WorldEngineTest {

    private val json = Json { prettyPrint = false; encodeDefaults = true }

    private fun hash(state: WorldState): String =
        json.encodeToString(WorldState.serializer(), state.canonical())

    /**
     * Three factions on a shared, strictly finite map, all starving on the same
     * schedule so their claims collide rather than politely alternating.
     */
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

    private fun grain(id: String) = Stock(id, "grain", 5_000, capacityMilli = 10_000, flowPerTickMilli = -30)
    private fun might(id: String, amount: Long) = Stock(id, "might", amount, scarcity = Stock.SCARCITY_ABSTRACT)
    private fun territory(id: String, amount: Long) = Stock(
        id, "territory", amount, poolId = "territory", scarcity = Stock.SCARCITY_CONSERVED,
    )

    private fun run(state: WorldState, ticks: Long, seed: Long = 42L, salt: Long = 0L): Pair<WorldState, List<WorldEvent>> {
        var current = state
        val all = mutableListOf<WorldEvent>()
        for (tick in 1..ticks) {
            val result = WorldEngine.tick(current, "w1", seed, salt, tick)
            current = result.state
            all += result.events
        }
        return current to all
    }

    @Test
    fun `replaying a thousand ticks from the same seed reproduces it exactly`() {
        val (first, firstEvents) = run(fixture(), 1_000)
        val (second, secondEvents) = run(fixture(), 1_000)

        assertEquals(hash(first), hash(second), "state diverged on replay")
        assertEquals(firstEvents.size, secondEvents.size, "event count diverged on replay")
        assertEquals(
            firstEvents.map { "${it.tick}.${it.seq}:${it.kind}:${it.actorId}:${it.magnitudeMilli}" },
            secondEvents.map { "${it.tick}.${it.seq}:${it.kind}:${it.actorId}:${it.magnitudeMilli}" },
            "event stream diverged on replay",
        )
    }

    @Test
    fun `resuming from a mid-run state matches running straight through`() {
        val (straight, _) = run(fixture(), 400)

        var partial = fixture()
        for (tick in 1..150L) partial = WorldEngine.tick(partial, "w1", 42L, 0L, tick).state
        // Round-trip through serialization, because that is what a real resume
        // after process death does — a state that only replays correctly while
        // it stays in memory is not resumable.
        val revived = json.decodeFromString(WorldState.serializer(), json.encodeToString(WorldState.serializer(), partial))
        for (tick in 151..400L) partial = WorldEngine.tick(if (tick == 151L) revived else partial, "w1", 42L, 0L, tick).state

        assertEquals(hash(straight), hash(partial), "resumed run diverged from the straight-through run")
    }

    @Test
    fun `a different seed produces a different history`() {
        val (a, _) = run(fixture(), 500, seed = 42L)
        val (b, _) = run(fixture(), 500, seed = 43L)
        assertTrue(hash(a) != hash(b), "two seeds produced identical worlds — the draw is not being used")
    }

    @Test
    fun `a conserved pool total is invariant across a thousand ticks`() {
        val start = fixture()
        val startTotal = start.stocks.filter { it.poolId == "territory" }.sumOf { it.amountMilli }

        var current = start
        for (tick in 1..1_000L) {
            current = WorldEngine.tick(current, "w1", 42L, 0L, tick).state
            val total = current.stocks.filter { it.poolId == "territory" }.sumOf { it.amountMilli }
            assertEquals(startTotal, total, "territory stopped being conserved at tick $tick")
        }
    }

    @Test
    fun `a pooled stock never moves by flow even when a rule tries`() {
        // A rule that adjusts a pooled stock directly must be refused, not
        // honoured — otherwise conservation depends on nobody writing the
        // obvious rule.
        val meddling = Rule(
            id = "meddle",
            name = "Meddle",
            condition = Cond.Always,
            effects = listOf(Effect.AdjustStock("territory", 5_000)),
        )
        val start = fixture(extraRules = listOf(meddling))
        val startTotal = start.stocks.filter { it.poolId == "territory" }.sumOf { it.amountMilli }
        val (end, _) = run(start, 50)
        assertEquals(
            startTotal,
            end.stocks.filter { it.poolId == "territory" }.sumOf { it.amountMilli },
            "a direct AdjustStock on a pooled stock changed the pool total",
        )
    }

    /**
     * The property that content-keyed random substreams exist to provide.
     *
     * With one sequential generator consumed in iteration order, adding a rule
     * that has nothing to do with territory would shift every later draw in the
     * tick and hand the map to different factions. That failure is silent and
     * only shows up as two histories that should match and don't.
     */
    @Test
    fun `adding an unrelated rule does not change who wins the contested pool`() {
        val unrelated = Rule(
            id = "songs",
            name = "Songs are sung",
            priority = 5,
            condition = Cond.Always,
            effects = listOf(Effect.AdjustStock("grain", 0)),
        )

        val (_, without) = run(fixture(), 400)
        val (_, with) = run(fixture(extraRules = listOf(unrelated)), 400)

        fun claims(events: List<WorldEvent>) = events
            .filter { it.kind == WorldEngine.KIND_CLAIM_WON }
            .map { "${it.tick}:${it.actorId}->${it.targetId}:${it.magnitudeMilli}" }

        assertTrue(claims(without).isNotEmpty(), "fixture produced no contested claims — the test proves nothing")
        assertEquals(claims(without), claims(with), "an unrelated rule changed the outcome of contested claims")
    }

    @Test
    fun `folding a quiet stretch matches ticking through it when no rule fires`() {
        // Grain well above the famine threshold, so nothing fires and the fold's
        // approximation is exact rather than merely close.
        val quiet = WorldState(
            entities = listOf(SimEntity("f_ash", "faction", "Ashfall")),
            stocks = listOf(Stock("f_ash", "grain", 9_000, capacityMilli = 20_000, flowPerTickMilli = -5)),
            relations = listOf(Relation("f_ash", "f_bram", "grievance", 900, decayPerTickMilli = 3)),
            rules = emptyList(),
        )

        var stepped = quiet
        for (tick in 1..100L) stepped = WorldEngine.tick(stepped, "w1", 7L, 0L, tick).state
        val folded = WorldEngine.fold(quiet, 100, atTick = 100).state

        assertEquals(hash(stepped), hash(folded), "fold diverged from stepping the same quiet stretch")
    }

    @Test
    fun `fold emits one event regardless of how long the absence was`() {
        val quiet = WorldState(
            entities = listOf(SimEntity("f_ash", "faction", "Ashfall")),
            stocks = listOf(Stock("f_ash", "grain", 9_000, flowPerTickMilli = -1)),
        )
        val short = WorldEngine.fold(quiet, 24, atTick = 24)
        val long = WorldEngine.fold(quiet, 24_000, atTick = 24_000)

        assertEquals(1, short.events.size)
        assertEquals(1, long.events.size, "a longer absence produced more events — catch-up cost is not constant")
        assertEquals(WorldEngine.KIND_QUIET_INTERVAL, long.events.first().kind)
    }

    @Test
    fun `stocks never fall below zero or exceed capacity`() {
        val drained = WorldState(
            entities = listOf(SimEntity("f_ash", "faction", "Ashfall")),
            stocks = listOf(
                Stock("f_ash", "grain", 100, capacityMilli = 500, flowPerTickMilli = -50),
                Stock("f_ash", "coin", 400, capacityMilli = 500, flowPerTickMilli = 50),
            ),
        )
        val (end, _) = run(drained, 100)
        val grain = end.stocks.first { it.key == "grain" }
        val coin = end.stocks.first { it.key == "coin" }
        assertEquals(0L, grain.amountMilli, "grain went negative")
        assertEquals(500L, coin.amountMilli, "coin exceeded its capacity")
    }

    @Test
    fun `a rule on cooldown does not fire every tick`() {
        val (_, events) = run(fixture(), 300)
        val famineByActor = events
            .filter { it.ruleId == "famine" && it.actorId == "f_ash" }
            .map { it.tick }
            .distinct()
            .sorted()
        assertTrue(famineByActor.size >= 2, "famine never re-fired — cooldown test proves nothing")
        val gaps = famineByActor.zipWithNext { a, b -> b - a }
        assertTrue(gaps.all { it >= 20 }, "famine fired inside its 20-tick cooldown: $gaps")
    }
}
