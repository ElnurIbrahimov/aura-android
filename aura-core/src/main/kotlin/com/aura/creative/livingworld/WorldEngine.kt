package com.aura.creative.livingworld

/**
 * The world's mechanism. Pure, deterministic, and entirely free of Android and
 * of the network.
 *
 * **No model is ever called from inside a tick.** That is architecture, not
 * economy: a step function that needs a round-trip cannot catch up two days of
 * absence in one worker slice, cannot be replayed to reconstruct the past, and
 * cannot be tested without a key. Prose is attached afterwards, to the few
 * events that earn it, by something else.
 *
 * Surprise comes from the mechanism rather than from sampling. Stocks drift
 * smoothly while rules fire on thresholds, so pressure accumulates quietly and
 * then breaks; and because claims on a conserved pool are strictly transfers,
 * one faction's gain is always somebody's loss.
 *
 * Every iteration below walks a sorted list. Maps appear only as lookup indices
 * and are never iterated — see [WorldState].
 */
object WorldEngine {

    /** Advance exactly one tick. */
    fun tick(
        state: WorldState,
        worldId: String,
        rootSeed: Long,
        branchSalt: Long,
        tick: Long,
    ): TickResult {
        val current = state.canonical()
        val seed = WorldRng.tickSeed(rootSeed, branchSalt, tick)
        val events = mutableListOf<WorldEvent>()

        val stocks = LinkedHashMap<String, Stock>(current.stocks.size * 2)
        for (stock in current.stocks) stocks[stockKey(stock.entityId, stock.key)] = advanceFlow(stock)

        val relations = LinkedHashMap<String, Relation>(current.relations.size * 2)
        for (relation in current.relations) relations[relationKey(relation)] = decay(relation)

        val living = current.living()
        val byId = LinkedHashMap<String, SimEntity>(living.size * 2)
        for (entity in living) byId[entity.id] = entity

        val firings = mutableListOf<Firing>()
        for (rule in current.rules) {
            if (!rule.enabled) continue
            for (subject in living) {
                if (subject.kind != rule.subjectKind) continue
                if (onCooldown(rule, subject.id, tick)) continue
                if (!evaluate(rule.condition, subject, stocks, current.relations)) continue
                firings += Firing(rule, subject)
            }
        }

        val claims = mutableListOf<Claim>()
        for (firing in firings) {
            for (effect in firing.rule.effects) {
                when (effect) {
                    is Effect.AdjustStock -> applyStockDelta(
                        stocks, firing, effect, tick, events,
                    )
                    is Effect.AdjustRelation -> applyRelationDelta(
                        relations, current.relations, byId, firing, effect, tick, events,
                    )
                    is Effect.ClaimPool -> claims += Claim(firing, effect)
                }
            }
        }

        resolveClaims(claims, stocks, byId, seed, tick, events)

        val firedIds = firings.map { it.rule.id to it.subject.id }.toSet()
        val updatedRules = current.rules.map { rule -> recordFirings(rule, firedIds, tick) }

        val next = WorldState(
            entities = current.entities,
            stocks = stocks.values.toList(),
            relations = relations.values.toList(),
            rules = updatedRules,
        ).canonical()

        return TickResult(next, events.mapIndexed { index, event -> event.copy(seq = index) })
    }

    /**
     * Collapse [ticks] quiet ticks into one closed-form step.
     *
     * This is what makes catching up cost the same whether the user was away
     * for three days or three months. Flows and relation decay are linear, so
     * applying them `n` times is a multiplication; rules are deliberately *not*
     * evaluated, which is the approximation being traded for a bounded cost.
     * The detail window around the present is always simulated properly, so the
     * fold only ever covers the part of the past nobody is going to inspect
     * closely.
     */
    fun fold(state: WorldState, ticks: Long, atTick: Long): TickResult {
        if (ticks <= 0L) return TickResult(state.canonical(), emptyList())
        val current = state.canonical()

        val stocks = current.stocks.map { stock ->
            if (stock.poolId.isNotBlank() || stock.flowPerTickMilli == 0L) stock
            else clampStock(stock, stock.amountMilli + stock.flowPerTickMilli * ticks)
        }
        val relations = current.relations.map { relation ->
            if (relation.decayPerTickMilli == 0L) relation
            else relation.copy(magnitudeMilli = towardZero(relation.magnitudeMilli, relation.decayPerTickMilli * ticks))
        }

        val summary = "$ticks quiet days passed."
        val event = WorldEvent(
            tick = atTick,
            seq = 0,
            kind = KIND_QUIET_INTERVAL,
            actorId = "",
            magnitudeMilli = ticks,
            summary = summary,
        )
        return TickResult(
            WorldState(current.entities, stocks, relations, current.rules).canonical(),
            listOf(event),
        )
    }

