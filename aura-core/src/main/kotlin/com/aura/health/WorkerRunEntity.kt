package com.aura.health

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * What Aura's background life actually did.
 *
 * Seven workers run on their own schedules and until now none of them left a
 * trace: eighteen `Result.failure` / `Result.retry` paths and no surface
 * anywhere that says what ran, when, or what came of it. The consequence is not
 * that failures went unnoticed — it is that *success* and *never having run*
 * were indistinguishable, so no claim about any background feature could be
 * checked. A dream cycle that has never fired and one that fired and found
 * nothing to consolidate produce exactly the same empty screen.
 *
 * Deliberately not backed up. This is telemetry about one installation's
 * health; "the dream worker ran on Tuesday" restored onto a new device is
 * false, and the audit's `DERIVED_OR_TRANSIENT` list is where that is recorded.
 */
@Entity(
    tableName = "worker_runs",
    indices = [Index("worker", "startedAt"), Index("startedAt")],
)
data class WorkerRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Simple class name, e.g. `DreamWorker`. */
    val worker: String,
    val startedAt: Long,
    val finishedAt: Long = 0L,
    /** [OUTCOME_OK], [OUTCOME_SKIPPED] or [OUTCOME_FAILED]. */
    val outcome: String = OUTCOME_OK,
    /**
     * One line about what happened, in the terms the user would ask in:
     * "3 summaries", "nothing to consolidate", "no background model set".
     *
     * The skip reasons matter more than the failures. Most of Aura's
     * background work no-ops on a missing precondition — a disabled toggle, an
     * unset model — and that is precisely the state that looks identical to
     * working.
     */
    val detail: String = "",
) {
    companion object {
        const val OUTCOME_OK = "ok"
        const val OUTCOME_SKIPPED = "skipped"
        const val OUTCOME_FAILED = "failed"
    }
}

@Dao
interface WorkerRunDao {
    @Insert
    suspend fun insert(row: WorkerRunEntity): Long

    @Query("UPDATE worker_runs SET finishedAt = :finishedAt, outcome = :outcome, detail = :detail WHERE id = :id")
    suspend fun finish(id: Long, finishedAt: Long, outcome: String, detail: String)

    /** The most recent run of every worker, which is what a health view shows. */
    @Query(
        "SELECT * FROM worker_runs WHERE id IN " +
            "(SELECT MAX(id) FROM worker_runs GROUP BY worker) ORDER BY startedAt DESC",
    )
    suspend fun latestPerWorker(): List<WorkerRunEntity>

    @Query("SELECT * FROM worker_runs ORDER BY startedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int = 50): List<WorkerRunEntity>

    @Query("SELECT COUNT(*) FROM worker_runs WHERE worker = :worker AND outcome = :outcome AND startedAt >= :since")
    suspend fun countSince(worker: String, outcome: String, since: Long): Int

    /** Keep the table small; this is a log, not a record. */
    @Query("DELETE FROM worker_runs WHERE startedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("DELETE FROM worker_runs")
    suspend fun deleteAll()
}
