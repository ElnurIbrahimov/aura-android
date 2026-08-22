package com.aura.creative.livingworld

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Separator for the composite keys of the local indices built inside a tick
 * and inside a projection: `entityId + key`, `observerId + subjectId + key`,
 * `fromId + toId + kind`.
 *
 * NUL because it cannot occur in an entity id, a stock key or a relation kind,
 * so two different tuples cannot collide onto one key. Written as the escape
 * rather than as a literal control character in the source: `WorldEngine.kt`
 * held five raw NUL bytes, which makes `grep` call the file binary and print
 * no matches at all, and `PlayerView.kt` held one inside git's 8,000-byte
 * binary-sniff window and diffed as `Bin 7501 -> 9497 bytes` for two commits.
 *
 * Shared rather than duplicated because `PlayerView` had meanwhile drifted to
 * a space, which is a character an id can legitimately contain. Nothing had
 * collided; nothing was stopping it either.
 *
 * Never persisted. Every index that uses it is local to one call.
 */
internal const val KEY_SEP = "\u0000"

/**
 * The state of one living world at one tick.
 *
 * Every collection is a **sorted list, never a map**. Kotlin does not specify
 * `HashMap` iteration order, and a tick that iterates a map produces different
 * results on different runs of the same input — which silently destroys replay,
 * rewind and fork. Lookups inside a tick build a local index and only ever call
 * `get` on it; iteration is always over these lists.
 *
 * Serialized whole into `living_worlds.stateJson`. Normalising this into Room
 * tables buys nothing until a query needs to filter inside it, which does not
 * happen until the point-of-view work.
 */
@Serializable
data class WorldState(
    val entities: List<SimEntity> = emptyList(),
    val stocks: List<Stock> = emptyList(),
    val relations: List<Relation> = emptyList(),
    val rules: List<Rule> = emptyList(),
    val beliefs: List<Belief> = emptyList(),
) {
    /** Re-sorts every collection into canonical order. Cheap, and the only way in. */
    fun canonical(): WorldState = WorldState(
        entities = entities.sortedBy { it.id },
        stocks = stocks.sortedWith(compareBy({ it.entityId }, { it.key })),
        relations = relations.sortedWith(compareBy({ it.fromId }, { it.toId }, { it.kind })),
        rules = rules.sortedWith(compareBy({ -it.priority }, { it.id })),
        beliefs = beliefs.sortedWith(compareBy({ it.observerId }, { it.subjectId }, { it.key })),
    )

    fun living(): List<SimEntity> = entities.filter { it.diedAtTick == 0L }
}

@Serializable
data class SimEntity(
    val id: String,
    /** `faction`, `character`, `location`, `institution`. */
    val kind: String,
    val name: String,
    /** Where this sits, for location containment and adjacency. Blank when unplaced. */
    val parentId: String = "",
    /** The `WorldCharacter`/`WorldFaction`/`WorldLocation` id it was seeded from. */
    val sourceBibleId: String = "",
    val bornAtTick: Long = 0L,
    /** 0 while extant. */
    val diedAtTick: Long = 0L,
)

/**
 * A scarce quantity held by one entity.
 *
 * [amountMilli] is a **scaled integer, not a float**. Splitting a conserved
 * quantity three ways in floating point and re-summing does not return the
 * original, and over tens of thousands of ticks that drift makes a pool that is
 * declared conserved measurably not conserved. Milliunits give three decimal
 * places, which is more than any world needs, with exact arithmetic.
 */
@Serializable
data class Stock(
    val entityId: String,
    val key: String,
    val amountMilli: Long,
    val capacityMilli: Long = 0L,
    /**
     * Applied every tick before rules run. **Must be 0 when [poolId] is set** —
     * a pooled stock changes only by transfer, which is what makes the pool's
     * total invariant. [WorldEngine] enforces this rather than trusting callers.
     */
    val flowPerTickMilli: Long = 0L,
    /** Non-blank means this stock is a share of a conserved pool of the same name. */
    val poolId: String = "",
    /** `conserved`, `renewable`, or `abstract`. Feeds notability scoring later. */
    val scarcity: String = SCARCITY_RENEWABLE,
) {
    companion object {
        const val SCARCITY_CONSERVED = "conserved"
        const val SCARCITY_RENEWABLE = "renewable"
        const val SCARCITY_ABSTRACT = "abstract"
    }
}

/** A directed, weighted tie. Grievance from A to B says nothing about B to A. */
@Serializable
data class Relation(
    val fromId: String,
    val toId: String,
    /** `grievance`, `debt`, `trade`, `adjacency`, `vassalage`. */
    val kind: String,
    val magnitudeMilli: Long,
    /** Pulled toward zero by this much per tick. Grudges fade unless fed. */
    val decayPerTickMilli: Long = 0L,
)

/**
 * One observer's error about one stock.
 *
 * **No row means accurate common knowledge.** The observer believes the truth;
 * a row exists only while somebody is wrong, so storage is proportional to
 * secrets and errors, never to entities x facts. `believed = actual +
 * deviationMilli`, and the engine drops rows whose deviation reaches zero.
 */
