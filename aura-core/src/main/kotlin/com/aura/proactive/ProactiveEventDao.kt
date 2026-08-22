package com.aura.proactive

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ProactiveEventDao {
    @Insert
    suspend fun insert(event: ProactiveEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<ProactiveEventEntity>)

    @Query("SELECT * FROM proactive_events ORDER BY timestamp ASC")
    suspend fun allForBackup(): List<ProactiveEventEntity>

    @Query("DELETE FROM proactive_events")
    suspend fun deleteAll()

    @Query("SELECT * FROM proactive_events ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int = 100): List<ProactiveEventEntity>

    /**
     * Single-row lookup by primary key. Used by the chat screen to load
     * a morning brief's body from its event id — the id (not the full
     * text) is what travels through notification extras and nav-route
     * arguments, avoiding TransactionTooLargeException on long briefs.
     */
    @Query("SELECT * FROM proactive_events WHERE id = :id")
    suspend fun byId(id: Long): ProactiveEventEntity?

    /**
     * Count of events with timestamp > [since]. Used by
     * [com.aura.proactive.ProactiveEvents.unreadCount] to drive the
     * Home-screen "📬 N today" badge. Pure SQL aggregate — the caller
     * doesn't need to load the full event rows, just the integer.
     */
    @Query("SELECT COUNT(*) FROM proactive_events WHERE timestamp > :since")
    suspend fun countSince(since: Long): Int

    /**
     * Delete all events older than [cutoff]. Called from
     * [com.aura.proactive.ProactiveEvents.init] with `now - 30 days`
     * so the table stays bounded — without this it grows forever and
     * the `recent()` query slows down as rows accumulate.
     */
    @Query("DELETE FROM proactive_events WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    /**
     * Every event carrying one correlation tag.
     *
     * Kept although only tests call it: it is the only way to observe a write
     * production really performs, and a write nothing can read back is a write
     * nothing can prove.
     * `correlationTag` has exactly one writer — living-world reports — and this
     * is what proves the tag survives the write.
     */
    @Query("SELECT * FROM proactive_events WHERE correlationTag = :tag ORDER BY timestamp DESC LIMIT :limit")
    suspend fun byCorrelationTag(tag: String, limit: Int = 20): List<ProactiveEventEntity>

    /** Count events by eventType. Used by Settings to show daemon thought count. */
    @Query("SELECT COUNT(*) FROM proactive_events WHERE eventType = :type")
    suspend fun countByType(type: kotlin.String): Int
}

@Dao
interface ProactiveInteractionDao {
    @Insert
    suspend fun insert(interaction: ProactiveInteractionEntity): Long

    @Query("SELECT action, COUNT(*) as count FROM proactive_interactions GROUP BY action")
    suspend fun summary(): List<ActionCount>

    @Query("SELECT * FROM proactive_interactions ORDER BY timestamp ASC")
    suspend fun allForBackup(): List<ProactiveInteractionEntity>

    @Query("SELECT * FROM proactive_interactions ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recent(limit: Int = 20): List<ProactiveInteractionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(interactions: List<ProactiveInteractionEntity>)

    @Query("DELETE FROM proactive_interactions")
    suspend fun deleteAll()
}

/** Row returned by [ProactiveInteractionDao.summary]. */
data class ActionCount(
    val action: String,
    val count: Int,
)
