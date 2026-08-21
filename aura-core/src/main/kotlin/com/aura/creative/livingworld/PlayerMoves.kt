package com.aura.creative.livingworld

/**
 * What the seat can do this tick, and why it cannot do the rest.
 *
 * ## The one rule this file exists to keep
 *
 * **Legality is computed against belief, never against truth.** If the
 * interface greyed out a claim because the pool is *actually* empty, it would
 * have told you something your character has no way of knowing, and the fog
 * would be over — every disabled button a free reading of ground truth.
 *
 * That rule is enforced by the signature rather than by discipline: [available]
 * takes a [PlayerView.View] and nothing else. It *cannot* consult [WorldState],
 * because it is never given one. A future edit that wants truth here has to add
 * a parameter, which is a conversation rather than an accident.
 *
 * So a move sized against a lie is offered, and it resolves against truth, and
 * finding out which you were living in is the game.
 *
 * ## Six verbs
 *
 * Four are what the faction can do, because the faction holds the stocks:
 * [CLAIM], [BLUFF], [MEND], [SPEND]. Two are what the character can do, because
 * the character is the one standing somewhere: [TRAVEL], [LOOK].
 *
 * That split is the tension the seat was built for. Power and presence are
 * separate, and being in the wrong place means commanding blind.
 */
object PlayerMoves {

    const val CLAIM = "claim"
    const val BLUFF = "bluff"
    const val MEND = "mend"
    const val SPEND = "spend"
    const val TRAVEL = "travel"
    const val LOOK = "look"

    /**
     * One offered move.
     *
     * [effect] is null exactly when the move is illegal, so a caller cannot
     * submit one by forgetting to check a boolean. [blockedBy] is the reason,
     * and every reason here is something the observer could work out for
     * themselves — where they are, what they hold, what they have been told.
     */
    data class Move(
        val verb: String,
        val label: String,
        val detail: String,
        val effect: ActorEffect?,
        val blockedBy: String = "",
    ) {
        val legal: Boolean get() = effect != null
    }

    /**
     * Every move this seat could submit into the next tick.
     *
     * Illegal moves are returned rather than filtered out: a verb that vanishes
     * teaches nothing, and "you are not standing near anyone" is a fact the
     * player is entitled to and can act on by travelling.
     */
    fun available(view: PlayerView.View): List<Move> {
        val self = view.self
        val faction = view.faction
        if (self == null || faction == null) {
            return listOf(
                Move(
                    verb = LOOK,
                    label = "Look around",
                    detail = "",
                    effect = null,
                    blockedBy = "You have no seat in this world.",
                ),
            )
        }

        val moves = mutableListOf<Move>()
        moves += claims(view, faction)
        moves += bluff(faction)
        moves += mends(view, faction)
        moves += spends(faction)
        moves += travels(view, self)
        moves += look(view, self)
        return moves
    }

    /**
     * One claim per conserved pool, sized off what the largest rival is
     * *believed* to hold.
     *
     * The size is where the fog bites hardest. Believe a rival is five times
     * their real strength and you will offer for five times what is there; the
     * engine transfers what exists and the rest of the ambition evaporates.
     */
    private fun claims(view: PlayerView.View, faction: PlayerView.SeenEntity): List<Move> {
        val pools = LinkedHashMap<String, Long>()
        val keys = LinkedHashMap<String, String>()
        for (other in view.others) {
            for (stock in other.stocks) {
                if (stock.poolId.isBlank()) continue
                val standing = pools[stock.poolId] ?: 0L
                if (stock.amountMilli > standing) {
                    pools[stock.poolId] = stock.amountMilli
                    keys[stock.poolId] = stock.key
                }
            }
        }
        // Own holdings name a pool even when no rival is believed to hold any,
        // so the verb is still offered — with an honest reason — on a pool the
        // player has already swept.
        for (stock in faction.stocks) {
            if (stock.poolId.isBlank()) continue
            pools.putIfAbsent(stock.poolId, 0L)
            keys.putIfAbsent(stock.poolId, stock.key)
        }

        return pools.keys.sorted().map { poolId ->
            val believedHeld = pools[poolId] ?: 0L
            val key = keys[poolId].orEmpty()
            val amount = believedHeld / CLAIM_FRACTION
            Move(
                verb = CLAIM,
                label = "Press a claim on $key",
                detail = if (amount > 0L) "Move for ${milli(amount)} of the $poolId." else "",
                effect = if (amount > 0L) {
                    ActorEffect(faction.id, Effect.ClaimPool(poolId, key, amount))
                } else {
                    null
                },
                blockedBy = if (amount > 0L) "" else "Nobody you know of holds enough $key to be worth taking.",
            )
        }
    }

