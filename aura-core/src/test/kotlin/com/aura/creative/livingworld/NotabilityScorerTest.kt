package com.aura.creative.livingworld

import org.junit.Test
import kotlin.test.assertTrue

/**
 * The scorer is the cost governor: what it thinks is interesting is what the
 * world will be heard to say, and everything below the floor is free.
 *
 * So the property that matters is not any single number but the **ordering** —
 * that a conquest outranks a harvest — and that the default floor actually
 * falls between them. A scorer where everything clears the bar is an
 * unbounded bill; one where nothing does is a world that never speaks.
 */
class NotabilityScorerTest {

    private val state = WorldSeeder().seed(
        com.aura.creative.WorldBible(
            factions = listOf(
                com.aura.creative.WorldFaction(id = "a", name = "Ashfall"),
                com.aura.creative.WorldFaction(id = "b", name = "Bramwatch"),
                com.aura.creative.WorldFaction(id = "c", name = "Cormere"),
            ),
        ),
        WorldSetup(territoryTotalMilli = 120_000L, grainCapacityMilli = 100_000L),
    )

    private fun claim(amount: Long = 6_000L) = WorldEvent(
        tick = 10, seq = 0, kind = WorldEngine.KIND_CLAIM_WON, actorId = "a", targetId = "b",
        magnitudeMilli = amount, stockKey = WorldSeeder.STOCK_TERRITORY, summary = "took land",
    )

    private fun harvest(amount: Long = 25_000L) = WorldEvent(
        tick = 10, seq = 0, kind = WorldEngine.KIND_STOCK_SHIFT, actorId = "a",
        magnitudeMilli = amount, stockKey = WorldSeeder.STOCK_GRAIN, summary = "grain rose",
    )

    @Test
    fun `land changing hands outranks a harvest coming in`() {
        assertTrue(
            NotabilityScorer.score(claim(), state, recentSimilar = 0) >
                NotabilityScorer.score(harvest(), state, recentSimilar = 0),
            "a conquest did not outrank routine resupply",
        )
    }

    @Test
    fun `the default floor sits between a conquest and a harvest`() {
        val conquest = NotabilityScorer.score(claim(), state, recentSimilar = 0)
        val routine = NotabilityScorer.score(harvest(), state, recentSimilar = 0)
        assertTrue(
            conquest >= NotabilityScorer.DEFAULT_FLOOR,
            "a conquest scored $conquest, below the ${NotabilityScorer.DEFAULT_FLOOR} floor — the world would never speak",
        )
        assertTrue(
            routine < NotabilityScorer.DEFAULT_FLOOR,
            "routine resupply scored $routine, above the floor — every tick would be narrated",
        )
    }

    @Test
    fun `the fifth raid this month is worth less than the first`() {
        val first = NotabilityScorer.score(claim(), state, recentSimilar = 0)
        val fifth = NotabilityScorer.score(claim(), state, recentSimilar = 4)
        assertTrue(fifth < first, "repetition did not reduce notability")
    }

    @Test
    fun `taking more land scores higher than taking less`() {
        assertTrue(
            NotabilityScorer.score(claim(24_000), state, 0) > NotabilityScorer.score(claim(2_000), state, 0),
            "magnitude is not being measured",
        )
    }

    @Test
    fun `a change in something conserved outranks the same change in something renewable`() {
        // Purpose-built so scarcity is the *only* difference. Comparing an
        // equal number of land and coin in the seeded world proves nothing:
        // they have different denominators, so the magnitudes are already
        // incomparable before scarcity is applied. Eight thousand coin against
        // a treasury of three thousand really is a bigger event than eight
        // thousand land out of a hundred and twenty.
        val isolated = WorldState(
            entities = listOf(SimEntity("a", "faction", "Ashfall")),
            stocks = listOf(
                Stock("a", "land", 5_000, capacityMilli = 10_000, scarcity = Stock.SCARCITY_CONSERVED),
                Stock("a", "coin", 5_000, capacityMilli = 10_000, scarcity = Stock.SCARCITY_RENEWABLE),
            ),
        )
        fun shift(key: String) = WorldEvent(
            tick = 1, seq = 0, kind = WorldEngine.KIND_STOCK_SHIFT, actorId = "a",
            magnitudeMilli = 2_000, stockKey = key, summary = "",
        )
        assertTrue(
            NotabilityScorer.score(shift("land"), isolated, 0) >
                NotabilityScorer.score(shift("coin"), isolated, 0),
            "scarcity class is not affecting the score",
        )
    }

    @Test
    fun `a folded absence always clears the floor`() {
        // It is the only event that tells the reader time passed at all.
        val quiet = WorldEvent(
            tick = 100, seq = 0, kind = WorldEngine.KIND_QUIET_INTERVAL, actorId = "",
            magnitudeMilli = 300, summary = "300 quiet days passed.",
        )
        assertTrue(NotabilityScorer.score(quiet, state, 0) >= NotabilityScorer.DEFAULT_FLOOR)
    }

    @Test
    fun `every score stays inside zero and one`() {
        val absurd = claim(amount = 999_999_999L)
        val score = NotabilityScorer.score(absurd, state, 0)
        assertTrue(score in 0.0..1.0, "score escaped its range: $score")
    }

    @Test
    fun `surprise raises a claim's score`() {
        val quiet = NotabilityScorer.score(claim(), state, recentSimilar = 0)
        val shocking = NotabilityScorer.score(claim().copy(surprisePermille = 1_000), state, recentSimilar = 0)
        assertTrue(shocking > quiet, "an event the world was wrong about did not score higher")
    }

    @Test
    fun `reach raises a claim's score`() {
        val unseen = NotabilityScorer.score(claim(), state, recentSimilar = 0)
        val public = NotabilityScorer.score(claim().copy(reachPermille = 1_000), state, recentSimilar = 0)
        assertTrue(public > unseen, "an event everyone came to know did not score higher")
    }

    @Test
    fun `a large public discovery clears the floor and a trivial one does not`() {
        fun reveal(surprise: Long, reach: Long) = WorldEvent(
            tick = 10, seq = 0, kind = WorldEngine.KIND_BELIEF_REVEAL, actorId = "a", targetId = "b",
            magnitudeMilli = 5_000, stockKey = WorldEngine.STOCK_MIGHT,
            reachPermille = reach, surprisePermille = surprise, summary = "discovered",
        )
        val collapse = NotabilityScorer.score(reveal(surprise = 800, reach = 1_000), state, 0)
        val correction = NotabilityScorer.score(reveal(surprise = 100, reach = 0), state, 0)
        assertTrue(
            collapse >= NotabilityScorer.DEFAULT_FLOOR,
            "a big lie collapsing in public scored $collapse, below the floor — it would never be narrated",
        )
        assertTrue(
            correction < NotabilityScorer.DEFAULT_FLOOR,
            "a trivial correction scored $correction, above the floor — noise would be narrated",
        )
    }

    @Test
    fun `reach and surprise leave a zeroed event exactly where it was`() {
        // Every event elsewhere in this file carries the zero defaults, so the
        // whole existing suite is the regression pin; this makes the neutrality
        // explicit for the reader.
        val base = NotabilityScorer.score(claim(), state, recentSimilar = 0)
        val zeroed = NotabilityScorer.score(
            claim().copy(reachPermille = 0, surprisePermille = 0), state, recentSimilar = 0,
        )
        assertTrue(base == zeroed, "zero reach and surprise must not move a score")
    }
}
