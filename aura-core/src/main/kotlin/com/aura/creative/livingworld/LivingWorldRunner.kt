package com.aura.creative.livingworld

import android.util.Log
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

enum class TickOutcome {
    /** The world is level with its clock. */
    CAUGHT_UP,

    /** Ran out of slice. The caller should re-enqueue. */
    PAUSED_FOR_TIME,

    /** Nothing was owed. */
    NOTHING_DUE,

    /**
     * Somebody else moved the world first, so this work was discarded.
     *
     * Distinct from [FAILED] because nothing went wrong: the world is fine,
     * it is just further along than the caller thought. A play session gets
     * this when the hourly worker beats it to a tick, and the honest answer
     * is to show the player the world as it now stands rather than to
     * pretend the move landed.
     */
    OVERTAKEN,

    FAILED,
}

/**
 * Advances living worlds. Has no `Context` and is not a Worker, so every
 * decision here is unit-testable with a fake clock — the same split
 * [com.aura.creative.longform.LongformRunner] makes, and for the same reason.
 *
 * The engine itself never touches the network, so a slice is pure arithmetic
 * plus two database writes. That is what allows a world to be caught up from a
 * cold start without the user waiting on anything.
 */
@Singleton
class LivingWorldRunner @Inject constructor(
    private val store: LivingWorldStore,
    private val tickBus: WorldTickBus? = null,
) {

    /** Advance every running world as far as its slice allows. */
    suspend fun runAllDue(
        deadlineMs: Long,
        isStopped: () -> Boolean,
        now: () -> Long = System::currentTimeMillis,
    ): TickOutcome {
        val worlds = runCatching { store.running() }
            .onFailure { Log.w(TAG, "could not list running worlds: ${it.message}", it) }
            .getOrDefault(emptyList())
        if (worlds.isEmpty()) return TickOutcome.NOTHING_DUE

        var sawWork = false
        for (world in worlds) {
            if (isStopped()) return TickOutcome.PAUSED_FOR_TIME
            when (runSlice(world.id, deadlineMs, isStopped, now)) {
                TickOutcome.PAUSED_FOR_TIME -> return TickOutcome.PAUSED_FOR_TIME
                TickOutcome.CAUGHT_UP -> sawWork = true
                TickOutcome.FAILED -> sawWork = true
                // runSlice never returns this — it discards an overtaken
                // slice as CAUGHT_UP, because the world did advance, just
                // not by this run. Listed rather than folded into an else
                // so that a future path returning it has to be considered.
                TickOutcome.OVERTAKEN -> sawWork = true
                TickOutcome.NOTHING_DUE -> Unit
            }
        }
        return if (sawWork) TickOutcome.CAUGHT_UP else TickOutcome.NOTHING_DUE
    }

    /**
     * Advance one world.
     *
     * A long absence is collapsed by a single [WorldEngine.fold] before any
     * detailed ticking, so the cost of coming back is the same whether the user
     * was gone three days or three months. Only the detail window nearest the
     * present is simulated properly, because that is the only part anyone is
     * going to read closely.
     */
    suspend fun runSlice(
        worldId: String,
        deadlineMs: Long,
        isStopped: () -> Boolean,
        now: () -> Long = System::currentTimeMillis,
    ): TickOutcome {
        val world = runCatching { store.byId(worldId) }
            .onFailure { Log.w(TAG, "load failed for $worldId: ${it.message}", it) }
            .getOrNull() ?: return TickOutcome.FAILED
        if (world.status != LivingWorldEntity.STATUS_RUNNING) return TickOutcome.NOTHING_DUE

        val startedAtTick = world.currentTick
        var behind =
            WorldClock.behind(world.currentTick, world.worldEpochMs, now(), world.sessionTicksBurned)
        if (behind <= 0L) return TickOutcome.NOTHING_DUE

        var state = store.decode(world.stateJson)
        var tick = world.currentTick
        val events = mutableListOf<WorldEvent>()
        tickBus?.begin(world.id, tick, tick + behind)

        if (behind > DETAIL_WINDOW_TICKS) {
            val skipped = behind - DETAIL_WINDOW_TICKS
            tick += skipped
            val folded = WorldEngine.fold(state, skipped, atTick = tick)
            state = folded.state
            events += folded.events
            behind = DETAIL_WINDOW_TICKS
        }

        var simulated = 0
        while (behind > 0L) {
            if (isStopped()) break
            if (now() >= deadlineMs || simulated >= MAX_TICKS_PER_SLICE) break
            tick += 1
            val result = WorldEngine.tick(state, world.id, world.rootSeed, world.branchSalt, tick)
            state = result.state
            events += result.events
            behind -= 1
            simulated += 1
            tickBus?.progress(world.id, tick)
        }

        val scored = score(world.id, events, state)

        if (tick == startedAtTick) {
            // Nothing advanced despite work being owed. Re-enqueueing here is
            // how an unbounded loop starts, so stop and say so instead.
            Log.w(TAG, "world $worldId owed ticks but advanced none; not re-enqueueing")
            return TickOutcome.FAILED
        }

        // WorkManager stops a worker by cancelling its coroutine. A cancellation
        // landing here would discard ticks the engine has already computed while
        // leaving currentTick behind, so the next run would recompute them —
        // harmless, since the ids are deterministic, but it would also throw
        // away the slice's whole cost.
        val committed = runCatching {
            withContext(NonCancellable) {
                store.commitTicks(world, state, tick, scored, now())
            }
        }.onFailure { Log.w(TAG, "commit failed for $worldId at tick $tick: ${it.message}", it) }

        if (committed.isFailure) return TickOutcome.FAILED
        if (committed.getOrDefault(false) == false) {
            // A play session moved the world while this slice was computing.
            // Its work is stale — throwing it away costs one slice and keeps
            // the session's tick, which is the one with a player action in
            // it. The next run picks up from wherever the world now is.
            Log.i(TAG, "slice for $worldId was overtaken at tick $tick; discarding it")
            return TickOutcome.CAUGHT_UP
        }
        if (behind <= 0L) tickBus?.clear(world.id)
        return if (behind > 0L) TickOutcome.PAUSED_FOR_TIME else TickOutcome.CAUGHT_UP
    }

    /**
     * Advance one world by exactly one tick, carrying [actions] into it.
     *
     * This is what makes an evening of play possible in a world that
     * otherwise moves one tick an hour. The tick is *burned*: it counts
     * toward [LivingWorldEntity.sessionTicksBurned], so the world really is
     * a day further along and ambient time carries on from there rather than
     * pausing for as long as the session lasted.
     *
     * Deliberately does not catch the world up first. A world that owes the
     * wall clock three ticks still owes three after this one, because
     * burning adds to the due tick as well as to the current tick. Playing
     * is extra time, never borrowed time.
     */
    suspend fun step(
        worldId: String,
        actions: List<ActorEffect>,
        now: () -> Long = System::currentTimeMillis,
    ): TickOutcome {
        val world = runCatching { store.byId(worldId) }
            .onFailure { Log.w(TAG, "load failed for $worldId: ${it.message}", it) }
            .getOrNull() ?: return TickOutcome.FAILED
        if (world.status != LivingWorldEntity.STATUS_RUNNING) return TickOutcome.NOTHING_DUE

        val tick = world.currentTick + 1L
        val state = runCatching { store.decode(world.stateJson) }
            .onFailure { Log.w(TAG, "state decode failed for $worldId: ${it.message}", it) }
            .getOrNull() ?: return TickOutcome.FAILED
        val result = WorldEngine.tick(state, world.id, world.rootSeed, world.branchSalt, tick, actions)
        val scored = score(world.id, result.events, result.state)

        val committed = runCatching {
            withContext(NonCancellable) {
                store.commitPlayedTicks(world, result.state, tick, burned = 1L, events = scored, now = now())
            }
        }.onFailure { Log.w(TAG, "played commit failed for $worldId at tick $tick: ${it.message}", it) }
        if (committed.isFailure) return TickOutcome.FAILED
        if (committed.getOrDefault(false) == false) {
            // The hourly worker got there first. Nothing was written — not
            // the tick and not the action — so the move is simply not made,
            // and the caller can offer it again against a world that has
            // moved on. Silently succeeding here would be the worst outcome:
            // a tap that appeared to work and did nothing.
            Log.i(TAG, "play at tick $tick for $worldId was overtaken; the move was not made")
            return TickOutcome.OVERTAKEN
        }

        tickBus?.progress(world.id, tick)
        return TickOutcome.CAUGHT_UP
    }

    /**
     * Score at commit time, against one bounded read of what came before.
     *
     * Novelty is the only factor that needs history, and asking for it once
     * per commit keeps the scorer's promise of costing nothing per event.
     */
    private suspend fun score(
        worldId: String,
        events: List<WorldEvent>,
        state: WorldState,
    ): List<ScoredEvent> {
        val recent = runCatching { store.recentEvents(worldId, NotabilityScorer.NOVELTY_WINDOW) }
            .onFailure { Log.w(TAG, "novelty window read failed: ${it.message}", it) }
            .getOrDefault(emptyList())
        val seen = HashMap<String, Int>()
        for (row in recent) seen.merge(row.kind + "|" + row.actorId, 1, Int::plus)
        return events.map { event ->
            val key = event.kind + "|" + event.actorId
            val before = seen.getOrDefault(key, 0)
            seen[key] = before + 1
            ScoredEvent(event, NotabilityScorer.score(event, state, before))
        }
    }

    companion object {
        private const val TAG = "LivingWorldRunner"

        /**
         * How much of the recent past is simulated in full rather than folded.
         *
         * Two world months. Everything older than this in a catch-up is
         * collapsed into one quiet interval, which is the trade that keeps the
         * cost of a long absence constant.
         */
        const val DETAIL_WINDOW_TICKS = 48L

        /** Ticks per worker execution. Bounds a slice regardless of the deadline. */
        const val MAX_TICKS_PER_SLICE = 24
    }
}