    // ---- internals -------------------------------------------------------

    private data class Firing(val rule: Rule, val subject: SimEntity)
    private data class Claim(val firing: Firing, val effect: Effect.ClaimPool)

    private fun advanceFlow(stock: Stock): Stock {
        // A pooled stock moves only by transfer. Letting a flow touch it would
        // silently break the conservation the pool exists to guarantee, so the
        // engine enforces it here rather than trusting whoever wrote the rule.
        if (stock.poolId.isNotBlank() || stock.flowPerTickMilli == 0L) return stock
        return clampStock(stock, stock.amountMilli + stock.flowPerTickMilli)
    }

    private fun clampStock(stock: Stock, raw: Long): Stock {
        val floored = if (raw < 0L) 0L else raw
        val capped = if (stock.capacityMilli > 0L && floored > stock.capacityMilli) stock.capacityMilli else floored
        return if (capped == stock.amountMilli) stock else stock.copy(amountMilli = capped)
    }

    private fun decay(relation: Relation): Relation {
        if (relation.decayPerTickMilli == 0L) return relation
        val moved = towardZero(relation.magnitudeMilli, relation.decayPerTickMilli)
        return if (moved == relation.magnitudeMilli) relation else relation.copy(magnitudeMilli = moved)
    }

    private fun towardZero(value: Long, by: Long): Long {
        val step = if (by < 0L) -by else by
        return when {
            value > 0L -> if (value - step < 0L) 0L else value - step
            value < 0L -> if (value + step > 0L) 0L else value + step
            else -> 0L
        }
    }

    private fun onCooldown(rule: Rule, subjectId: String, tick: Long): Boolean {
        if (rule.cooldownTicks <= 0L) return false
        val last = rule.lastFired.firstOrNull { it.entityId == subjectId } ?: return false
        return tick - last.tick < rule.cooldownTicks
    }

    private fun recordFirings(rule: Rule, fired: Set<Pair<String, String>>, tick: Long): Rule {
        val subjects = rule.lastFired.map { it.entityId }.toMutableSet()
        val updated = rule.lastFired.map { entry ->
            if (fired.contains(rule.id to entry.entityId)) entry.copy(tick = tick) else entry
        }.toMutableList()
        for ((ruleId, subjectId) in fired.sortedWith(compareBy({ it.first }, { it.second }))) {
            if (ruleId == rule.id && subjects.add(subjectId)) updated += FiredAt(subjectId, tick)
        }
        return if (updated.isEmpty()) rule else rule.copy(lastFired = updated.sortedBy { it.entityId })
    }

    private fun evaluate(
        cond: Cond,
        subject: SimEntity,
        stocks: Map<String, Stock>,
        allRelations: List<Relation>,
    ): Boolean = when (cond) {
        is Cond.Always -> true
        is Cond.StockBelow -> (stocks[stockKey(subject.id, cond.key)]?.amountMilli ?: 0L) < cond.thresholdMilli
        is Cond.StockAbove -> (stocks[stockKey(subject.id, cond.key)]?.amountMilli ?: 0L) > cond.thresholdMilli
        is Cond.RelationAbove -> allRelations.any {
            it.fromId == subject.id && it.kind == cond.kind && it.magnitudeMilli > cond.thresholdMilli
        }
        is Cond.And -> cond.all.all { evaluate(it, subject, stocks, allRelations) }
        is Cond.Or -> cond.any.any { evaluate(it, subject, stocks, allRelations) }
        is Cond.Not -> !evaluate(cond.cond, subject, stocks, allRelations)
    }

    private fun applyStockDelta(
        stocks: MutableMap<String, Stock>,
        firing: Firing,
        effect: Effect.AdjustStock,
        tick: Long,
        events: MutableList<WorldEvent>,
    ) {
        val key = stockKey(firing.subject.id, effect.key)
        val existing = stocks[key] ?: return
        // A pooled stock is conserved; only a transfer may move it.
        if (existing.poolId.isNotBlank()) return
        val updated = clampStock(existing, existing.amountMilli + effect.deltaMilli)
        if (updated.amountMilli == existing.amountMilli) return
        stocks[key] = updated
        val direction = if (effect.deltaMilli > 0L) "rose" else "fell"
        events += WorldEvent(
            tick = tick,
            seq = 0,
            kind = KIND_STOCK_SHIFT,
            actorId = firing.subject.id,
            ruleId = firing.rule.id,
            magnitudeMilli = updated.amountMilli - existing.amountMilli,
            stockKey = effect.key,
            summary = "${firing.subject.name}: ${effect.key} $direction (${firing.rule.name}).",
        )
    }

