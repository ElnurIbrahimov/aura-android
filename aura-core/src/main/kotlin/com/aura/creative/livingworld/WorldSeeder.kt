package com.aura.creative.livingworld

import com.aura.creative.WorldBible
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starting quantities for a new world.
 *
 * These exist because **the world bible contains no numbers at all** — no
 * troops, no treasuries, no capacities anywhere in the type. The seeder cannot
 * infer them, and inventing them silently would hand the author a world whose
 * initial conditions they never chose and cannot explain. So they are asked
 * for, with defaults that produce a world which visibly moves.
 */
data class WorldSetup(
    /** Total of the conserved territory pool, split across factions. */
    val territoryTotalMilli: Long = 12_000L,
    val startingGrainMilli: Long = 5_000L,
    val grainCapacityMilli: Long = 10_000L,
    /** Negative: stores draw down between harvests, which is what creates pressure. */
    val grainFlowPerTickMilli: Long = -30L,
    val startingCoinMilli: Long = 3_000L,
    val coinFlowPerTickMilli: Long = 12L,
    val startingMightMilli: Long = 1_000L,
    /** Grievance seeded on each declared rivalry. */
    val rivalGrievanceMilli: Long = 600L,
    val grievanceDecayPerTickMilli: Long = 1L,
)

/**
 * Compiles a [WorldBible] into a runnable [WorldState].
 *
 * Entity ids are the bible's own ids, which are stable UUIDs already on
 * `WorldCharacter`/`WorldLocation`/`WorldFaction`. That is what lets a re-seed
 * after the author edits the bible be a **merge** rather than a wipe — a world
 * that resets its history every time somebody adds a character would be worse
 * than no world.
 */
@Singleton
class WorldSeeder @Inject constructor() {

    fun seed(bible: WorldBible, setup: WorldSetup = WorldSetup()): WorldState {
        val factions = bible.factions.sortedBy { it.id }
        val entities = mutableListOf<SimEntity>()
        val stocks = mutableListOf<Stock>()
        val relations = mutableListOf<Relation>()

        for (faction in factions) {
            entities += SimEntity(
                id = faction.id,
                kind = KIND_FACTION,
                name = faction.name,
                sourceBibleId = faction.id,
            )
        }
        for (location in bible.locations.sortedBy { it.id }) {
            entities += SimEntity(
                id = location.id,
                kind = KIND_LOCATION,
                name = location.name,
                sourceBibleId = location.id,
            )
        }
        for (character in bible.characters.sortedBy { it.id }) {
            entities += SimEntity(
                id = character.id,
                kind = KIND_CHARACTER,
                name = character.name,
                sourceBibleId = character.id,
            )
        }

        // Territory is a conserved pool: split evenly, remainder to the lowest
        // id. An even split with a deterministic remainder rule keeps the pool
        // total exact — integer division alone would quietly lose the remainder
        // and the pool would start out not summing to what it claims.
        if (factions.isNotEmpty()) {
            val share = setup.territoryTotalMilli / factions.size
            var remainder = setup.territoryTotalMilli - share * factions.size
            for (faction in factions) {
                val extra = if (remainder > 0L) 1L else 0L
                remainder -= extra
                stocks += Stock(
                    entityId = faction.id,
                    key = STOCK_TERRITORY,
                    amountMilli = share + extra,
                    poolId = POOL_TERRITORY,
                    scarcity = Stock.SCARCITY_CONSERVED,
                )
                stocks += Stock(
                    entityId = faction.id,
                    key = STOCK_GRAIN,
                    amountMilli = setup.startingGrainMilli,
                    capacityMilli = setup.grainCapacityMilli,
                    flowPerTickMilli = setup.grainFlowPerTickMilli,
                    scarcity = Stock.SCARCITY_RENEWABLE,
                )
                stocks += Stock(
                    entityId = faction.id,
                    key = STOCK_COIN,
                    amountMilli = setup.startingCoinMilli,
                    flowPerTickMilli = setup.coinFlowPerTickMilli,
                    scarcity = Stock.SCARCITY_RENEWABLE,
                )
                stocks += Stock(
                    entityId = faction.id,
                    key = WorldEngine.STOCK_MIGHT,
                    amountMilli = setup.startingMightMilli,
                    scarcity = Stock.SCARCITY_ABSTRACT,
                )
            }
        }

        // Declared rivalries become directed grievance. `rivals` is a list of
        // names, not ids, so it is matched case-insensitively against faction
        // names; an unmatched rival is skipped rather than inventing a faction.
        val byName = factions.associateBy { it.name.trim().lowercase() }
        for (faction in factions) {
            for (rival in faction.rivals.sorted()) {
                val target = byName[rival.trim().lowercase()] ?: continue
                if (target.id == faction.id) continue
                relations += Relation(
                    fromId = faction.id,
                    toId = target.id,
                    kind = REL_GRIEVANCE,
                    magnitudeMilli = setup.rivalGrievanceMilli,
                    decayPerTickMilli = setup.grievanceDecayPerTickMilli,
                )
            }
        }

        return WorldState(
            entities = entities,
            stocks = stocks,
            relations = relations,
            rules = RuleTemplates.defaults(setup),
        ).canonical()
    }

