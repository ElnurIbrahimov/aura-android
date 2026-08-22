package com.aura.creative.livingworld

/**
 * The world as one observer believes it to be.
 *
 * [WorldState] is ground truth. Nothing in the app was ever allowed to show a
 * player ground truth before, because there was no player — the living-world
 * surface is a spectator view and is omniscient by construction. A seat inside
 * the world needs the opposite: a projection that can be *wrong*, in exactly
 * the ways the belief table says this observer is wrong.
 *
 * Pure, deterministic and free of Android, like [WorldEngine], [TimelineDiff]
 * and [NotabilityScorer]. It reads state and returns a value; it never ticks,
 * never persists, and never calls a model.
 *
 * ## What is fogged, and what is not
 *
 * Existence is common knowledge: you know the great houses are out there. What
 * you can be wrong about is **quantity** — how much anyone holds. That is
 * precisely what [Belief] models, and it is the whole game, because
 * [Effect.ClaimPool] resolves contested claims on what rivals *believe* each
 * contender can bring to bear.
 *
 * Your own character and your own faction read true. You know your own strength;
 * being deceived about yourself is a different feature and is not this one.
 *
 * ## Whose beliefs these are
 *
 * The faction's, not the character's. Only factions hold beliefs in this
 * engine — [Effect.SpreadLie] plants deviations in "every other faction's"
 * table and nothing ever writes one for a character — so reading the
 * character's table would mean reading a table that is always empty, which
 * renders as a player who can never be wrong about anything.
 *
 * Which is also the truer reading. A house has scouts, envoys and rumours; a
 * person has eyes. [observerId] decides what you are *near*; [factionId]
 * decides what you *know*, and [Effect.Observe] is the seam that lets the
 * first correct the second.
 *
 * ## The leak rule
 *
 * **Nothing about a deviation is exposed — not its size, not its existence, not
 * its provenance.** A view that renders "your information here is stale" has
 * told the observer they are wrong, which is the one thing the fog exists to
 * hide. `provenance = lied_to` leaking would be worse still: it would reveal
 * both that a lie exists and that someone planted it.
 *
 * So a believed stock is a number and nothing else. Any uncertainty the UI wants
 * to show has to come from something the observer legitimately knows — when they
 * last stood in a place, say — never from this projection.
 *
 * [SeenEntity.canObserve] is safe by that rule: it reports co-location, which
 * the observer can see out of a window, and says nothing about whether their
 * information is any good.
 *
 * ## The event log is part of the fog
 *
 * The event stream is the loudest leak in the whole design, and the least
 * obvious one. A `lie_told` row reads "Bramwatch lets it be known its might is
 * greater **than it is**" — rendering that to the player ends the fog in one
 * line, no matter how careful the numbers above it were. [witnessed] is the
 * filter, and any surface showing a player their world's history has to run
 * events through it.
 */
object PlayerView {

    /**
     * One holding as the observer understands it.
     *
     * [amountMilli] is believed, not actual, for everyone but the observer's own
     * side. There is deliberately no `isBelieved` flag — see the leak rule.
     */
    data class SeenStock(
        val key: String,
        val amountMilli: Long,
        val capacityMilli: Long,
        val poolId: String,
        val scarcity: String,
    )

    /** One entity as the observer understands it. */
    data class SeenEntity(
        val id: String,
        val kind: String,
        val name: String,
        val parentId: String,
        val stocks: List<SeenStock>,
        /**
         * True when this entity stands where the observer's character stands.
         *
         * Reports geography, not information quality: it is what makes
         * [Effect.Observe] legal, and it is knowable by looking around.
         */
        val canObserve: Boolean,
    )

    /**
     * Everything one seat can see at one tick.
     *
     * [self] and [faction] are true. [others] are believed.
     */
    data class View(
        val observerId: String,
        val factionId: String,
        /** Where the observer's character stands. Blank when unplaced. */
        val locationId: String,
        val self: SeenEntity?,
        val faction: SeenEntity?,
        val others: List<SeenEntity>,
        /** The observer's own outgoing ties. Other people's ties are not visible. */
        val relations: List<Relation>,
    )

