package com.aura.creative.livingworld

import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The test the game rests on.
 *
 * [WorldReplayer]'s KDoc names its own tripwire: state must never be mutated
 * outside the engine, and "any future god-edit surface must land as a
 * replayable event kind, or fork-at-past breaks silently". A player action is
 * that surface, and *silently* is the word that matters — a world whose actions
 * are lost still replays to a perfectly plausible state, just not the one that
 * was played. Nothing at runtime would ever complain.
 *
 * So the journal here is not hand-written. It is built out of the
 * `player_action` events the engine actually emitted, round-tripped through the
 * same JSON codec [LivingWorldStore] persists them with, which is the whole
 * production path minus Room.
 */
class PlayedWorldReplayTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun hash(state: WorldState): String =
        json.encodeToString(WorldState.serializer(), state.canonical())

    /**
     * Two keeps, three houses, a conserved territory pool, and a character who
     * leads Ashfall and can walk between the keeps.
     */
    private fun genesis(): WorldState = WorldState(
        entities = listOf(
            SimEntity(id = "loc_keep", kind = "location", name = "The Keep"),
            SimEntity(id = "loc_road", kind = "location", name = "The Low Road"),
            SimEntity(id = "c_you", kind = "character", name = "You", parentId = "loc_road"),
            SimEntity(id = "f_ash", kind = "faction", name = "Ashfall", parentId = "loc_keep"),
            SimEntity(id = "f_bram", kind = "faction", name = "Bramwatch", parentId = "loc_keep"),
            SimEntity(id = "f_cor", kind = "faction", name = "Cormere", parentId = "loc_road"),
        ),
        stocks = listOf(
            Stock("f_ash", "grain", 5_000, capacityMilli = 10_000, flowPerTickMilli = -60),
            Stock("f_bram", "grain", 5_000, capacityMilli = 10_000, flowPerTickMilli = -60),
            Stock("f_cor", "grain", 5_000, capacityMilli = 10_000, flowPerTickMilli = -60),
            Stock("f_ash", "territory", 4_000, poolId = "territory", scarcity = Stock.SCARCITY_CONSERVED),
            Stock("f_bram", "territory", 3_000, poolId = "territory", scarcity = Stock.SCARCITY_CONSERVED),
            Stock("f_cor", "territory", 3_000, poolId = "territory", scarcity = Stock.SCARCITY_CONSERVED),
        ),
        relations = listOf(
            Relation("f_ash", "f_bram", "grievance", 400),
            Relation("f_bram", "f_cor", "grievance", 300),
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

    /** An evening of play: eight ticks, six of them with a move in them. */
    private fun script(): Map<Long, List<ActorEffect>> = mapOf(
        1L to listOf(ActorEffect("f_ash", Effect.SpreadLie("territory", 3_000))),
        2L to listOf(ActorEffect("c_you", Effect.MoveTo("loc_keep"))),
        3L to listOf(ActorEffect("c_you", Effect.Observe(forId = "f_ash"))),
        4L to listOf(
            ActorEffect("f_ash", Effect.ClaimPool("territory", "territory", 900)),
            ActorEffect("f_ash", Effect.AdjustStock("grain", -1_200)),
        ),
        6L to listOf(ActorEffect("f_ash", Effect.AdjustRelation("grievance", -250))),
        8L to listOf(ActorEffect("c_you", Effect.MoveTo("loc_road"))),
    )

    private data class Played(val state: WorldState, val journal: List<WorldReplayer.ActionAt>)

    /**
     * Play the script forward and collect the journal the way the store would.
     *
     * The payload is encoded and decoded on the way through on purpose: a codec
     * that cannot round-trip an [Effect] is indistinguishable at runtime from an
     * engine that ignored the action.
     */
    private fun play(through: Long, script: Map<Long, List<ActorEffect>>): Played {
        var state = genesis()
        val journal = mutableListOf<WorldReplayer.ActionAt>()
        for (tick in 1..through) {
            val result = WorldEngine.tick(state, "w1", 7L, 0L, tick, script[tick].orEmpty())
            state = result.state
            for (event in result.events) {
                if (event.kind != WorldEngine.KIND_PLAYER_ACTION) continue
                val encoded = json.encodeToString(Effect.serializer(), requireNotNull(event.payload))
                journal += WorldReplayer.ActionAt(
                    atTick = event.tick,
                    seq = event.seq,
                    action = ActorEffect(event.actorId, json.decodeFromString(Effect.serializer(), encoded)),
                )
            }
        }
        return Played(state, journal)
    }

    private fun segments(through: Long) =
        listOf(WorldReplayer.Segment("w1", 7L, 0L, 0L, through))

    @Test
    fun `a world that was played replays to byte-identical state`() {
        val played = play(8L, script())
        val replayed = WorldReplayer.stateAt(genesis(), segments(8L), emptyList(), played.journal, 8L)
        assertEquals(hash(played.state), hash(replayed), "the replay played a different world")
    }

    @Test
    fun `every action reached the journal`() {
        val played = play(8L, script())
        // Six submitted, six journalled. A dropped action is the failure this
        // whole file exists to make loud, and it is the one that would still
        // leave the assertion above passing if the *replay* dropped it too.
        assertEquals(script().values.sumOf { it.size }, played.journal.size)
        assertEquals(listOf(1L, 2L, 3L, 4L, 4L, 6L, 8L), played.journal.map { it.atTick }.sorted())
        assertEquals(
            script().values.flatten().map { it.effect }.toSet(),
            played.journal.map { it.action.effect }.toSet(),
        )
    }

    @Test
    fun `replaying without the journal produces a different world`() {
        // The negative control. Without it, a replayer that silently ignored
        // its actions argument would pass the test above whenever the actions
        // happened not to matter, and this suite would be decoration.
        val played = play(8L, script())
        val amnesiac = WorldReplayer.stateAt(genesis(), segments(8L), emptyList(), emptyList(), 8L)
        assertNotEquals(hash(played.state), hash(amnesiac), "the actions changed nothing, so nothing was tested")
    }

    @Test
    fun `two actions in one tick replay in the order they were submitted`() {
        // Claims and lies are deferred and resolved canonically, so they are
        // indifferent to submission order. Direct adjustments are not: grain
        // caps at 10,000, so filling the store and then spending lands
        // somewhere the reverse order never reaches. That clamp is what makes
        // `seq` a requirement rather than a tidy habit.
        val forward = mapOf(
            2L to listOf(
                ActorEffect("f_ash", Effect.AdjustStock("grain", 6_000)),
                ActorEffect("f_ash", Effect.AdjustStock("grain", -1_000)),
            ),
        )
        val played = play(3L, forward)
        assertEquals(listOf(0, 1), played.journal.map { it.seq })

        val replayed = WorldReplayer.stateAt(genesis(), segments(3L), emptyList(), played.journal, 3L)
        assertEquals(hash(played.state), hash(replayed))

        // The same two actions with their seqs swapped must not reproduce it.
        val shuffled = played.journal.map { at ->
            at.copy(seq = if (at.seq == 0) 1 else 0)
        }
        val wrongOrder = WorldReplayer.stateAt(genesis(), segments(3L), emptyList(), shuffled, 3L)
        assertNotEquals(hash(played.state), hash(wrongOrder), "submission order made no difference")
    }

    @Test
    fun `forking before an action yields a branch where it never happened`() {
        val played = play(8L, script())

        // Fork at 3 — before the claim at tick 4. The child replays from
        // genesis along the parent's segments with only the actions up to 3.
        val cutoff = 3L
        val beforeFork = played.journal.filter { it.atTick <= cutoff }
        val branch = WorldReplayer.stateAt(genesis(), segments(cutoff), emptyList(), beforeFork, cutoff)

        val ashTerritory = branch.stocks.first { it.entityId == "f_ash" && it.key == "territory" }
        assertEquals(4_000L, ashTerritory.amountMilli, "the branch carries a claim that had not been made yet")

        // The lie at tick 1 is on the far side of the fork, so the branch keeps
        // it. A fork that dropped everything would pass the assertion above.
        assertTrue(
            branch.beliefs.any { it.subjectId == "f_ash" && it.deviationMilli != 0L },
            "the branch lost history from before the fork point",
        )
    }

    @Test
    fun `an action on the fold's own arrival tick is refused too`() {
        // The seam, and the worst case of the lot. A fold covers
        // `(start, atTick]` — the cursor jumps straight to `atTick` without
        // ever calling `tick()` for it — so an action sitting exactly on that
        // boundary is as unreplayable as one in the middle of the span. A
        // guard that stops one short of it is worse than no guard, because
        // it reads as though the case was considered.
        val folds = listOf(WorldReplayer.FoldSpan(atTick = 20L, ticks = 10L))
        val onTheSeam = listOf(
            WorldReplayer.ActionAt(20L, 0, ActorEffect("f_ash", Effect.SpreadLie("territory", 500))),
        )
        assertFailsWith<IllegalArgumentException> {
            WorldReplayer.stateAt(genesis(), segments(30L), folds, onTheSeam, 30L)
        }
    }

    @Test
    fun `an action on the tick a fold starts from is fine`() {
        // The other side of the seam. `start` is the last tick before the
        // fold and was simulated normally, so an action there replays. A
        // guard that refused it would make ordinary play unforkable.
        val folds = listOf(WorldReplayer.FoldSpan(atTick = 20L, ticks = 10L))
        val justBefore = listOf(
            WorldReplayer.ActionAt(10L, 0, ActorEffect("f_ash", Effect.SpreadLie("territory", 500))),
        )
        WorldReplayer.stateAt(genesis(), segments(30L), folds, justBefore, 30L)
    }

    @Test
    fun `an action inside a folded span is refused rather than silently dropped`() {
        // Folds collapse ticks nobody was awake for and cannot carry an action.
        // Replaying past one would produce a world where the move never
        // happened — plausible, wrong, and completely quiet. The replayer is
        // required to say so instead.
        val folds = listOf(WorldReplayer.FoldSpan(atTick = 20L, ticks = 10L))
        val stranded = listOf(
            WorldReplayer.ActionAt(15L, 0, ActorEffect("f_ash", Effect.SpreadLie("territory", 500))),
        )
        val failure = assertFailsWith<IllegalArgumentException> {
            WorldReplayer.stateAt(genesis(), segments(30L), folds, stranded, 30L)
        }
        assertTrue(failure.message.orEmpty().contains("cannot be replayed"))
    }
}