    /** Whether a bible has enough in it to make a world worth running. */
    fun canSeed(bible: WorldBible): Boolean = bible.factions.size >= 2

    companion object {
        const val KIND_FACTION = "faction"
        const val KIND_LOCATION = "location"
        const val KIND_CHARACTER = "character"

        const val STOCK_TERRITORY = "territory"
        const val STOCK_GRAIN = "grain"
        const val STOCK_COIN = "coin"
        const val POOL_TERRITORY = "territory"
        const val REL_GRIEVANCE = "grievance"
    }
}

/**
 * The built-in laws a seeded world starts with.
 *
 * Prose rules from the world bible are **not** converted into these
 * automatically. `WorldRule.description` is free text and a mis-parsed rule
 * would run silently forever; the author picks and parameterises instead.
 */
object RuleTemplates {

    fun defaults(setup: WorldSetup): List<Rule> = listOf(
        Rule(
            id = "famine_drives_expansion",
            name = "Famine drives expansion",
            subjectKind = WorldSeeder.KIND_FACTION,
            priority = 10,
            condition = Cond.StockBelow(WorldSeeder.STOCK_GRAIN, setup.grainCapacityMilli / 5),
            effects = listOf(
                // Scaled to the map, not a fixed number. A constant 400 was
                // 3% of a small world and 0.3% of a large one, so on anything
                // the author sized generously a conquest moved a rounding
                // error and nothing ever mattered.
                Effect.ClaimPool(
                    WorldSeeder.POOL_TERRITORY,
                    WorldSeeder.STOCK_TERRITORY,
                    (setup.territoryTotalMilli / 20).coerceAtLeast(1L),
                ),
                Effect.AdjustStock(WorldSeeder.STOCK_GRAIN, setup.grainCapacityMilli / 4),
            ),
            cooldownTicks = 30,
        ),
        Rule(
            id = "grudges_harden",
            name = "Grudges harden",
            subjectKind = WorldSeeder.KIND_FACTION,
            priority = 5,
            condition = Cond.RelationAbove(WorldSeeder.REL_GRIEVANCE, setup.rivalGrievanceMilli),
            effects = listOf(Effect.AdjustRelation(WorldSeeder.REL_GRIEVANCE, 120)),
            cooldownTicks = 15,
        ),
        Rule(
            id = "prosperity_pays",
            name = "Prosperity pays",
            subjectKind = WorldSeeder.KIND_FACTION,
            priority = 3,
            condition = Cond.StockAbove(WorldSeeder.STOCK_TERRITORY, setup.territoryTotalMilli / 2),
            effects = listOf(Effect.AdjustStock(WorldEngine.STOCK_MIGHT, 60)),
            cooldownTicks = 40,
        ),
        Rule(
            id = "overreach_costs",
            name = "Overreach costs",
            subjectKind = WorldSeeder.KIND_FACTION,
            priority = 4,
            condition = Cond.StockAbove(WorldSeeder.STOCK_TERRITORY, (setup.territoryTotalMilli * 2) / 3),
            effects = listOf(Effect.AdjustStock(WorldSeeder.STOCK_COIN, -400)),
            cooldownTicks = 25,
        ),
    )
}
