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
     * The durable half of the screen's state. Re-emits when a worker commits a
     * tick, which is how progress made while the screen was closed — or in a
     * previous process — appears on return without polling.
     */
    @Query("SELECT * FROM living_worlds WHERE projectId = :projectId ORDER BY createdAt ASC LIMIT 1")
    fun observeForProject(projectId: String): Flow<LivingWorldEntity?>

    /** Every world a tick worker should advance. */
    @Query("SELECT * FROM living_worlds WHERE status = 'running' ORDER BY id ASC")
    suspend fun running(): List<LivingWorldEntity>

    @Query("UPDATE living_worlds SET currentTick = :tick, stateJson = :stateJson, updatedAt = :updatedAt WHERE id = :id")
    suspend fun commitTick(id: String, tick: Long, stateJson: String, updatedAt: Long)

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

    @Query("SELECT COUNT(*) FROM living_events WHERE worldId = :worldId")
    suspend fun count(worldId: String): Int

    @Query("DELETE FROM living_events WHERE worldId = :worldId AND tickIndex < :beforeTick")
    suspend fun trimBefore(worldId: String, beforeTick: Long)

    @Query("SELECT * FROM living_events")
    suspend fun allForBackup(): List<LivingEventEntity>

    @Query("DELETE FROM living_events")
    suspend fun deleteAll()
}
