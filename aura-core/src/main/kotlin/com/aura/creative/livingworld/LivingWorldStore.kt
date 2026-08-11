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
        val world = LivingWorldEntity(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            branchId = branchId,
            rootSeed = rootSeed,
            worldEpochMs = worldEpochMs,
            currentTick = 0L,
            stateJson = encode(state),
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
        events: List<WorldEvent>,
        now: Long,
    ) {
        if (events.isNotEmpty()) {
            eventDao.upsertAll(events.map { it.toEntity(world, now) })
        }
        worldDao.commitTick(world.id, throughTick, encode(newState), now)
    }

    suspend fun recentEvents(worldId: String, limit: Int = DEFAULT_EVENT_PAGE): List<LivingEventEntity> =
        eventDao.recent(worldId, limit)

    suspend fun eventCount(worldId: String): Int = eventDao.count(worldId)

    companion object {
        const val DEFAULT_EVENT_PAGE = 200
    }
}

internal fun WorldEvent.toEntity(world: LivingWorldEntity, now: Long) = LivingEventEntity(
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
    createdAt = now,
)