    /** Propaganda about your own strength. Always available, never free. */
    private fun bluff(faction: PlayerView.SeenEntity): List<Move> {
        val loudest = faction.stocks.maxByOrNull { it.amountMilli } ?: return listOf(
            Move(
                verb = BLUFF,
                label = "Put it about that you are stronger",
                detail = "",
                effect = null,
                blockedBy = "You hold nothing to exaggerate.",
            ),
        )
        val inflation = (loudest.amountMilli * BLUFF_PERMILLE / 1_000L).coerceAtLeast(1L)
        return listOf(
            Move(
                verb = BLUFF,
                label = "Put it about that you are stronger",
                detail = "Let it be believed you hold ${milli(loudest.amountMilli + inflation)} " +
                    "${loudest.key}, not ${milli(loudest.amountMilli)}.",
                effect = ActorEffect(faction.id, Effect.SpreadLie(loudest.key, inflation)),
            ),
        )
    }

    /**
     * One per kind of tie you hold, mending it.
     *
     * [Effect.AdjustRelation] resolves its target from state — it moves the tie
     * toward whoever the subject currently resents most — so this is "make
     * peace with your worst enemy" rather than a move against a named house.
     * Which house that turns out to be is not necessarily who you think.
     */
    private fun mends(view: PlayerView.View, faction: PlayerView.SeenEntity): List<Move> {
        val kinds = view.relations.filter { it.fromId == faction.id }.map { it.kind }.distinct().sorted()
        if (kinds.isEmpty()) {
            return listOf(
                Move(
                    verb = MEND,
                    label = "Send an envoy",
                    detail = "",
                    effect = null,
                    blockedBy = "You hold no ties to mend.",
                ),
            )
        }
        return kinds.map { kind ->
            Move(
                verb = MEND,
                label = "Send an envoy to settle a $kind",
                detail = "Whoever you have wronged worst hears you out.",
                effect = ActorEffect(faction.id, Effect.AdjustRelation(kind, -MEND_MILLI)),
            )
        }
    }

    /** Spend down an unpooled holding. Pooled stock moves only by claim. */
    private fun spends(faction: PlayerView.SeenEntity): List<Move> {
        val spendable = faction.stocks.filter { it.poolId.isBlank() }.sortedBy { it.key }
        if (spendable.isEmpty()) {
            return listOf(
                Move(
                    verb = SPEND,
                    label = "Open the stores",
                    detail = "",
                    effect = null,
                    blockedBy = "Everything you hold is contested ground, and that moves only by force.",
                ),
            )
        }
        return spendable.map { stock ->
            val amount = stock.amountMilli * SPEND_PERMILLE / 1_000L
            Move(
                verb = SPEND,
                label = "Spend ${stock.key}",
                detail = if (amount > 0L) "Put ${milli(amount)} of your ${stock.key} to work." else "",
                effect = if (amount > 0L) {
                    ActorEffect(faction.id, Effect.AdjustStock(stock.key, -amount))
                } else {
                    null
                },
                blockedBy = if (amount > 0L) "" else "Your ${stock.key} is spent.",
            )
        }
    }

    /** One per place that is not the place you are standing in. */
    private fun travels(view: PlayerView.View, self: PlayerView.SeenEntity): List<Move> {
        val places = view.others
            .filter { it.kind == KIND_LOCATION && it.id != view.locationId }
            .sortedBy { it.id }
        if (places.isEmpty()) {
            return listOf(
                Move(
                    verb = TRAVEL,
                    label = "Ride out",
                    detail = "",
                    effect = null,
                    blockedBy = "There is nowhere else you know of.",
                ),
            )
        }
        return places.map { place ->
            Move(
                verb = TRAVEL,
                label = "Ride to ${place.name}",
                detail = "A day on the road, and the day happens without you.",
                effect = ActorEffect(self.id, Effect.MoveTo(place.id)),
            )
        }
    }

    /**
     * Look around, which is the only thing that makes a lie cost anything.
     *
     * Gated on co-location, which [PlayerView.SeenEntity.canObserve] reports
     * and which is knowable by looking out of a window. It says nothing about
     * whether what you already believe is any good — that is the one thing the
     * fog must never hand over.
     */
    private fun look(view: PlayerView.View, self: PlayerView.SeenEntity): List<Move> {
        val neighbours = view.others.count { it.canObserve }
        return listOf(
            Move(
                verb = LOOK,
                label = "Take the measure of the room",
                detail = if (neighbours > 0) "See for yourself what is here." else "",
                // Your character looks; your house is what learns.
                effect = if (neighbours > 0) {
                    ActorEffect(self.id, Effect.Observe(forId = view.factionId))
                } else {
                    null
                },
                blockedBy = if (neighbours > 0) {
                    ""
                } else if (view.locationId.isBlank()) {
                    "You are nowhere in particular."
                } else {
                    "There is nobody here but you."
                },
            ),
        )
    }

    private fun milli(value: Long): String {
        val whole = value / 1_000L
        val fraction = (value % 1_000L) / 100L
        return if (fraction == 0L) whole.toString() else "$whole.$fraction"
    }

    private const val KIND_LOCATION = "location"

    /** A claim opens for a quarter of what the leader is believed to hold. */
    private const val CLAIM_FRACTION = 4L

    /** A bluff inflates by a quarter. Large enough to sway a draw, small enough to be plausible. */
    private const val BLUFF_PERMILLE = 250L

    /** Spending puts a tenth of a holding to work. */
    private const val SPEND_PERMILLE = 100L

    /** How far an envoy moves a grievance. */
    private const val MEND_MILLI = 250L
}