@Serializable
data class Belief(
    val observerId: String,
    val subjectId: String,
    /** Which stock the error is about ("might", "grain", "territory"). */
    val key: String,
    val deviationMilli: Long,
    /** Why the deviation exists: [PROVENANCE_STALE] or [PROVENANCE_LIED_TO]. */
    val provenance: String = PROVENANCE_STALE,
    /** Who planted it, for lied_to. Blank for stale. */
    val sourceId: String = "",
    /** Tick the error last formed or grew, so a reveal can say how long it stood. */
    val sinceTick: Long = 0L,
    /** Linear pull toward zero per tick: truth outs. Folds like relation decay. */
    val decayPerTickMilli: Long = 2L,
) {
    companion object {
        const val PROVENANCE_STALE = "stale"
        const val PROVENANCE_LIED_TO = "lied_to"
    }
}

/**
 * One executable law of the world.
 *
 * Evaluated once per living entity of [subjectKind], with that entity bound as
 * the subject. Prose rules from the world bible are not runnable and are not
 * converted silently — the author picks from templates and parameterises them.
 */
@Serializable
data class Rule(
    val id: String,
    val name: String,
    val subjectKind: String = "faction",
    /** Higher fires first. Ties break on id, so evaluation order is total. */
    val priority: Int = 0,
    val condition: Cond = Cond.Always,
    val effects: List<Effect> = emptyList(),
    /** Ticks that must pass before this rule may fire again for the same subject. */
    val cooldownTicks: Long = 0L,
    /** Per-subject last-fired ticks, sorted by entity id. Not a map — see [WorldState]. */
    val lastFired: List<FiredAt> = emptyList(),
    val enabled: Boolean = true,
)

@Serializable
data class FiredAt(val entityId: String, val tick: Long)

/** A condition over the subject's own stocks and its outgoing relations. */
@Serializable
sealed class Cond {
    @Serializable
    @SerialName("always")
    data object Always : Cond()

    @Serializable
    @SerialName("stock_below")
    data class StockBelow(val key: String, val thresholdMilli: Long) : Cond()

    @Serializable
    @SerialName("stock_above")
    data class StockAbove(val key: String, val thresholdMilli: Long) : Cond()

    /** True when the subject holds a relation of [kind] to anyone above [thresholdMilli]. */
    @Serializable
    @SerialName("relation_above")
    data class RelationAbove(val kind: String, val thresholdMilli: Long) : Cond()

    @Serializable
    @SerialName("and")
    data class And(val all: List<Cond>) : Cond()

    @Serializable
    @SerialName("or")
    data class Or(val any: List<Cond>) : Cond()

    @Serializable
    @SerialName("not")
    data class Not(val cond: Cond) : Cond()
}

/** What a rule does when it fires. */
@Serializable
sealed class Effect {
    /** Move one of the subject's own unpooled stocks. */
    @Serializable
    @SerialName("adjust_stock")
    data class AdjustStock(val key: String, val deltaMilli: Long) : Effect()

    /**
     * Adjust the subject's relation toward whoever it currently resents most in
     * [kind]. Resolving the target from state rather than naming it keeps rules
     * reusable across worlds.
     */
    @Serializable
    @SerialName("adjust_relation")
    data class AdjustRelation(val kind: String, val deltaMilli: Long) : Effect()

    /**
     * Claim [amountMilli] of a conserved pool from the current largest holder.
     *
     * Claims are the only way a pooled stock moves, and every claim is a
     * transfer, so the pool total cannot change. Contested claims are resolved
     * by a seeded draw weighted by what the *rival claimants believe* each
     * contender could bring to bear — belief arbitrates the draw, while the
     * transfer itself stays strictly real. Bluffed might wins ground until a
     * reveal snaps the audience accurate.
     */
    @Serializable
    @SerialName("claim_pool")
    data class ClaimPool(val poolId: String, val key: String, val amountMilli: Long) : Effect()

    /**
     * Propaganda: shift every other faction's belief about the subject's own
     * [key] by [deltaMilli]. Nothing real moves — only the deviation tables of
     * everyone listening, sourced to the subject, until truth outs by decay or
     * an event snaps the audience accurate.
     */
    @Serializable
    @SerialName("spread_lie")
    data class SpreadLie(val key: String, val deltaMilli: Long) : Effect()

    /**
     * Put the subject somewhere: set its `parentId` to [locationId].
     *
     * The only effect in the hierarchy that touches [SimEntity] rather than a
     * stock, a relation or a belief. It exists because presence is half the
     * game — what you can see is decided by where you stand — and there was
     * previously no way for anything to move.
     *
     * Rules may use it too; nothing here is player-only. A rule that moves its
     * subject is a migration, and works exactly the same way.
     */
    @Serializable
    @SerialName("move_to")
    data class MoveTo(val locationId: String) : Effect()

