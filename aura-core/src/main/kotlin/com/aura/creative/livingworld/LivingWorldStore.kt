package com.aura.creative.livingworld

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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

    fun observeAllForProject(projectId: String): Flow<List<LivingWorldEntity>> =
        worldDao.observeAllForProject(projectId)

    suspend fun forProjectAndBranch(projectId: String, branchId: String): LivingWorldEntity? =
        worldDao.forProjectAndBranch(projectId, branchId)

    /**
     * Fork at the present: a sibling world sharing every tick up to now and
     * free to diverge from the next draw on. Same rootSeed — the pre-fork past
     * is shared identity — and the same worldEpochMs, so tick N is due at the
     * same wall time on every branch and WorldClock stays truthful. State and
     * genesis are byte-copied; only the salt is new.
     */
    suspend fun fork(
        parent: LivingWorldEntity,
        branchId: String,
        branchName: String,
    ): LivingWorldEntity {
        val world = LivingWorldEntity(
            id = UUID.randomUUID().toString(),
            projectId = parent.projectId,
            branchId = branchId,
            rootSeed = parent.rootSeed,
            branchSalt = deriveBranchSalt(parent, parent.currentTick, branchName),
            parentWorldId = parent.id,
            forkedAtTick = parent.currentTick,
            worldEpochMs = parent.worldEpochMs,
            currentTick = parent.currentTick,
            stateJson = parent.stateJson,
            genesisJson = parent.genesisJson,
            // You are still you in the branch, and it stands at the same
            // tick, so it owes the clock exactly what the parent owes.
            playerCharacterId = parent.playerCharacterId,
            playerFactionId = parent.playerFactionId,
            sessionTicksBurned = parent.sessionTicksBurned,
        )
        worldDao.upsert(world)
        return world
    }

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
    /** Returns false when another writer moved the world first and this work is stale. */
    suspend fun commitTicks(
        world: LivingWorldEntity,
        newState: WorldState,
        throughTick: Long,
        events: List<ScoredEvent>,
        now: Long,
    ): Boolean {
        // Events first, then the tick. Engine events are a pure function of
        // (state, tick) with deterministic ids, so writing them for a tick
        // that then loses the commit is harmless — whoever recomputes that
        // tick writes the identical rows over the top.
        if (events.isNotEmpty()) {
            eventDao.upsertAll(events.map { it.event.toEntity(world, now, it.notability) })
        }
        return worldDao.commitTick(world.id, throughTick, encode(newState), now, world.currentTick) > 0
    }

    /**
     * Commit ticks the player advanced deliberately, burning [burned] of
     * them against the wall clock so ambient time keeps its own count.
     *
     * Returns false when another writer moved the world first. The tick
     * lands before its events here, the opposite way round from
     * [commitTicks], and the asymmetry is the point: a played tick carries a
     * `player_action` row that only this call will ever write. Writing it
     * for a tick that then loses the commit would leave the journal claiming
     * a move the stored state does not contain, which is precisely the
     * divergence the journal exists to prevent. An engine event can be
     * recomputed by anyone; a player's move cannot.
     */
    suspend fun commitPlayedTicks(
        world: LivingWorldEntity,
        newState: WorldState,
        throughTick: Long,
        burned: Long,
        events: List<ScoredEvent>,
        now: Long,
    ): Boolean {
        val applied = worldDao.commitPlayedTick(
            world.id, throughTick, encode(newState), burned, now, world.currentTick,
        ) > 0
        if (!applied) return false
        if (events.isNotEmpty()) {
            eventDao.upsertAll(events.map { it.event.toEntity(world, now, it.notability) })
        }
        return true
    }

    /** Put the player in a character and give them a faction to command. */
    suspend fun seat(worldId: String, characterId: String, factionId: String, now: Long) {
        worldDao.seat(worldId, characterId, factionId, now)
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
     * The branch chain as replay segments, root first, loop-guarded. Each
     * segment names whose salt governs its ticks; a fork-of-fork switches at
     * every boundary its chain crossed.
     */
    suspend fun resolveSegments(world: LivingWorldEntity, throughTick: Long): List<WorldReplayer.Segment> {
        val chain = mutableListOf<LivingWorldEntity>()
        var cursor: LivingWorldEntity? = world
        val seen = mutableSetOf<String>()
        while (cursor != null && seen.add(cursor.id)) {
            chain += cursor
            cursor = cursor.parentWorldId.takeIf { it.isNotBlank() }?.let { worldDao.byId(it) }
        }
        chain.reverse()
        return chain.mapIndexed { index, entry ->
            WorldReplayer.Segment(
                worldId = entry.id,
                rootSeed = entry.rootSeed,
                branchSalt = entry.branchSalt,
                fromTick = if (index == 0) 0L else entry.forkedAtTick,
                toTick = if (index == chain.lastIndex) throughTick else chain[index + 1].forkedAtTick,
            )
        }
    }

    /** The recorded folds along the chain, each read from the world that folded it. */
    suspend fun resolveFoldSpans(
        segments: List<WorldReplayer.Segment>,
        throughTick: Long,
    ): List<WorldReplayer.FoldSpan> {
        val folds = mutableListOf<WorldReplayer.FoldSpan>()
        for (segment in segments) {
            folds += eventDao
                .ofKindUpTo(segment.worldId, WorldEngine.KIND_QUIET_INTERVAL, minOf(segment.toTick, throughTick))
                .filter { it.tickIndex > segment.fromTick }
                .map { WorldReplayer.FoldSpan(atTick = it.tickIndex, ticks = it.magnitudeMilli) }
        }
        return folds.sortedBy { it.atTick }
    }

    /**
     * The recorded player actions along the chain, in the order the engine
     * received them.
     *
     * Mirrors [resolveFoldSpans], because an action is history in the same
     * sense a fold is: the tick cannot be recomputed without it.
     *
     * A row whose payload will not decode throws rather than being skipped.
     * Skipping would hand [WorldReplayer] a shorter history and get back a
     * plausible world that is not the one that was played — the exact silent
     * failure the replayer refuses to be party to. The caller catches it and
     * declines to fork.
     */
    suspend fun resolveActions(
        segments: List<WorldReplayer.Segment>,
        throughTick: Long,
    ): List<WorldReplayer.ActionAt> {
        val actions = mutableListOf<WorldReplayer.ActionAt>()
        for (segment in segments) {
            actions += eventDao
                .ofKindUpTo(segment.worldId, WorldEngine.KIND_PLAYER_ACTION, minOf(segment.toTick, throughTick))
                .filter { it.tickIndex > segment.fromTick }
                .map { row ->
                    WorldReplayer.ActionAt(
                        atTick = row.tickIndex,
                        seq = row.seq,
                        action = ActorEffect(
                            actorId = row.actorId,
                            effect = payloadJson.decodeFromString(Effect.serializer(), row.payloadJson),
                        ),
                    )
                }
        }
        return actions.sortedWith(compareBy({ it.atTick }, { it.seq }))
    }

    /**
     * Fork at a past tick, gated on genesis: pre-v29 worlds have none and are
     * honestly told no. The child's state is replayed from genesis along the
     * recorded fold spans, its clock anchor is shared, and its currentTick is
     * the past — so it immediately owes the ticks since, and catches up along
     * its own salt through the ordinary worker path. The counterfactual IS the
     * catch-up.
     */
    suspend fun forkAt(
        parent: LivingWorldEntity,
        tick: Long,
        branchId: String,
        branchName: String,
    ): LivingWorldEntity? {
        if (parent.genesisJson.isBlank()) return null
        if (tick < 0L || tick > parent.currentTick) return null
        val genesis = decode(parent.genesisJson)
        val segments = resolveSegments(parent, tick)
        val folds = resolveFoldSpans(segments, tick)
        val actions = runCatching { resolveActions(segments, tick) }.getOrElse { return null }
        val state = runCatching { WorldReplayer.stateAt(genesis, segments, folds, actions, tick) }
            .getOrElse { return null }
        val world = LivingWorldEntity(
            id = UUID.randomUUID().toString(),
            projectId = parent.projectId,
            branchId = branchId,
            rootSeed = parent.rootSeed,
            branchSalt = deriveBranchSalt(parent, tick, branchName),
            parentWorldId = parent.id,
            forkedAtTick = tick,
            worldEpochMs = parent.worldEpochMs,
            currentTick = tick,
            stateJson = encode(state),
            genesisJson = parent.genesisJson,
            // You are still you in the branch. A fork is "what if I had
            // done otherwise", which is not a question anybody asks from
            // outside their own timeline.
            playerCharacterId = parent.playerCharacterId,
            playerFactionId = parent.playerFactionId,
            // How much of the child's tick the wall clock did not pay for.
            //
            // The parent reached `currentTick` on `currentTick - burned`
            // hours of real time, so anything past that mark was played
            // rather than waited for. Fork below the mark and the child
            // owes the ticks since, which is the documented behaviour —
            // the counterfactual IS the catch-up. Fork above it and the
            // child must inherit the burn, or it sits ahead of a clock
            // that has to spend the length of the session catching up,
            // and forking silently switches the ambient half off.
            sessionTicksBurned = (tick - (parent.currentTick - parent.sessionTicksBurned))
                .coerceAtLeast(0L),
        )
        worldDao.upsert(world)
        return world
    }

    /**
     * The child's timeline with its inheritance: its own rows live, ancestors'
     * pages appended read-once — an ancestor's past is immutable, so there is
     * nothing to observe over there.
     */
    fun observeEventsDeep(
        world: LivingWorldEntity,
        limit: Int = DEFAULT_EVENT_PAGE,
    ): Flow<List<LivingEventEntity>> =
        eventDao.observeRecent(world.id, limit).map { own ->
            if (world.parentWorldId.isBlank() || own.size >= limit) return@map own
            val inherited = mutableListOf<LivingEventEntity>()
            var boundary = world.forkedAtTick
            var cursor = worldDao.byId(world.parentWorldId)
            val seen = mutableSetOf(world.id)
            while (cursor != null && seen.add(cursor.id) && own.size + inherited.size < limit) {
                inherited += eventDao.recentUpTo(cursor.id, boundary, limit - own.size - inherited.size)
                boundary = cursor.forkedAtTick
                cursor = cursor.parentWorldId.takeIf { it.isNotBlank() }?.let { worldDao.byId(it) }
            }
            own + inherited
        }

    suspend fun topNotableOfKinds(worldId: String, kinds: List<String>, limit: Int): List<LivingEventEntity> =
        eventDao.topNotableOfKinds(worldId, kinds, limit)

    suspend fun ascAfter(worldId: String, afterTick: Long, limit: Int): List<LivingEventEntity> =
        eventDao.ascAfter(worldId, afterTick, limit)

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

        /**
         * A fork is a ref, not a dice roll: the same-named fork of the same
         * moment yields the same world, every time, on every device — which is
         * what replay and backup already promise. Derived from the parent's
         * salt so fork-of-fork chains stay distinct, and re-mixed if the
         * derivation lands on the parent's own salt, because equal salts would
         * make the child's future identical to the parent's.
         */
        fun deriveBranchSalt(parent: LivingWorldEntity, atTick: Long, branchName: String): Long {
            var salt = WorldRng.mix64(
                parent.branchSalt xor WorldRng.stableHash64("fork:${parent.id}:$atTick:$branchName"),
            )
            if (salt == parent.branchSalt) salt = WorldRng.mix64(salt)
            return salt
        }

        /** 30 real days of hourly ticks — the house retention window. */
        const val COMPACTION_HORIZON_TICKS = 720L

        /** Rows per world past which the emergency valve opens. */
        const val HARD_CAP_ROWS = 24_000
    }
}

/**
 * The codec for [WorldEvent.payload].
 *
 * File-level rather than a store field because the mapper below is a
 * top-level function and the read side needs the identical configuration —
 * an encoder and a decoder that disagree are how a journal becomes
 * unreplayable.
 */
private val payloadJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

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
    payloadJson = payload?.let { payloadJson.encodeToString(Effect.serializer(), it) }.orEmpty(),
    createdAt = now,
)
