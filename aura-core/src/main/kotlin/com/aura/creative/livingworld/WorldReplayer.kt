package com.aura.creative.livingworld

/**
 * Reconstructs a world's state at a past tick by replaying its history from
 * genesis, exactly as that history was computed.
 *
 * "As it was computed" is the whole contract. The runner folded some spans
 * closed-form and stepped the rest, and which spans were folded depended on
 * when the phone happened to be awake — so the replay follows the *recorded*
 * fold spans (the quiet_interval rows are that record) rather than re-deriving
 * spans from any clock. Everything else is [WorldEngine.tick], which is pure
 * and deterministic, with each tick's salt drawn from the branch segment that
 * owned it — a fork-of-fork switches salts at every boundary its chain crossed.
 *
 * Three preconditions, checked by the caller and honestly refused otherwise:
 * genesis exists (pre-v29 worlds have none and never will); every
 * quiet_interval row up to the target survives (compaction preserves them by
 * construction — their 0.40 sits above the trim floor); and state was never
 * mutated outside the engine. That last clause is this object's tripwire: any
 * future god-edit surface must land as a replayable event kind, or fork-at-past
 * breaks silently.
 *
 * Player actions are the first such surface, and they honour it: each one is a
 * `player_action` event carrying its [Effect], read back here as [ActionAt] and
 * fed to the tick that received it. So a world that was *played* replays like a
 * world that was only watched, and forking to a tick before a move yields a
 * branch where the move genuinely has not happened yet.
 */
object WorldReplayer {

    /** One branch segment: [salt] governs ticks in `(fromTick, toTick]`. */
    data class Segment(
        val worldId: String,
        val rootSeed: Long,
        val branchSalt: Long,
        val fromTick: Long,
        val toTick: Long,
    )

    /** A recorded fold: [ticks] quiet ticks collapsed, arriving at [atTick]. */
    data class FoldSpan(val atTick: Long, val ticks: Long)

    /**
     * A recorded action: one [ActorEffect] submitted into tick [atTick].
     *
     * [seq] is the position of the `player_action` row within its tick, and
     * it is load-bearing rather than decorative. Claims and lies are
     * deferred and resolved in a canonical order, so those are indifferent
     * to it — but [Effect.AdjustStock] and [Effect.AdjustRelation] apply as
     * they are encountered, and stocks clamp at zero and at capacity. Spend
     * then earn is not the same world as earn then spend once either end of
     * the range is touched, so a journal read back out of order replays a
     * world that was never played.
     */
    data class ActionAt(val atTick: Long, val seq: Int, val action: ActorEffect)

    /**
     * State at [targetTick], walking recorded folds and stepping the rest.
     *
     * Refuses a target strictly inside a folded span — the fold is atomic, so
     * no exact state exists there. Callers fork at events, and events only
     * exist at detailed ticks or at a fold's arrival tick, so the refusal is
     * a guard rail, not a working path.
     */
    fun stateAt(
        genesis: WorldState,
        segments: List<Segment>,
        folds: List<FoldSpan>,
        actions: List<ActionAt>,
        targetTick: Long,
    ): WorldState {
        require(targetTick >= 0L) { "targetTick must be non-negative" }
        val orderedFolds = folds.sortedBy { it.atTick }
        for (fold in orderedFolds) {
            val start = fold.atTick - fold.ticks
            require(targetTick <= start || targetTick >= fold.atTick) {
                "tick $targetTick is inside a folded span ($start, ${fold.atTick}) — no exact state exists there"
            }
            // A fold is a closed-form shortcut over ticks nobody was awake
            // for, and it cannot carry an action. If one is in there, the
            // replay would quietly produce a world where you never acted —
            // which is precisely the silent breakage this object exists to
            // refuse. Loud is the only acceptable failure here.
            // `atTick` is inclusive: the loop below jumps the cursor from
            // `start` straight to `atTick` and never calls tick() for it, so
            // the arrival tick is inside the span even though it reads like
            // the far edge of it. A guard stopping one short would let
            // through the one case that looks handled.
            require(actions.none { it.atTick > start && it.atTick <= fold.atTick }) {
                "an action sits inside folded span ($start, ${fold.atTick}] and cannot be replayed"
            }
        }

        // Grouped once rather than scanned per tick, and sorted within the
        // tick because submission order decides contested draws.
        val actionsByTick = actions
            .groupBy { it.atTick }
            .mapValues { (_, at) -> at.sortedBy { it.seq }.map { it.action } }

        var state = genesis
        var tick = 0L
        while (tick < targetTick) {
            val fold = orderedFolds.firstOrNull { it.atTick - it.ticks == tick && it.atTick <= targetTick }
            if (fold != null) {
                state = WorldEngine.fold(state, fold.ticks, fold.atTick).state
                tick = fold.atTick
            } else {
                tick += 1
                val segment = segmentFor(segments, tick)
                state = WorldEngine.tick(
                    state,
                    segment.worldId,
                    segment.rootSeed,
                    segment.branchSalt,
                    tick,
                    actionsByTick[tick].orEmpty(),
                ).state
            }
        }
        return state
    }

    private fun segmentFor(segments: List<Segment>, tick: Long): Segment =
        segments.firstOrNull { tick > it.fromTick && tick <= it.toTick }
            ?: segments.lastOrNull()
            ?: error("no branch segment covers tick $tick")
}