    /**
     * Project [state] through the eyes of [observerId], who commands [factionId].
     *
     * Both ids are looked up rather than assumed present: a world whose seat was
     * never assigned, or whose character has died, yields a view with null
     * [View.self] rather than throwing. Callers render "you are not in this
     * world" from that; they do not get to see truth as a fallback.
     */
    fun of(state: WorldState, observerId: String, factionId: String): View {
        val canonical = state.canonical()
        val living = canonical.living()

        // Local index, never iterated — the same discipline WorldState documents:
        // map iteration order is unspecified and would make this projection
        // differ between runs of identical input.
        val stocksByEntity = LinkedHashMap<String, MutableList<Stock>>(living.size * 2)
        for (stock in canonical.stocks) {
            stocksByEntity.getOrPut(stock.entityId) { mutableListOf() }.add(stock)
        }

        val deviations = LinkedHashMap<String, Long>(canonical.beliefs.size * 2)
        for (belief in canonical.beliefs) {
            if (belief.observerId != factionId) continue
            deviations[deviationKey(belief.subjectId, belief.key)] = belief.deviationMilli
        }

        val self = living.firstOrNull { it.id == observerId }
        val locationId = self?.parentId.orEmpty()

        fun seen(entity: SimEntity, trueValues: Boolean): SeenEntity {
            val held = stocksByEntity[entity.id].orEmpty()
            return SeenEntity(
                id = entity.id,
                kind = entity.kind,
                name = entity.name,
                parentId = entity.parentId,
                stocks = held.map { stock ->
                    val deviation =
                        if (trueValues) 0L
                        else deviations[deviationKey(entity.id, stock.key)] ?: 0L
                    SeenStock(
                        key = stock.key,
                        amountMilli = stock.amountMilli + deviation,
                        capacityMilli = stock.capacityMilli,
                        poolId = stock.poolId,
                        scarcity = stock.scarcity,
                    )
                },
                // Co-location is only meaningful for somewhere the observer
                // actually is; an unplaced character stands nowhere and can
                // observe nothing.
                canObserve = locationId.isNotBlank() &&
                    entity.parentId == locationId &&
                    entity.id != observerId,
            )
        }

        return View(
            observerId = observerId,
            factionId = factionId,
            locationId = locationId,
            self = self?.let { seen(it, trueValues = true) },
            faction = living.firstOrNull { it.id == factionId }?.let { seen(it, trueValues = true) },
            others = living
                .filter { it.id != observerId && it.id != factionId }
                .map { seen(it, trueValues = false) },
            relations = canonical.relations.filter { it.fromId == factionId || it.fromId == observerId },
        )
    }

    /**
     * Whether this seat could have witnessed an event.
     *
     * Two kinds are special, and both for the same reason: they are events
     * *about* the fog, so showing one is showing the player the shape of
     * their own ignorance.
     *
     * `lie_told` says in as many words that a claim is false. It reaches
     * only the liar — which is not a rule about earshot but about what the
     * row means: propaganda that announced itself would not be propaganda.
     *
     * `belief_reveal` says somebody discovered the truth of something, which
     * implies an untruth was standing. Your own discoveries are yours to
     * read; a rival discovering something tells you they had been wrong, and
     * possibly about you.
     *
     * Everything else is witnessed if you or your house did it, if it was
     * done to you, or if it happened where you are standing. `quiet_interval`
     * has no actor and is time passing rather than news, so it always shows.
     */
    fun witnessed(view: View, kind: String, actorId: String, targetId: String): Boolean {
        if (kind == WorldEngine.KIND_QUIET_INTERVAL) return true
        val mine = actorId == view.observerId || actorId == view.factionId
        return when (kind) {
            WorldEngine.KIND_LIE_TOLD, WorldEngine.KIND_BELIEF_REVEAL -> mine
            else -> mine ||
                targetId == view.observerId ||
                targetId == view.factionId ||
                view.others.any { it.id == actorId && it.canObserve }
        }
    }

    /**
     * Same separator the engine's own indices use, for the same reason.
     *
     * This separated on a space, which an entity id can legitimately contain —
     * so `("house a", "coin")` and `("house", "a coin")` were one key. Nothing
     * had collided, because ids are UUIDs by default and stock keys are
     * constants; nothing was stopping it once an author supplied their own id.
     */
    private fun deviationKey(subjectId: String, key: String): String =
        "$subjectId$KEY_SEP$key"
}
