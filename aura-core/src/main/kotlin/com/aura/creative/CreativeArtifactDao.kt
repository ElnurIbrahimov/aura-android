package com.aura.creative

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * ## The same defect [CreativeProjectDao] documents, one level down
 *
 * That KDoc explains why `INSERT OR REPLACE` on `creative_projects` destroyed
 * every artifact belonging to a project, and fixed it with targeted updates.
 * `creative_artifacts` is a CASCADE parent too — of `creative_revisions`,
 * `artifact_dependencies` and `continuity_issues` — and it was left on REPLACE.
 *
 * The consequence was worse than the original, because the write path re-saves
 * the artifact on *every* draft: `CreativeArtifactStore.addRevision` inserted
 * the new revision and then re-saved the artifact to point `currentRevisionId`
 * at it. The re-save deleted the artifact row, cascade-deleted every revision
 * of that artifact **including the one written on the line above**, and then
 * re-inserted the artifact pointing at a revision that no longer existed.
 * `archive()` and `restore()` did the same. Revision history could not
 * accumulate; the artifact row survived, so the Creative screens still listed
 * work whose content was gone.
 *
 * `@Upsert` is a genuine UPDATE-or-INSERT and fires no delete, so children
 * survive. `CascadeParentReplaceAuditTest` now fails the build for the whole
 * class of defect rather than for one instance of it.
 */
@Dao
interface CreativeArtifactDao {
    @Upsert
    suspend fun upsert(artifact: CreativeArtifactEntity)

    @Upsert
    suspend fun insertAll(artifacts: List<CreativeArtifactEntity>)

    @Query("SELECT * FROM creative_artifacts WHERE projectId = :projectId ORDER BY updatedAt DESC")
    fun observeForProject(projectId: kotlin.String): Flow<List<CreativeArtifactEntity>>

    @Query("SELECT * FROM creative_artifacts WHERE projectId = :projectId AND kind = :kind ORDER BY updatedAt DESC")
    suspend fun forProjectByKind(projectId: kotlin.String, kind: kotlin.String): List<CreativeArtifactEntity>

    @Query("SELECT * FROM creative_artifacts WHERE id = :id LIMIT 1")
    suspend fun getById(id: kotlin.String): CreativeArtifactEntity?

    @Query("SELECT * FROM creative_artifacts WHERE projectId = :projectId ORDER BY updatedAt DESC")
    suspend fun allForProject(projectId: kotlin.String): List<CreativeArtifactEntity>

    @Query("SELECT * FROM creative_artifacts ORDER BY updatedAt DESC")
    suspend fun allForBackup(): List<CreativeArtifactEntity>

    @Query("SELECT COUNT(*) FROM creative_artifacts WHERE projectId = :projectId")
    suspend fun countForProject(projectId: kotlin.String): Int

    @Query("DELETE FROM creative_artifacts WHERE id = :id")
    suspend fun delete(id: kotlin.String)

    @Query("DELETE FROM creative_artifacts WHERE projectId = :projectId")
    suspend fun deleteAllForProject(projectId: kotlin.String)

    @Query("DELETE FROM creative_artifacts")
    suspend fun deleteAll()
}

@Dao
interface CreativeRevisionDao {
    // @Upsert, not @Insert(REPLACE). SQLite implements REPLACE as DELETE then
    // INSERT, which fires ON DELETE CASCADE on every child before the row comes
    // back — so re-saving a revision would silently destroy the analysis
    // attached to it. These two were harmless until `creative_analysis` made
    // `creative_revisions` a CASCADE parent for the first time; the change made
    // them dangerous without either of them being touched, which is precisely
    // why `CascadeParentReplaceAuditTest` scans for the shape rather than
    // trusting review. It caught this the first time the suite ran.
    @Upsert
    suspend fun upsert(revision: CreativeRevisionEntity)

    @Upsert
    suspend fun insertAll(revisions: List<CreativeRevisionEntity>)

    @Query("DELETE FROM creative_revisions")
    suspend fun deleteAll()

    @Query("SELECT * FROM creative_revisions WHERE artifactId = :artifactId ORDER BY createdAt DESC")
    suspend fun forArtifact(artifactId: kotlin.String): List<CreativeRevisionEntity>

    @Query("SELECT * FROM creative_revisions WHERE id = :id LIMIT 1")
    suspend fun getById(id: kotlin.String): CreativeRevisionEntity?

