package com.aura.creative.livingworld

import kotlin.math.abs

/**
 * How interesting an event is, from 0 to 1.
 *
 * This is the cost governor for the whole feature. The engine produces events
 * for free and a model is only ever asked about the few that clear the bar, so
 * what this function considers important is what the world will be heard to say.
 *
 * Every input is already in hand when an event is committed — no extra query,
 * no model call. It has to stay that way: a scorer that costs something defeats
 * the thing it exists to make cheap.
 *
 * **The multiplicative core is the point.** A change that is enormous but
 * routine, or enormous but affects nothing scarce, scores near zero and is
 * never narrated. Only a change that is large *relative to what is at stake*,
 * *in something finite*, and *not the fifth of its kind this month* survives.
 *
 * Two of the five factors the design calls for are deliberately absent rather
 * than faked. **Reach** (how many factions witnessed it) and **surprise** (how
 * many of them already expected it) both require the belief layer, which does
 * not exist yet. Inventing plausible stand-ins would produce a number that
 * looks like five factors and is really three, and there would be no way to
 * tell later which part was measuring anything.
 */
object NotabilityScorer {

    /**
     * @param recentSimilar how many events of the same kind by the same actor
     *   are already on record within the novelty window.
     */
    fun score(event: WorldEvent, state: WorldState, recentSimilar: Int): Double {
        if (event.kind == WorldEngine.KIND_QUIET_INTERVAL) return QUIET_INTERVAL_SCORE

        val magnitude = magnitude(event, state)
        val scarcity = scarcity(event, state)
        val novelty = 1.0 / (1.0 + recentSimilar.coerceAtLeast(0))

        var score = magnitude * scarcity * novelty
        // The map changing hands is the one event that is always worth a
        // mention: it is the only thing in the world that cannot be undone by
        // the losing side simply waiting.
        if (event.kind == WorldEngine.KIND_CLAIM_WON) score += CLAIM_BONUS
        return score.coerceIn(0.0, 1.0)
    }

    /**
     * Size of the change against the right denominator.
     *
     * For a conserved pool that denominator is an **average holding**, not the
     * pool total: taking a tenth of everything there is means little if there
     * are fifty holders and a great deal if there are three.
     */
    private fun magnitude(event: WorldEvent, state: WorldState): Double {
        val delta = abs(event.magnitudeMilli).toDouble()
        if (delta <= 0.0) return 0.0
        if (event.stockKey.isBlank()) {
            return (delta / RELATION_REFERENCE_MILLI).coerceIn(0.0, 1.0)
        }

        val stock = state.stocks.firstOrNull { it.entityId == event.actorId && it.key == event.stockKey }
        val reference = when {
            stock == null -> RELATION_REFERENCE_MILLI
            stock.poolId.isNotBlank() -> {
                val holders = state.stocks.count { it.poolId == stock.poolId && it.key == stock.key }
                val total = state.stocks.filter { it.poolId == stock.poolId && it.key == stock.key }
                    .sumOf { it.amountMilli }
                if (holders > 0 && total > 0L) total.toDouble() / holders else RELATION_REFERENCE_MILLI
            }
            stock.capacityMilli > 0L -> stock.capacityMilli.toDouble()
            else -> (abs(stock.amountMilli) + delta)
        }
        if (reference <= 0.0) return 0.0
        return (delta / reference).coerceIn(0.0, 1.0)
    }

    private fun scarcity(event: WorldEvent, state: WorldState): Double {
        if (event.stockKey.isBlank()) return RELATION_SCARCITY
        val stock = state.stocks.firstOrNull { it.entityId == event.actorId && it.key == event.stockKey }
            ?: return Stock.SCARCITY_RENEWABLE.let { SCARCITY_RENEWABLE }
        return when (stock.scarcity) {
            Stock.SCARCITY_CONSERVED -> SCARCITY_CONSERVED
            Stock.SCARCITY_RENEWABLE -> SCARCITY_RENEWABLE
            else -> SCARCITY_ABSTRACT
        }
    }

    /** How far back "have I seen this before" looks, in events. */
    const val NOVELTY_WINDOW = 64

    /**
     * The bar an event must clear to be worth a model call.
     *
     * A world of three factions on a shared map produces a conquest worth about
     * 0.4 and routine famine relief worth about 0.1, so this sits between them:
     * land changing hands is news, a harvest coming in on schedule is not.
     */
    const val DEFAULT_FLOOR = 0.35

    /**
     * A folded absence is always worth mentioning — it is the only event that
     * tells the reader time passed at all — but it is a summary, not a
     * happening, so it sits just above the floor rather than at the top.
     */
    private const val QUIET_INTERVAL_SCORE = 0.40

    private const val CLAIM_BONUS = 0.25
    private const val SCARCITY_CONSERVED = 1.0
    private const val SCARCITY_RENEWABLE = 0.4
    private const val SCARCITY_ABSTRACT = 0.2
    private const val RELATION_SCARCITY = 0.5

    /** Relations have no capacity, so they are measured against a fixed sense of "a lot". */
    private const val RELATION_REFERENCE_MILLI = 1_000.0
}

/** An event with the score it was given when it was committed. */
data class ScoredEvent(val event: WorldEvent, val notability: Double)
