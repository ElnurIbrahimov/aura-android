package com.aura.creative.livingworld

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.aura.creative.CreativeProjectEntity

/**
 * One living world, attached to one creative project on one branch.
 *
 * The world's whole state is a JSON blob in [stateJson], exactly as the world
 * bible lives in `CreativeProjectEntity.worldJson`. Normalising it into tables
 * buys nothing until a query needs to filter *inside* it, which does not happen
 * until the point-of-view work — and a normalised schema guessed before any
 * consumer exists is how this codebase ended up with a canon layer nothing
 * writes to.
 *
 * Named `living_worlds`, not `worlds`: `world_events` and `beliefs` already
 * exist in this same database and belong to the assistant's model of the user's
 * *real* life. A faction's belief landing in that table would be surfaced to the
 * user as a genuine suggestion.
 */
@Entity(
    tableName = "living_worlds",
    foreignKeys = [
        ForeignKey(
            entity = CreativeProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["projectId"]),
        Index(value = ["projectId", "branchId"], unique = true),
        Index(value = ["status"]),
    ],
)
data class LivingWorldEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val branchId: String,
    /** Drawn once at creation and never changed. With the tick index, it determines everything. */
    val rootSeed: Long,
    /** Distinguishes a fork's luck from its parent's. 0 on a root world. */
    val branchSalt: Long = 0L,
    /** Blank on a root world. Pre-fork history is read through the parent. */
    val parentWorldId: String = "",
    val forkedAtTick: Long = 0L,
    /** Wall-clock anchor. Tick N became due at `worldEpochMs + N * TICK_REAL_MS`. */
    val worldEpochMs: Long,
    /** How far the stored state has caught up. Always `<= WorldClock.dueTick`. */
    val currentTick: Long = 0L,
    val stateJson: String,
    /**
     * The exact canonical tick-0 state, written once at creation and never
     * updated. Fork-at-past replays from here; "" on worlds created before
     * v29, for which that door stays closed — re-seeding cannot reconstruct
     * genesis once the author has edited the bible.
     */
    @androidx.room.ColumnInfo(defaultValue = "")
    val genesisJson: String = "",
    val status: String = STATUS_RUNNING,
    /**
     * The character the player occupies, and the faction that character
     * leads. Blank on a world nobody has taken a seat in — which is every
     * world created before this column existed, and stays a valid state:
     * an unseated world is watched rather than played, exactly as before.
     *
     * Two ids rather than one because they answer different questions.
     * [playerCharacterId] decides what you can see (it has a `parentId`, so
     * it is somewhere); [playerFactionId] decides what you can do (it holds
     * the stocks a claim moves). Commanding more than you can see is the
     * tension the seat exists to create.
     */
    @androidx.room.ColumnInfo(defaultValue = "")
    val playerCharacterId: String = "",
    @androidx.room.ColumnInfo(defaultValue = "")
    val playerFactionId: String = "",
    /**
     * Ticks the player advanced deliberately, on top of the wall clock.
     *
     * [WorldClock] derives the due tick from elapsed real time, which is the
     * whole point of an ambient world — it moves whether or not anybody is
     * watching. A session adds to that rather than replacing it: the due
     * tick becomes `elapsed + burned`, so playing through ten days really
     * does leave the world ten days further along and the ambient clock
     * carries on from there.
     *
     * Replay is indifferent to this. It walks ticks 0..N and never asks how
     * any particular tick came to be due.
     */
    @androidx.room.ColumnInfo(defaultValue = "0")
    val sessionTicksBurned: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
) {
    companion object {
        const val STATUS_RUNNING = "running"
        const val STATUS_PAUSED = "paused"
        const val STATUS_ARCHIVED = "archived"
    }
}

/**
 * Something that happened in a world.
 *
 * [id] is `worldId#tick.seq` rather than a random UUID, so re-applying a tick
 * replaces its rows instead of duplicating them — which is what makes a retried
 * worker slice idempotent.
 *
 * [summary] is rendered by the engine from a deterministic template and is
 * always present. [narration] is prose attached later to the few events that
 * earn it, and the timeline reads perfectly well without any.
 *
 * Deliberately not stored in `proactive_events`: `ProactiveEvents.init` deletes
 * everything older than thirty days on every app start, and a world's history
 * must outlive a month.
 */
@Entity(
    tableName = "living_events",
    foreignKeys = [
        ForeignKey(
            entity = LivingWorldEntity::class,
            parentColumns = ["id"],
            childColumns = ["worldId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["worldId", "tickIndex"]),
        Index(value = ["worldId", "notability"]),
        Index(value = ["worldId", "actorId", "tickIndex"]),
        Index(value = ["worldId", "narratedAt"]),
    ],
)
data class LivingEventEntity(
    @PrimaryKey val id: String,
    val worldId: String,
    val branchId: String,
    val tickIndex: Long,
    val seq: Int,
    val kind: String,
    val actorId: String,
    val targetId: String = "",
    val ruleId: String = "",
    val magnitudeMilli: Long = 0L,
    val summary: String,
    /** Scored when the event is committed. Gates narration, filters the timeline. */
    val notability: Double = 0.0,
    val narration: String = "",
    /** 0 means never narrated. Also the per-day narration budget counter. */
    val narratedAt: Long = 0L,
    /**
     * The serialised [Effect] for a `player_action` row. Blank on every
     * event the engine produced itself.
     *
     * A player move is a god-edit on a world that must stay replayable, and
     * [WorldReplayer] states the rule: such an edit "must land as a
     * replayable event kind, or fork-at-past breaks silently". Events are
     * already the replay journal — `quiet_interval` rows are how folds are
     * recorded — so the move belongs here rather than in a table of its own.
     *
     * The existing columns nearly fit (`actorId`, `magnitudeMilli`,
     * `targetId`) and deliberately are not reused: squeezing a sealed
     * hierarchy into three loosely-typed fields makes the decoder guess, and
     * a decoder that guesses wrong replays a different world than the one
     * that was played.
     */
    @androidx.room.ColumnInfo(defaultValue = "")
    val payloadJson: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)
