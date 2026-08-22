package com.aura.creative.livingworld

import kotlin.math.abs

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

    /**
     * Advance exactly one tick.
     *
     * [actorEffects] are moves submitted by someone who is not a rule — the
     * player, today. They are applied *before* rule conditions are evaluated,
     * so the world answers what you did inside the same tick rather than a
     * tick later: spend your grain and the famine rule sees the smaller pile.
     * Their claims are pooled with everyone else's and resolved together, so
     * taking contested ground stays a contest rather than a privilege.
     *
     * Each one is journalled as a [KIND_PLAYER_ACTION] event carrying the
     * effect, which is what makes a world that was played replayable.
     */
    fun tick(
        state: WorldState,
        worldId: String,
        rootSeed: Long,
        branchSalt: Long,
        tick: Long,
        actorEffects: List<ActorEffect> = emptyList(),
    ): TickResult {
        val current = state.canonical()
        val seed = WorldRng.tickSeed(rootSeed, branchSalt, tick)
        val events = mutableListOf<WorldEvent>()

        val stocks = LinkedHashMap<String, Stock>(current.stocks.size * 2)
        for (stock in current.stocks) stocks[stockKey(stock.entityId, stock.key)] = advanceFlow(stock)

        val relations = LinkedHashMap<String, Relation>(current.relations.size * 2)
        for (relation in current.relations) relations[relationKey(relation)] = decay(relation)

        // Truth outs: every error shrinks a little each tick, before events
        // refresh or deepen it. Zero rows are dropped at the end of the belief
        // step, not here, so the step still sees what just became accurate.
        val decayedBeliefs = current.beliefs.map { decayBelief(it) }

        val living = current.living()
        val byId = LinkedHashMap<String, SimEntity>(living.size * 2)
        for (entity in living) byId[entity.id] = entity

        val claims = mutableListOf<Claim>()
        val lies = mutableListOf<LieIntent>()
        val moves = mutableListOf<MoveIntent>()
        val looks = mutableListOf<Look>()

        // The player moves first. Not favouritism — it is what separates an
        // action from a wish: the rule conditions below are evaluated against
        // stocks and relations that already carry what you just did, so the
        // world can answer inside the tick you acted in.
        //
        // An action whose actor is unknown or dead is dropped rather than
        // thrown on. Actions are submitted a tick ahead and an actor can die
        // in between; a world that refused to tick because of one stale order
        // would be a world one bad row can stop forever.
        val actorBatch = mutableListOf<Pair<Firing, List<Effect>>>()
        for (action in actorEffects) {
            val subject = byId[action.actorId] ?: continue
            actorBatch += Firing(PLAYER_ORDER, subject) to listOf(action.effect)
            events += WorldEvent(
                tick = tick,
                seq = 0,
                kind = KIND_PLAYER_ACTION,
                actorId = subject.id,
                summary = subject.name + " acts.",
                payload = action.effect,
            )
        }
        applyEffects(
            actorBatch, stocks, relations, current.relations, byId, tick, events,
            claims, lies, moves, looks,
        )

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
        applyEffects(
            firings.map { it to it.rule.effects },
            stocks, relations, current.relations, byId, tick, events,
            claims, lies, moves, looks,
        )

        // Looking around resolves before claims and before the belief step,
        // and the order of those two is the whole balance of the verb.
        // Before claims, so what you counted with your own eyes is what you
        // weigh the draw with. Before the belief step, so a lie told this
        // same tick still lands on you — observing is a check on yesterday's
        // propaganda, never a shield against today's.
        val observedBeliefs =
            if (looks.isEmpty()) decayedBeliefs
            else observe(decayedBeliefs, looks, byId, stocks, tick, events)

        // Local index only, for the claim seam and the belief step.
        val beliefIndex = HashMap<String, Long>(observedBeliefs.size * 2)
        for (belief in observedBeliefs) {
            beliefIndex[beliefKey(belief.observerId, belief.subjectId, belief.key)] = belief.deviationMilli
        }

        resolveClaims(claims, stocks, byId, beliefIndex, seed, tick, events)

        val nextBeliefs =
            beliefStep(observedBeliefs, lies, events, stocks, living, current.relations, byId, seed, tick)

        val firedIds = firings.map { it.rule.id to it.subject.id }.toSet()
        val updatedRules = current.rules.map { rule -> recordFirings(rule, firedIds, tick) }

        // Travel resolves last, so the tick you spent on the road happened
        // where you set out from. Leaving is not a way to be already gone.
        val movedEntities =
            if (moves.isEmpty()) current.entities
            else applyMoves(current.entities, moves, byId, tick, events)

        val next = WorldState(
            entities = movedEntities,
            stocks = stocks.values.toList(),
            relations = relations.values.toList(),
            rules = updatedRules,
            beliefs = nextBeliefs,
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

        // Belief decay is linear too, so it folds the same way. Nothing forms
        // and nothing is revealed inside a folded span: a folded absence is a
        // world that quietly caught up on the news.
        val beliefs = current.beliefs.mapNotNull { belief ->
            if (belief.decayPerTickMilli == 0L) belief
            else {
                val moved = towardZero(belief.deviationMilli, belief.decayPerTickMilli * ticks)
                if (moved == 0L) null else belief.copy(deviationMilli = moved)
            }
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
            WorldState(current.entities, stocks, relations, current.rules, beliefs).canonical(),
            listOf(event),
        )
    }

    // ---- internals -------------------------------------------------------

    private data class Firing(val rule: Rule, val subject: SimEntity)
    private data class Claim(val firing: Firing, val effect: Effect.ClaimPool)
    private data class MoveIntent(val subjectId: String, val locationId: String)

    /** Somebody looking around, and whose table the looking corrects. */
    private data class Look(val looker: SimEntity, val learnerId: String)

    /**
     * The rule a player action wears so it can travel the path a rule's
     * effect travels.
     *
     * Its id matches no real rule, so cooldown bookkeeping skips it and
     * acting can never exhaust anything. It exists to hand
     * [applyStockDelta] and [applyRelationDelta] the attribution they stamp
     * on events, and for nothing else.
     */
    private val PLAYER_ORDER = Rule(id = KIND_PLAYER_ACTION, name = "your order")

    /**
     * Apply one batch of effects, deferring the four kinds whose outcome
     * must not depend on the order they were encountered in.
     *
     * Called twice per tick — once for player actions, once for rule firings
     * — against the same collectors, so a player claim and a rule claim reach
     * [resolveClaims] indistinguishable from one another. That is the point:
     * the engine has no idea which of its actors is a person.
     */
    @Suppress("LongParameterList")
    private fun applyEffects(
        batch: List<Pair<Firing, List<Effect>>>,
        stocks: MutableMap<String, Stock>,
        relations: MutableMap<String, Relation>,
        sortedRelations: List<Relation>,
        byId: Map<String, SimEntity>,
        tick: Long,
        events: MutableList<WorldEvent>,
        claims: MutableList<Claim>,
        lies: MutableList<LieIntent>,
        moves: MutableList<MoveIntent>,
        looks: MutableList<Look>,
    ) {
        for ((firing, effects) in batch) {
            for (effect in effects) {
                when (effect) {
                    is Effect.AdjustStock -> applyStockDelta(stocks, firing, effect, tick, events)
                    is Effect.AdjustRelation -> applyRelationDelta(
                        relations, sortedRelations, byId, firing, effect, tick, events,
                    )
                    is Effect.ClaimPool -> claims += Claim(firing, effect)
                    // Deferred like claims, so application order is canonical
                    // rather than firing-encounter order.
                    is Effect.SpreadLie -> lies += LieIntent(firing, effect)
                    is Effect.MoveTo -> moves += MoveIntent(firing.subject.id, effect.locationId)
                    is Effect.Observe -> looks += Look(
                        looker = firing.subject,
                        learnerId = effect.forId.ifBlank { firing.subject.id },
                    )
                }
            }
        }
    }

    /**
     * Drop every deviation the lookers hold about whatever stands where they
     * stand.
     *
     * The counterweight to [Effect.SpreadLie]. A lie costs the liar nothing
     * and pays until truth outs on its own, so without a way to *check*,
     * propaganda would be strictly dominant. This is that way, and its price
     * is presence: you have to be there, which makes being somewhere else a
     * real cost.
     *
     * The correction is total, not partial. Standing in the room and
     * counting is ground truth about that room; the fog here models
     * distance, not eyesight. An unplaced looker sees nothing, which is why
     * a blank `parentId` is skipped rather than treated as a place named "".
     */
    private fun observe(
        beliefs: List<Belief>,
        looks: List<Look>,
        byId: Map<String, SimEntity>,
        stocks: Map<String, Stock>,
        tick: Long,
        events: MutableList<WorldEvent>,
    ): List<Belief> {
        val cleared = LinkedHashSet<String>()
        // Looking twice in one tick is looking once. Without this, two
        // Observes would clear the same row and narrate it twice.
        for (look in looks.distinctBy { it.looker.id to it.learnerId }) {
            val here = look.looker.parentId
            if (here.isBlank()) continue
            for (belief in beliefs) {
                if (belief.observerId != look.learnerId) continue
                if (belief.deviationMilli == 0L) continue
                val subject = byId[belief.subjectId] ?: continue
                // Co-location is judged from where the *looker* stands. A
                // house learns what its scout is standing next to, not what
                // is next to the house.
                if (subject.parentId != here) continue
                cleared += beliefKey(belief.observerId, belief.subjectId, belief.key)
                events += WorldEvent(
                    tick = tick,
                    seq = 0,
                    kind = KIND_BELIEF_REVEAL,
                    actorId = belief.observerId,
                    targetId = belief.subjectId,
                    magnitudeMilli = abs(belief.deviationMilli),
                    // Seen with one pair of eyes. Nobody else learns anything,
                    // which is what separates looking from a public reveal.
                    reachPermille = 0L,
                    stockKey = belief.key,
                    surprisePermille = permille(
                        abs(belief.deviationMilli),
                        deviationCap(belief.subjectId, belief.key, stocks),
                    ),
                    summary = look.looker.name + " sees " + subject.name + "'s " +
                        belief.key + " for what it is.",
                )
            }
        }
        if (cleared.isEmpty()) return beliefs
        return beliefs.filterNot { beliefKey(it.observerId, it.subjectId, it.key) in cleared }
    }

    /**
     * Put the movers where they said they were going.
     *
     * A destination that is not an entity in this world is refused rather
     * than obeyed. A bogus `parentId` is how something falls out of every
     * co-location check in the game at once — unreachable, unobservable, and
     * unable to observe — which is a silent removal from the world wearing a
     * move's clothes.
     */
    private fun applyMoves(
        entities: List<SimEntity>,
        moves: List<MoveIntent>,
        byId: Map<String, SimEntity>,
        tick: Long,
        events: MutableList<WorldEvent>,
    ): List<SimEntity> {
        val destinations = LinkedHashMap<String, String>(moves.size * 2)
        for (move in moves) {
            val subject = byId[move.subjectId] ?: continue
            val destination = byId[move.locationId] ?: continue
            if (subject.parentId == move.locationId) continue
            destinations[move.subjectId] = move.locationId
            events += WorldEvent(
                tick = tick,
                seq = 0,
                kind = KIND_MOVED,
                actorId = subject.id,
                targetId = destination.id,
                summary = subject.name + " arrives at " + destination.name + ".",
            )
        }
        if (destinations.isEmpty()) return entities
        return entities.map { entity ->
            val destination = destinations[entity.id]
            if (destination == null) entity else entity.copy(parentId = destination)
        }
    }

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
        beliefs: Map<String, Long>,
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
                val subjectId = claim.firing.subject.id
                val actualRaw = stocks[stockKey(subjectId, STOCK_MIGHT)]?.amountMilli
                val actual = if (actualRaw != null && actualRaw > 0L) actualRaw else DEFAULT_MIGHT_MILLI
                // The draw is weighted by what the rival claimants believe this
                // one could bring to bear. Self-belief is exact by construction,
                // so an uncontested reading collapses to the actual stock — and
                // bluffed might genuinely wins ground until a reveal snaps it.
                val rivals = contenders.filter { it.firing.subject.id != subjectId }
                if (rivals.isEmpty()) actual
                else {
                    val believedSum = rivals.sumOf { rival ->
                        val deviation =
                            beliefs[beliefKey(rival.firing.subject.id, subjectId, STOCK_MIGHT)] ?: 0L
                        (actual + deviation).coerceAtLeast(0L)
                    }
                    believedSum / rivals.size
                }
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


    private fun decayBelief(belief: Belief): Belief {
        if (belief.decayPerTickMilli == 0L) return belief
        val moved = towardZero(belief.deviationMilli, belief.decayPerTickMilli)
        return if (moved == belief.deviationMilli) belief else belief.copy(deviationMilli = moved)
    }

    private data class StockChange(val subjectId: String, val key: String, val deltaMilli: Long)

    private data class LieIntent(val firing: Firing, val effect: Effect.SpreadLie)

    private data class Reveal(
        val observerId: String,
        val subjectId: String,
        val key: String,
        val clearedMilli: Long,
        val reachPermille: Long,
    )

    /** The two changes a claim is, or the one a shift is. Nothing else moves a stock. */
    private fun stockChanges(event: WorldEvent): List<StockChange> = when (event.kind) {
        KIND_STOCK_SHIFT -> listOf(StockChange(event.actorId, event.stockKey, event.magnitudeMilli))
        KIND_CLAIM_WON -> listOf(
            StockChange(event.actorId, event.stockKey, event.magnitudeMilli),
            StockChange(event.targetId, event.stockKey, -event.magnitudeMilli),
        )
        else -> emptyList()
    }

    /**
     * Who knows what, after this tick's events.
     *
     * Runs once every stock has settled, so the deviations it writes are against
     * the tick's final truth. For each stock-moving event it first computes reach
     * (how far the news travels) and surprise (how wrong the non-principals were)
     * from the deviation table as it stood, copies both onto the event, and only
     * then updates beliefs: principals and witnesses snap to accurate, rumor
     * reaches some of the rest, and everyone else's picture goes stale by exactly
     * the change they missed. Snapping an error of at least [REVEAL_FLOOR_MILLI]
     * is a discovery, and the largest few become [KIND_BELIEF_REVEAL] events.
     */
    private fun beliefStep(
        beliefs: List<Belief>,
        lies: List<LieIntent>,
        events: MutableList<WorldEvent>,
        stocks: Map<String, Stock>,
        living: List<SimEntity>,
        relations: List<Relation>,
        byId: Map<String, SimEntity>,
        tickSeed: Long,
        tick: Long,
    ): List<Belief> {
        val factions = living.filter { it.kind == "faction" }.sortedBy { it.id }
        if (factions.size < 2) return beliefs.filter { it.deviationMilli != 0L }

        // Local index only; iteration is always over sorted lists.
        val table = LinkedHashMap<String, Belief>(beliefs.size * 2)
        for (belief in beliefs) table[beliefKey(belief.observerId, belief.subjectId, belief.key)] = belief

        val reveals = mutableListOf<Reveal>()

        // Lies land first, so the walk below scores later events against a
        // world that has already heard the propaganda.
        for (lie in lies.sortedWith(compareBy({ it.firing.subject.id }, { it.effect.key }))) {
            val liar = lie.firing.subject
            for (faction in factions) {
                if (faction.id == liar.id) continue
                val key = beliefKey(faction.id, liar.id, lie.effect.key)
                val cap = deviationCap(liar.id, lie.effect.key, stocks)
                val standing = table[key]
                val updated = ((standing?.deviationMilli ?: 0L) + lie.effect.deltaMilli).coerceIn(-cap, cap)
                if (updated == 0L) {
                    table.remove(key)
                } else {
                    table[key] = Belief(
                        observerId = faction.id,
                        subjectId = liar.id,
                        key = lie.effect.key,
                        deviationMilli = updated,
                        provenance = Belief.PROVENANCE_LIED_TO,
                        sourceId = liar.id,
                        sinceTick = tick,
                        decayPerTickMilli = standing?.decayPerTickMilli ?: DEFAULT_BELIEF_DECAY_MILLI,
                    )
                }
            }
            val boast = if (lie.effect.deltaMilli >= 0L) "greater" else "smaller"
            events += WorldEvent(
                tick = tick,
                seq = 0,
                kind = KIND_LIE_TOLD,
                actorId = liar.id,
                ruleId = lie.firing.rule.id,
                magnitudeMilli = lie.effect.deltaMilli,
                stockKey = lie.effect.key,
                // Propaganda reaches everyone by construction; it surprises nobody.
                reachPermille = 1_000L,
                summary = "${liar.name} lets it be known its ${lie.effect.key} is $boast than it is.",
            )
        }

        for (index in events.indices) {
            val event = events[index]
            val changes = stockChanges(event)
            if (changes.isEmpty()) continue

            val principals = buildSet {
                add(event.actorId)
                if (event.targetId.isNotBlank()) add(event.targetId)
            }
            val audience = factions.filter { it.id !in principals }

            val knowers = mutableSetOf<String>()
            for (faction in audience) {
                if (watches(faction.id, principals, relations)) {
                    knowers += faction.id
                    continue
                }
                val draw = WorldRng.substream(
                    tickSeed,
                    "belief:${faction.id}:${event.kind}:${event.actorId}:${event.targetId}:${event.stockKey}",
                )
                if (WorldRng.bounded(draw, 1_000L) < RUMOR_CHANCE_PERMILLE) knowers += faction.id
            }

            // Pre-update truths. Surprise averages the audience's standing error
            // about what just moved; reach is how many of them now know it did.
            var surpriseSum = 0L
            var surpriseCount = 0
            for (faction in audience) {
                for (change in changes) {
                    val standing = table[beliefKey(faction.id, change.subjectId, change.key)]?.deviationMilli ?: 0L
                    surpriseSum += permille(abs(standing), deviationCap(change.subjectId, change.key, stocks))
                    surpriseCount++
                }
            }
            val surprise = if (surpriseCount == 0) 0L else (surpriseSum / surpriseCount).coerceIn(0L, 1_000L)
            val reach = if (audience.isEmpty()) 0L else knowers.size * 1_000L / audience.size
            events[index] = event.copy(reachPermille = reach, surprisePermille = surprise)

            for (change in changes) {
                for (faction in factions) {
                    val key = beliefKey(faction.id, change.subjectId, change.key)
                    val knows = faction.id in principals || faction.id in knowers
                    if (knows) {
                        val standing = table.remove(key) ?: continue
                        val cleared = abs(standing.deviationMilli)
                        if (cleared >= REVEAL_FLOOR_MILLI) {
                            reveals += Reveal(faction.id, change.subjectId, change.key, cleared, reach)
                        }
                    } else {
                        val cap = deviationCap(change.subjectId, change.key, stocks)
                        val standing = table[key]
                        val updated = ((standing?.deviationMilli ?: 0L) - change.deltaMilli)
                            .coerceIn(-cap, cap)
                        if (updated == 0L) {
                            table.remove(key)
                        } else {
                            table[key] = Belief(
                                observerId = faction.id,
                                subjectId = change.subjectId,
                                key = change.key,
                                deviationMilli = updated,
                                provenance = Belief.PROVENANCE_STALE,
                                sourceId = "",
                                sinceTick = tick,
                                decayPerTickMilli = standing?.decayPerTickMilli ?: DEFAULT_BELIEF_DECAY_MILLI,
                            )
                        }
                    }
                }
            }
        }

        val emitted = reveals
            .sortedWith(
                compareByDescending<Reveal> { it.clearedMilli }
                    .thenBy { it.observerId }.thenBy { it.subjectId }.thenBy { it.key },
            )
            .take(MAX_BELIEF_EVENTS_PER_TICK)
        for (reveal in emitted) {
            val observerName = byId[reveal.observerId]?.name ?: reveal.observerId
            val subjectName = byId[reveal.subjectId]?.name ?: reveal.subjectId
            events += WorldEvent(
                tick = tick,
                seq = 0,
                kind = KIND_BELIEF_REVEAL,
                actorId = reveal.observerId,
                targetId = reveal.subjectId,
                magnitudeMilli = reveal.clearedMilli,
                stockKey = reveal.key,
                // The discovery is as public as the event that forced it.
                reachPermille = reveal.reachPermille,
                surprisePermille = permille(reveal.clearedMilli, deviationCap(reveal.subjectId, reveal.key, stocks)),
                summary = "$observerName discovers the truth of $subjectName's ${reveal.key}.",
            )
        }

        return table.values.filter { it.deviationMilli != 0L }
    }

    /** Enemies are observed: adjacency in either direction, or a grievance this hot. */
    private fun watches(factionId: String, principals: Set<String>, relations: List<Relation>): Boolean =
        relations.any { relation ->
            when (relation.kind) {
                REL_ADJACENCY ->
                    (relation.fromId == factionId && relation.toId in principals) ||
                        (relation.toId == factionId && relation.fromId in principals)
                REL_GRIEVANCE ->
                    relation.fromId == factionId && relation.toId in principals &&
                        relation.magnitudeMilli >= WATCH_GRIEVANCE_MILLI
                else -> false
            }
        }

    /** A deviation is bounded by the stock's own capacity where it declares one. */
    private fun deviationCap(subjectId: String, key: String, stocks: Map<String, Stock>): Long {
        val capacity = stocks[stockKey(subjectId, key)]?.capacityMilli ?: 0L
        return if (capacity > 0L) capacity else DEVIATION_CAP_MILLI
    }

    private fun permille(value: Long, cap: Long): Long =
        if (cap <= 0L) 0L else (value * 1_000L / cap).coerceIn(0L, 1_000L)

    /**
     * Composite map keys, joined on [KEY_SEP].
     *
     * The separator was a raw NUL byte typed into the string literal rather
     * than the escape. It produces the same string and it made the source
     * hostile to every tool that reads it: `grep` reports this file as binary
     * and prints no matches, and when `PlayerView.kt` carried one inside
     * git's 8,000-byte binary-sniff window git rendered two commits of it as
     * `Bin 7501 -> 9497 bytes` — no diff, no blame, no merge. These five
     * escaped that only by sitting past the window, which one deletion above
     * them would have changed.
     */
    private fun beliefKey(observerId: String, subjectId: String, key: String): String =
        "$observerId$KEY_SEP$subjectId$KEY_SEP$key"

    private fun stockKey(entityId: String, key: String): String = "$entityId$KEY_SEP$key"

    private fun relationKey(relation: Relation): String =
        "${relation.fromId}$KEY_SEP${relation.toId}$KEY_SEP${relation.kind}"

    const val KIND_STOCK_SHIFT = "stock_shift"
    const val KIND_RELATION_SHIFT = "relation_shift"
    const val KIND_CLAIM_WON = "claim_won"
    const val KIND_QUIET_INTERVAL = "quiet_interval"
    const val KIND_BELIEF_REVEAL = "belief_reveal"
    const val KIND_LIE_TOLD = "lie_told"

    /**
     * A move made by somebody who is not a rule. Carries the [Effect] in
     * [WorldEvent.payload], and is the row [WorldReplayer] reads back to
     * replay a world that was played rather than only watched.
     */
    const val KIND_PLAYER_ACTION = "player_action"
    const val KIND_MOVED = "moved"

    /** A grievance at or above this watches its object: enemies are observed. */
    const val WATCH_GRIEVANCE_MILLI = 400L

    /** Chance per event, in permille, that a non-witness hears anyway. */
    const val RUMOR_CHANCE_PERMILLE = 250L

    /** Snapping an error at least this large is a discovery worth an event. */
    const val REVEAL_FLOOR_MILLI = 1_000L

    /** At most this many belief_reveal events per tick; the rest apply silently. */
    const val MAX_BELIEF_EVENTS_PER_TICK = 4

    /** Deviation bound for stocks that declare no capacity of their own. */
    const val DEVIATION_CAP_MILLI = 16_000L

    /** Decay for engine-formed beliefs; seeded rows may carry their own. */
    const val DEFAULT_BELIEF_DECAY_MILLI = 2L

    private const val REL_ADJACENCY = "adjacency"
    private const val REL_GRIEVANCE = "grievance"

    /** Stock consulted when weighting a contested claim. Absent means average. */
    const val STOCK_MIGHT = "might"
    private const val DEFAULT_MIGHT_MILLI = 1_000L
}
