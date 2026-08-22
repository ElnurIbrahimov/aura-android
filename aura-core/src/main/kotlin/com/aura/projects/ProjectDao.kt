package com.aura.projects

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes for [ProjectEntity].
 *
 * Every write here is either an `@Upsert` or a column-named `UPDATE`, and none
 * is `@Insert(onConflict = REPLACE)`. `projects` is a CASCADE parent of
 * `project_notes`, and `INSERT OR REPLACE` is a DELETE followed by an INSERT —
 * so re-saving a project to bump its turn count would delete its entire ledger
 * and then put the parent back, leaving a table that looks intact. That is not
 * hypothetical: [com.aura.creative.CreativeProjectDao] carries the post-mortem
 * of it happening to thirteen drafted scenes, and `CascadeParentReplaceAuditTest`
 * exists to stop it recurring. It will fail this file if anyone changes it back.
 */
@Dao
interface ProjectDao {

    @Upsert
    suspend fun upsert(project: ProjectEntity)

    @Upsert
    suspend fun upsertAll(projects: List<ProjectEntity>)

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun byId(id: String): ProjectEntity?

    /**
     * Resolve by name, case-insensitively.
     *
     * `project_state` is called by a model with whatever the user typed, and
     * "arc-agi-2" has to find "ARC-AGI-2". SQLite's `LIKE` is case-insensitive
     * for ASCII by default and carries no wildcards here, so this is an equality
     * test that tolerates case rather than a pattern match.
     */
    @Query("SELECT * FROM projects WHERE name LIKE :name LIMIT 1")
    suspend fun byName(name: String): ProjectEntity?

    /** Live projects, most recently worked first — the order the picker wants. */
    @Query("SELECT * FROM projects WHERE status = 'active' ORDER BY lastTurnAt DESC, updatedAt DESC")
    fun observeActive(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects ORDER BY lastTurnAt DESC, updatedAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE status = 'active' ORDER BY lastTurnAt DESC LIMIT :limit")
    suspend fun active(limit: Int = 50): List<ProjectEntity>

    /** Targeted, so attributing a turn cannot cascade. See the KDoc above. */
    @Query(
        "UPDATE projects SET lastTurnAt = :at, turnCount = turnCount + 1, updatedAt = :at " +
            "WHERE id = :id",
    )
    suspend fun touch(id: String, at: Long)

    @Query("UPDATE projects SET status = :status, updatedAt = :at WHERE id = :id")
    suspend fun setStatus(id: String, status: String, at: Long)

    @Query("SELECT * FROM projects ORDER BY createdAt ASC")
    suspend fun allForBackup(): List<ProjectEntity>

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM projects")
    suspend fun deleteAll()
}

/**
 * Reads and writes for [ProjectNoteEntity].
 *
 * Insert is a plain `@Insert` — ABORT on conflict — rather than REPLACE. A note
 * id is a fresh UUID, so a primary-key collision is a bug and should throw
 * loudly instead of silently overwriting a row that is somebody's audit trail.
 * Supersession is an explicit UPDATE, never an id collision: the whole value of
 * this table is that the displaced row survives.
 */
@Dao
interface ProjectNoteDao {

    @Insert
    suspend fun insert(note: ProjectNoteEntity)

    @Upsert
    suspend fun upsertAll(notes: List<ProjectNoteEntity>)

    @Query("SELECT * FROM project_notes WHERE id = :id")
    suspend fun byId(id: String): ProjectNoteEntity?

    /**
     * The rows a new note would displace.
     *
     * A **list**, though the store's invariant is that at most one is active per
     * `(projectId, kind, subject)`. `ProjectStore.recordNote` inserts before it
     * supersedes — `SceneLedger.reconcile`'s ordering, for its reason: the write
     * runs unsupervised on a worker, so interruption is expected, and superseding
     * first would retire a row with no replacement and lose the history for good.
     * Inserting first fails toward two active rows instead, which is visible and
     * recoverable. Returning every one of them is what makes it recoverable —
     * `LIMIT 1` would repair one duplicate per pass and strand the rest.
     */
    @Query(
        "SELECT * FROM project_notes WHERE projectId = :projectId AND kind = :kind " +
            "AND subject = :subject AND state = 'active' ORDER BY createdAt DESC",
    )
    suspend fun activeBySubject(projectId: String, kind: String, subject: String): List<ProjectNoteEntity>

    /** Everything currently true about a project. What `project_state` reports. */
    @Query(
        "SELECT * FROM project_notes WHERE projectId = :projectId AND state = 'active' " +
            "ORDER BY kind ASC, createdAt DESC LIMIT :limit",
    )
    suspend fun activeFor(projectId: String, limit: Int = 100): List<ProjectNoteEntity>

    /**
     * Subjects already recorded for a project, for the extraction prompt.
     *
     * Handed to the model as the preferred vocabulary so it writes "payments"
     * again rather than "payment provider", which would sit beside the first as
     * a second active row instead of superseding it. Drift here is the main
     * thing that stops the ledger converging, and this is the cheap half of the
     * defence — the visible ledger is the other half.
     */
    @Query("SELECT DISTINCT subject FROM project_notes WHERE projectId = :projectId AND state = 'active'")
    suspend fun activeSubjects(projectId: String): List<String>

    /** The history behind one subject, newest first. */
    @Query(
        "SELECT * FROM project_notes WHERE projectId = :projectId AND kind = :kind " +
            "AND subject = :subject ORDER BY createdAt DESC LIMIT :limit",
    )
    suspend fun historyFor(projectId: String, kind: String, subject: String, limit: Int = 20): List<ProjectNoteEntity>

    @Query(
        "UPDATE project_notes SET state = 'superseded', supersededBy = :supersededBy " +
            "WHERE id = :id AND state = 'active'",
    )
    suspend fun supersede(id: String, supersededBy: String): Int

    @Query("UPDATE project_notes SET state = 'resolved', resolvedAt = :at WHERE id = :id AND state = 'active'")
    suspend fun resolve(id: String, at: Long): Int

    @Query("SELECT * FROM project_notes ORDER BY createdAt ASC")
    suspend fun allForBackup(): List<ProjectNoteEntity>

    @Query("DELETE FROM project_notes WHERE projectId = :projectId")
    suspend fun deleteForProject(projectId: String)

    @Query("DELETE FROM project_notes")
    suspend fun deleteAll()
}
