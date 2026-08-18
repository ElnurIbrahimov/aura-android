package com.aura.creative.livingworld

/**
 * Where two timelines part ways, and what the parting cost.
 *
 * Pure comparisons over event pages and states — no queries, no model. The
 * caller supplies both branches' events ascending from their common fork tick;
 * the first field-wise mismatch is the divergence. Pages are bounded, so two
 * branches identical for longer than a page report "identical so far" at the
 * horizon rather than scanning forever — the caller states that honestly.
 */
object TimelineDiff {

    /** The first differing pair; either side null when one branch simply has more. */
    data class Divergence(val a: LivingEventEntity?, val b: LivingEventEntity?)

    fun firstDivergence(a: List<LivingEventEntity>, b: List<LivingEventEntity>): Divergence? {
        val length = maxOf(a.size, b.size)
        for (index in 0 until length) {
            val ea = a.getOrNull(index)
            val eb = b.getOrNull(index)
            if (ea == null || eb == null) return Divergence(ea, eb)
            if (!matches(ea, eb)) return Divergence(ea, eb)
        }
        return null
    }

    /** Identity fields only — narration and notability are commentary, not history. */
    private fun matches(a: LivingEventEntity, b: LivingEventEntity): Boolean =
        a.tickIndex == b.tickIndex && a.seq == b.seq && a.kind == b.kind &&
            a.actorId == b.actorId && a.targetId == b.targetId &&
            a.ruleId == b.ruleId && a.magnitudeMilli == b.magnitudeMilli

    /**
     * Per-faction, per-stock deltas between two states, in whole units,
     * rendered as "Name: key A -> B" lines. Factions and keys sorted, so the
     * output is stable enough to test and to show.
     */
    fun standingsDiff(a: WorldState, b: WorldState): List<String> {
        val names = (a.entities + b.entities)
            .filter { it.kind == "faction" }
            .associate { it.id to it.name }
        val stocksA = a.stocks.associateBy { it.entityId to it.key }
        val stocksB = b.stocks.associateBy { it.entityId to it.key }
        val keys = (stocksA.keys + stocksB.keys).sortedWith(
            compareBy({ it.first }, { it.second }),
        )
        return keys.mapNotNull { key ->
            val fromAmount = stocksA[key]?.amountMilli ?: 0L
            val toAmount = stocksB[key]?.amountMilli ?: 0L
            if (fromAmount == toAmount) return@mapNotNull null
            val name = names[key.first] ?: key.first
            "$name: ${key.second} ${fromAmount / 1_000} -> ${toAmount / 1_000}"
        }
    }
}
