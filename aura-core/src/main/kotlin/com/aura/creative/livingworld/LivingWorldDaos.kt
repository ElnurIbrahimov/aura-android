package com.aura.creative.livingworld

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LivingWorldDao {
    @Upsert
    suspend fun upsert(world: LivingWorldEntity)

    /** Restore path. Worlds must land before their events — the events carry the foreign key. */
    @Upsert
    suspend fun upsertAll(worlds: List<LivingWorldEntity>)

    @Query("SELECT * FROM living_worlds WHERE id = :id")
    suspend fun byId(id: String): LivingWorldEntity?

    @Query("SELECT * FROM living_worlds WHERE projectId = :projectId ORDER BY createdAt ASC")
    suspend fun forProject(projectId: String): List<LivingWorldEntity>

    /**
     * The durable half of the screen's state, every timeline of it: oldest
     * first, so the root world leads. Re-emits when a worker commits a tick,
     * which is how progress made while the screen was closed — or in a
     * previous process — appears on return without polling.
     */
    @Query("SELECT * FROM living_worlds WHERE projectId = :projectId ORDER BY createdAt ASC, id ASC")
    fun observeAllForProject(projectId: String): Flow<List<LivingWorldEntity>>

    @Query("SELECT * FROM living_worlds WHERE projectId = :projectId AND branchId = :branchId LIMIT 1")
    fun observeForProjectAndBranch(projectId: String, branchId: String): Flow<LivingWorldEntity?>

    @Query("SELECT * FROM living_worlds WHERE projectId = :projectId AND branchId = :branchId LIMIT 1")
    suspend fun forProjectAndBranch(projectId: String, branchId: String): LivingWorldEntity?

    /** Every world a tick worker should advance. */
    @Query("SELECT * FROM living_worlds WHERE status = 'running' ORDER BY id ASC")
    suspend fun running(): List<LivingWorldEntity>

    /** Every world, whatever its status — compaction owes paused worlds too. */
    @Query("SELECT * FROM living_worlds ORDER BY id ASC")
    suspend fun all(): List<LivingWorldEntity>

    @Query("UPDATE living_worlds SET currentTick = :tick, stateJson = :stateJson, updatedAt = :updatedAt WHERE id = :id")
    suspend fun commitTick(id: String, tick: Long, stateJson: String, updatedAt: Long)

    /**
     * Commit a tick the player advanced on purpose.
     *
     * The burn is `+ :burned` in SQL rather than a value computed in Kotlin
     * so that a session running while the hourly worker commits cannot lose
     * a tick to a read-modify-write race.
     */
    @Query(
        "UPDATE living_worlds SET currentTick = :tick, stateJson = :stateJson, " +
            "sessionTicksBurned = sessionTicksBurned + :burned, updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun commitPlayedTick(id: String, tick: Long, stateJson: String, burned: Long, updatedAt: Long)

    @Query("UPDATE living_worlds SET playerCharacterId = :characterId, playerFactionId = :factionId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun seat(id: String, characterId: String, factionId: String, updatedAt: Long)

    @Query("UPDATE living_worlds SET status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: String, updatedAt: Long)

    @Query("DELETE FROM living_worlds WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM living_worlds")
    suspend fun count(): Int

    @Query("SELECT * FROM living_worlds")
    suspend fun allForBackup(): List<LivingWorldEntity>

    @Query("DELETE FROM living_worlds")
    suspend fun deleteAll()
}

@Dao
interface LivingEventDao {
    @Upsert
    suspend fun upsertAll(events: List<LivingEventEntity>)

    /**
     * Newest first, bounded. A year of world history is thousands of rows and
     * the timeline is paged; nothing ever reads the whole table.
     */
    @Query("SELECT * FROM living_events WHERE worldId = :worldId ORDER BY tickIndex DESC, seq DESC LIMIT :limit")
    fun observeRecent(worldId: String, limit: Int): Flow<List<LivingEventEntity>>

    @Query("SELECT * FROM living_events WHERE worldId = :worldId ORDER BY tickIndex DESC, seq DESC LIMIT :limit")
    suspend fun recent(worldId: String, limit: Int): List<LivingEventEntity>

    /** An ancestor's page: its history at or before the fork boundary. */
    @Query(
        "SELECT * FROM living_events WHERE worldId = :worldId AND tickIndex <= :throughTick " +
            "ORDER BY tickIndex DESC, seq DESC LIMIT :limit",
    )
    suspend fun recentUpTo(worldId: String, throughTick: Long, limit: Int): List<LivingEventEntity>

    /** The divergence scan's page: ascending from the common fork tick. */
    @Query(
        "SELECT * FROM living_events WHERE worldId = :worldId AND tickIndex > :afterTick " +
            "ORDER BY tickIndex ASC, seq ASC LIMIT :limit",
    )
    suspend fun ascAfter(worldId: String, afterTick: Long, limit: Int): List<LivingEventEntity>

    /** The fold record, for replay: every row of one kind up to a tick, oldest first. */
    @Query(
        "SELECT * FROM living_events WHERE worldId = :worldId AND kind = :kind AND tickIndex <= :throughTick " +
            "ORDER BY tickIndex ASC, seq ASC",
    )
    suspend fun ofKindUpTo(worldId: String, kind: String, throughTick: Long): List<LivingEventEntity>

    /** Plot mining: the most notable events of the dramatic kinds. */
    @Query(
        "SELECT * FROM living_events WHERE worldId = :worldId AND kind IN (:kinds) " +
            "ORDER BY notability DESC, tickIndex DESC LIMIT :limit",
    )
    suspend fun topNotableOfKinds(worldId: String, kinds: List<String>, limit: Int): List<LivingEventEntity>

    @Query(
        "SELECT * FROM living_events WHERE worldId = :worldId AND tickIndex BETWEEN :fromTick AND :toTick " +
            "ORDER BY tickIndex ASC, seq ASC",
    )
    suspend fun inTickRange(worldId: String, fromTick: Long, toTick: Long): List<LivingEventEntity>

    /** Drives the top-K narration pick, and the timeline's "big moments" filter. */
    @Query(
        "SELECT * FROM living_events WHERE worldId = :worldId AND narratedAt = 0 AND notability >= :floor " +
            "ORDER BY notability DESC, tickIndex DESC LIMIT :limit",
    )
    suspend fun topUnnarrated(worldId: String, floor: Double, limit: Int): List<LivingEventEntity>

    /**
     * The daily narration budget, counted in the same table as the thing it
     * counts. A separate counter row would be one more thing that can drift out
     * of sync with reality.
     */
    @Query("SELECT COUNT(*) FROM living_events WHERE worldId = :worldId AND narratedAt > :since")
    suspend fun narratedSince(worldId: String, since: Long): Int

    @Query("UPDATE living_events SET narration = :narration, narratedAt = :narratedAt WHERE id = :id")
    suspend fun attachNarration(id: String, narration: String, narratedAt: Long)

    @Query("SELECT * FROM living_events WHERE id = :id")
    suspend fun byId(id: String): LivingEventEntity?

    @Query("SELECT COUNT(*) FROM living_events WHERE worldId = :worldId")
    suspend fun count(worldId: String): Int

    /**
     * Compaction's blade for the noise floor: sub-floor, never-narrated rows
     * older than the horizon. What survives is the notable spine (the
     * product), every paid narration, and every quiet_interval — replay-based
     * forking walks those, so they are load-bearing, not sentiment.
     */
    @Query(
        "DELETE FROM living_events WHERE worldId = :worldId AND tickIndex < :beforeTick " +
            "AND notability < :floor AND narration = ''",
    )
    suspend fun trimNoiseBefore(worldId: String, beforeTick: Long, floor: Double)

    /** The tick sitting :offset rows back from the newest, for the hard cap. */
    @Query(
        "SELECT tickIndex FROM living_events WHERE worldId = :worldId " +
            "ORDER BY tickIndex DESC, seq DESC LIMIT 1 OFFSET :offset",
    )
    suspend fun tickAtOffset(worldId: String, offset: Int): Long?

    /** The emergency valve: everything before the tick, notable or not. */
    @Query("DELETE FROM living_events WHERE worldId = :worldId AND tickIndex < :beforeTick")
    suspend fun trimBefore(worldId: String, beforeTick: Long)

    @Query("SELECT * FROM living_events")
    suspend fun allForBackup(): List<LivingEventEntity>

    @Query("DELETE FROM living_events")
    suspend fun deleteAll()
}
