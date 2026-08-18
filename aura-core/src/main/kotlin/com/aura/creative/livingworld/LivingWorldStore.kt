package com.aura.creative.livingworld

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable state for living worlds.
 *
 * The world's state is stored as one JSON blob and the events as rows, which is
 * the split that matters: the state is only ever read whole, and the events are
 * only ever read as a bounded, ordered page.
 */
@Singleton
class LivingWorldStore @Inject constructor(
    private val worldDao: LivingWorldDao,
    private val eventDao: LivingEventDao,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(state: WorldState): String =
        json.encodeToString(WorldState.serializer(), state.canonical())

    fun decode(stateJson: String): WorldState =
        runCatching { json.decodeFromString(WorldState.serializer(), stateJson) }
            .getOrElse {
                android.util.Log.w("LivingWorldStore", "world state decode failed: ${it.message}", it)
                WorldState()
            }

    suspend fun create(
        projectId: String,
        branchId: String,
        state: WorldState,
        worldEpochMs: Long,
        rootSeed: Long = java.security.SecureRandom().nextLong(),
    ): LivingWorldEntity {
        val encoded = encode(state)
        val world = LivingWorldEntity(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            branchId = branchId,
            rootSeed = rootSeed,
            worldEpochMs = worldEpochMs,
            currentTick = 0L,
            stateJson = encoded,
            // Written once, never updated: the exact state fork-at-past
            // replays from. commitTick touches only stateJson and the tick.
            genesisJson = encoded,
        )
        worldDao.upsert(world)
        return world
    }

    suspend fun byId(worldId: String): LivingWorldEntity? = worldDao.byId(worldId)

    suspend fun forProject(projectId: String): LivingWorldEntity? =
        worldDao.forProject(projectId).firstOrNull()

    fun observeForProject(projectId: String): Flow<LivingWorldEntity?> =
        worldDao.observeForProject(projectId)

    fun observeEvents(worldId: String, limit: Int = DEFAULT_EVENT_PAGE): Flow<List<LivingEventEntity>> =
        eventDao.observeRecent(worldId, limit)

    suspend fun running(): List<LivingWorldEntity> = worldDao.running()

    suspend fun setStatus(worldId: String, status: String) {
        worldDao.updateStatus(worldId, status, System.currentTimeMillis())
    }

    /**
     * Persist a batch of advanced ticks.
     *
     * **Events are written before the state advance, deliberately.** If the
     * process dies between the two, the next run recomputes exactly the same
     * ticks — the engine is deterministic — and the event ids are derived from
     * `worldId#tick.seq`, so the upsert replaces those rows instead of
     * duplicating them. Committing the state first would instead lose the
     * events for good, because the ticks that produced them would never be
     * re-run.
     */
    suspend fun commitTicks(
        world: LivingWorldEntity,
        newState: WorldState,
        throughTick: Long,
        events: List<ScoredEvent>,
        now: Long,
    ) {
        if (events.isNotEmpty()) {
            eventDao.upsertAll(events.map { it.event.toEntity(world, now, it.notability) })
        }
        worldDao.commitTick(world.id, throughTick, encode(newState), now)
    }

    suspend fun topUnnarrated(worldId: String, floor: Double, limit: Int): List<LivingEventEntity> =
        eventDao.topUnnarrated(worldId, floor, limit)

    suspend fun narratedSince(worldId: String, since: Long): Int = eventDao.narratedSince(worldId, since)

    suspend fun attachNarration(eventId: String, narration: String, now: Long) {
        eventDao.attachNarration(eventId, narration, now)
    }

    suspend fun recentEvents(worldId: String, limit: Int = DEFAULT_EVENT_PAGE): List<LivingEventEntity> =
        eventDao.recent(worldId, limit)

    suspend fun eventCount(worldId: String): Int = eventDao.count(worldId)

    suspend fun eventById(eventId: String): LivingEventEntity? = eventDao.byId(eventId)

    /**
     * The sixth DecayWorker sweep, above the decayEnabled gate — retention is
     * not a feature the user opted into. Noise first: sub-floor, unnarrated
     * rows older than 30 real days of history (720 hourly ticks). Then the
     * hard cap, oldest-first regardless of notability, when a world has grown
     * past [HARD_CAP_ROWS] — an emergency valve, not policy, and the caller
     * `trimBefore` shipped with and never had until now.
     */
    suspend fun compactAll() {
        for (world in worldDao.all()) {
            val horizon = world.currentTick - COMPACTION_HORIZON_TICKS
            if (horizon > 0L) {
                eventDao.trimNoiseBefore(world.id, horizon, NotabilityScorer.DEFAULT_FLOOR)
            }
            if (eventDao.count(world.id) > HARD_CAP_ROWS) {
                eventDao.tickAtOffset(world.id, HARD_CAP_ROWS)?.let { overflowTick ->
                    eventDao.trimBefore(world.id, overflowTick)
                }
            }
        }
    }

    companion object {
        const val DEFAULT_EVENT_PAGE = 200

        /** 30 real days of hourly ticks — the house retention window. */
        const val COMPACTION_HORIZON_TICKS = 720L

        /** Rows per world past which the emergency valve opens. */
        const val HARD_CAP_ROWS = 24_000
    }
}

internal fun WorldEvent.toEntity(world: LivingWorldEntity, now: Long, notability: Double = 0.0) = LivingEventEntity(
    id = idFor(world.id),
    worldId = world.id,
    branchId = world.branchId,
    tickIndex = tick,
    seq = seq,
    kind = kind,
    actorId = actorId,
    targetId = targetId,
    ruleId = ruleId,
    magnitudeMilli = magnitudeMilli,
    summary = summary,
    notability = notability,
    createdAt = now,
)