    private fun applyRelationDelta(
        relations: MutableMap<String, Relation>,
        sortedRelations: List<Relation>,
        byId: Map<String, SimEntity>,
        firing: Firing,
        effect: Effect.AdjustRelation,
        tick: Long,
        events: MutableList<WorldEvent>,
    ) {
        // Resolve the target from state rather than naming it in the rule, so
        // the same rule works in any world. Ties break on id, never on
        // encounter order.
        val target = sortedRelations
            .filter { it.fromId == firing.subject.id && it.kind == effect.kind }
            .sortedWith(compareByDescending<Relation> { it.magnitudeMilli }.thenBy { it.toId })
            .firstOrNull() ?: return
        val key = relationKey(target)
        val existing = relations[key] ?: return
        val updated = existing.copy(magnitudeMilli = existing.magnitudeMilli + effect.deltaMilli)
        relations[key] = updated
        val targetName = byId[target.toId]?.name ?: target.toId
        val direction = if (effect.deltaMilli > 0L) "hardened toward" else "softened toward"
        events += WorldEvent(
            tick = tick,
            seq = 0,
            kind = KIND_RELATION_SHIFT,
            actorId = firing.subject.id,
            targetId = target.toId,
            ruleId = firing.rule.id,
            magnitudeMilli = effect.deltaMilli,
            summary = "${firing.subject.name} $direction $targetName (${firing.rule.name}).",
        )
    }

    /**
     * Resolve every claim on a conserved pool.
     *
     * One draw per pool per tick, keyed by the pool rather than by a counter, so
     * adding an unrelated rule elsewhere in the tick cannot change who wins
     * here. The winner takes from the largest other holder, which is a strict
     * transfer — the pool total is invariant by construction, not by assertion.
     */
    private fun resolveClaims(
        claims: List<Claim>,
        stocks: MutableMap<String, Stock>,
        byId: Map<String, SimEntity>,
        tickSeed: Long,
        tick: Long,
        events: MutableList<WorldEvent>,
    ) {
        if (claims.isEmpty()) return
        val pools = claims.map { it.effect.poolId }.distinct().sorted()
        for (poolId in pools) {
            val contenders = claims
                .filter { it.effect.poolId == poolId }
                .sortedBy { it.firing.subject.id }
            if (contenders.isEmpty()) continue

            val weights = contenders.map { claim ->
                val might = stocks[stockKey(claim.firing.subject.id, STOCK_MIGHT)]?.amountMilli
                if (might != null && might > 0L) might else DEFAULT_MIGHT_MILLI
            }
            val winnerIndex = WorldRng.weightedPick(WorldRng.substream(tickSeed, "pool:$poolId"), weights)
            val winner = contenders[winnerIndex]
            val stockName = winner.effect.key

            val donor = stocks.values
                .filter { it.poolId == poolId && it.key == stockName && it.entityId != winner.firing.subject.id }
                .sortedWith(compareByDescending<Stock> { it.amountMilli }.thenBy { it.entityId })
                .firstOrNull() ?: continue
            if (donor.amountMilli <= 0L) continue

            val moved = if (winner.effect.amountMilli < donor.amountMilli) winner.effect.amountMilli else donor.amountMilli
            if (moved <= 0L) continue

            val winnerKey = stockKey(winner.firing.subject.id, stockName)
            val winnerStock = stocks[winnerKey] ?: continue
            stocks[winnerKey] = winnerStock.copy(amountMilli = winnerStock.amountMilli + moved)
            stocks[stockKey(donor.entityId, stockName)] = donor.copy(amountMilli = donor.amountMilli - moved)

            val donorName = byId[donor.entityId]?.name ?: donor.entityId
            events += WorldEvent(
                tick = tick,
                seq = 0,
                kind = KIND_CLAIM_WON,
                actorId = winner.firing.subject.id,
                targetId = donor.entityId,
                ruleId = winner.firing.rule.id,
                magnitudeMilli = moved,
                stockKey = stockName,
                summary = "${winner.firing.subject.name} took $stockName from $donorName.",
            )
        }
    }

    private fun stockKey(entityId: String, key: String): String = "$entityId $key"

    private fun relationKey(relation: Relation): String =
        "${relation.fromId} ${relation.toId} ${relation.kind}"

    const val KIND_STOCK_SHIFT = "stock_shift"
    const val KIND_RELATION_SHIFT = "relation_shift"
    const val KIND_CLAIM_WON = "claim_won"
    const val KIND_QUIET_INTERVAL = "quiet_interval"

    /** Stock consulted when weighting a contested claim. Absent means average. */
    const val STOCK_MIGHT = "might"
    private const val DEFAULT_MIGHT_MILLI = 1_000L
}