    @Query("""WITH RECURSIVE ancestry(id, parentRevisionId, depth) AS (
                SELECT id, parentRevisionId, 0 FROM creative_revisions WHERE id = :revisionId
                UNION ALL
                SELECT cr.id, cr.parentRevisionId, a.depth + 1
                FROM creative_revisions cr INNER JOIN ancestry a ON cr.id = a.parentRevisionId
                WHERE a.depth < 100
              ) SELECT id FROM ancestry ORDER BY depth""")
    suspend fun ancestryChain(revisionId: kotlin.String): List<kotlin.String>

    @Query("SELECT * FROM creative_revisions ORDER BY createdAt DESC")
    suspend fun allForBackup(): List<CreativeRevisionEntity>

    @Query("DELETE FROM creative_revisions WHERE artifactId = :artifactId")
    suspend fun deleteForArtifact(artifactId: kotlin.String)

    /**
     * Current-revision text of this project's scenes containing [term].
     *
     * Joins on `currentRevisionId` rather than scanning every revision, so a
     * scene that has been revised five times contributes its current text once
     * and not six variants of itself.
     *
     * `LIKE '%term%'` cannot use an index and scans the scene rows. That is the
     * right trade at this scale — a long novel on one branch is forty rows —
     * and §3's Gate B records that the embedding business case for anything
     * cleverer is still unproven.
     */
    @Query(
        """
        SELECT r.* FROM creative_revisions r
        INNER JOIN creative_artifacts a ON a.currentRevisionId = r.id
        WHERE a.projectId = :projectId
          AND a.kind = 'scene'
          AND r.id != :excludeRevisionId
          AND r.contentText LIKE '%' || :term || '%'
        ORDER BY r.createdAt DESC
        LIMIT :limit
        """
    )
    suspend fun searchScenes(
        projectId: kotlin.String,
        term: kotlin.String,
        excludeRevisionId: kotlin.String,
        limit: kotlin.Int,
    ): List<CreativeRevisionEntity>
}

@Dao
interface CreativeBranchDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(branch: CreativeBranchEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(branches: List<CreativeBranchEntity>)

    @Query("DELETE FROM creative_branches")
    suspend fun deleteAll()

    @Query("SELECT * FROM creative_branches WHERE projectId = :projectId ORDER BY createdAt ASC")
    suspend fun forProject(projectId: kotlin.String): List<CreativeBranchEntity>

    @Query("SELECT * FROM creative_branches WHERE projectId = :projectId AND status = 'active' ORDER BY createdAt ASC")
    fun observeActive(projectId: kotlin.String): Flow<List<CreativeBranchEntity>>

    @Query("SELECT * FROM creative_branches WHERE id = :id LIMIT 1")
    suspend fun getById(id: kotlin.String): CreativeBranchEntity?

    @Query("SELECT * FROM creative_branches ORDER BY createdAt ASC")
    suspend fun allForBackup(): List<CreativeBranchEntity>

    @Query("DELETE FROM creative_branches WHERE id = :id")
    suspend fun delete(id: kotlin.String)
}

@Dao
interface CreativeGenerationJobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: CreativeGenerationJobEntity)

    @Query("SELECT * FROM creative_generation_jobs WHERE status IN ('queued', 'running', 'waiting_provider') ORDER BY createdAt ASC")
    suspend fun pendingJobs(): List<CreativeGenerationJobEntity>

    @Query("SELECT * FROM creative_generation_jobs WHERE projectId = :projectId ORDER BY createdAt DESC")
    fun observeForProject(projectId: kotlin.String): Flow<List<CreativeGenerationJobEntity>>

    @Query("SELECT * FROM creative_generation_jobs WHERE id = :id LIMIT 1")
    suspend fun getById(id: kotlin.String): CreativeGenerationJobEntity?

    @Query("UPDATE creative_generation_jobs SET status = :status, progress = :progress, updatedAt = :timestamp WHERE id = :id")
    suspend fun updateStatus(id: kotlin.String, status: kotlin.String, progress: Int, timestamp: kotlin.Long)

    @Query("UPDATE creative_generation_jobs SET status = :status, resultArtifactIdsJson = :resultJson, updatedAt = :timestamp WHERE id = :id")
    suspend fun complete(id: kotlin.String, status: kotlin.String, resultJson: kotlin.String, timestamp: kotlin.Long)

    @Query("DELETE FROM creative_generation_jobs WHERE status IN ('succeeded', 'failed', 'cancelled') AND createdAt < :cutoff")
    suspend fun cleanupOld(cutoff: kotlin.Long)
}