    /**
     * Look around: drop the subject's deviations about everything standing
     * where the subject stands.
     *
     * The counterweight to [SpreadLie]. A lie costs the liar nothing and pays
     * until truth outs, which by default takes as many ticks as the decay rate
     * allows — so without a way to *check*, propaganda would be strictly
     * dominant. Observing is that way, and its price is presence: you must be
     * there, which means being somewhere else is a real cost.
     *
     * Deliberately not a partial correction. Standing in the room and counting
     * is ground truth about that room; the fog is about distance, not about
     * perception.
     *
     * [forId] is who *learns*, which is not always who looked. Only
     * factions hold beliefs — [SpreadLie] plants deviations in "every other
     * faction's" table and nothing ever writes one for a character — so a
     * character who looks is the eyes of a house rather than a mind of its
     * own. That is the seat's two ids again: the character has presence, the
     * faction has knowledge, and this is the seam between them.
     *
     * Blank means the looker learns for itself, which is right for a faction
     * standing somewhere and harmlessly inert for a character.
     */
    @Serializable
    @SerialName("observe")
    data class Observe(val forId: String = "") : Effect()
}

/**
 * One effect, attributed to an actor who is not a rule.
 *
 * A player move is a god-edit on a world that must stay replayable, and
 * [WorldReplayer] states the constraint: such an edit "must land as a
 * replayable event kind, or fork-at-past breaks silently". So an action is not
 * applied to state directly — it is submitted to the tick that has not run
 * yet, journalled as a `player_action` event, and replayed from that journal.
 *
 * [actorId] is the entity the effect is applied *to*: [Effect.AdjustStock]
 * moves that entity's stock, [Effect.ClaimPool] claims on its behalf,
 * [Effect.MoveTo] moves it. Which is why the seat holds two ids: your character
 * moves and observes, your faction claims and lies.
 */
@Serializable
data class ActorEffect(val actorId: String, val effect: Effect)

/**
 * Something that happened. Produced by the engine, never by a model.
 *
 * [summary] is rendered from a deterministic template so the world is readable
 * with no LLM involved at all. Narration is attached later, to the few events
 * that earn it, and is never required for the timeline to make sense.
 */
@Serializable
data class WorldEvent(
    val tick: Long,
    val seq: Int,
    /** `stock_shift`, `relation_shift`, `claim_won`, `claim_lost`, `quiet_interval`, `belief_reveal`. */
    val kind: String,
    val actorId: String,
    val targetId: String = "",
    val ruleId: String = "",
    val magnitudeMilli: Long = 0L,
    /**
     * Which stock moved, when one did. Blank for events that are not about a
     * stock. Carried so notability can measure the change against the right
     * denominator — a number is only large or small relative to something.
     */
    val stockKey: String = "",
    /**
     * How far the news travelled and how wrong the world was about it, in
     * permille. The belief step computes both from the deviation table as it
     * stood *before* the event applied, so they are pre-event truths; they
     * feed notability and are folded into the persisted score.
     */
    val reachPermille: Long = 0L,
    val surprisePermille: Long = 0L,
    val summary: String,
    /**
     * The action this event journals. Set only on `player_action` rows, and
     * null on every event a rule produced.
     *
     * Events are already the replay journal — `quiet_interval` rows are how
     * folds are recorded — and a player move has to be replayable by the
     * same route or fork-at-past silently drops it. Carrying the [Effect]
     * itself rather than squeezing it into `actorId`/`targetId`/
     * `magnitudeMilli` means the decoder never has to guess, and a decoder
     * that guesses wrong replays a different world than the one played.
     */
    val payload: Effect? = null,
) {
    /**
     * Deterministic identity. A re-run of the same tick regenerates the same id,
     * so re-applying a tick is idempotent instead of duplicating rows.
     */
    fun idFor(worldId: String): String = "$worldId#$tick.$seq"
}

/** The result of advancing one tick: the new state and what happened in it. */
data class TickResult(
    val state: WorldState,
    val events: List<WorldEvent>,
)

/**
 * A compact, deterministic rendering of the world's standings for a prompt.
 *
 * Pure — no model, no I/O, no clock — and small by construction: one line per
 * living faction. The caller's section cap is the budget; this stays well
 * under it at bible scale.
 */
object WorldStateBrief {
    fun render(state: WorldState): String = buildString {
        val living = state.living().filter { it.kind == "faction" }
        val byId = living.associateBy { it.id }
        for (faction in living) {
            val stocks = state.stocks
                .filter { it.entityId == faction.id }
                .joinToString(", ") { "${it.key} ${it.amountMilli / 1_000}" }
            if (stocks.isEmpty()) continue
            append("- ${faction.name}: ")
            append(stocks)
            val resented = state.relations
                .filter { it.fromId == faction.id && it.kind == "grievance" && it.magnitudeMilli > 0L }
                .sortedWith(compareByDescending<Relation> { it.magnitudeMilli }.thenBy { it.toId })
                .firstOrNull()
            if (resented != null) {
                append("; resents ${byId[resented.toId]?.name ?: resented.toId}")
            }
            appendLine()
        }
    }
}
