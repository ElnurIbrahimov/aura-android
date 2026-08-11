package com.aura.creative

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * ## The incident this DAO was hardened against
 *
 * `OnConflictStrategy.REPLACE` compiles to SQLite's `INSERT OR REPLACE`, which
 * is a **DELETE followed by an INSERT** — not an update. Three tables declare
 * `onDelete = CASCADE` against `creative_projects`: [CreativeArtifactEntity],
 * [CreativeBranchEntity] and [CreativeGenerationJobEntity]. Replacing a project
 * row therefore destroyed every artifact, revision, branch and generation job
 * belonging to it.
 *
 * Not theoretical. A long-form run drafted thirteen scenes on a device and
 * finished with the project holding thirteen beats marked "drafted", each naming
 * an artifact id — and `creative_artifacts`, `creative_revisions`,
 * `creative_branches` and `creative_generation_jobs` all holding **zero rows**.
 * Each scene was written, then its beat was marked, and marking the beat is what
 * deleted the scene. Saving the World tab did the same thing.
 *
 * The original fix added the targeted updates below, which name their columns
 * and cannot cascade, and left [upsert]/[insertAll] on REPLACE "for genuine
 * inserts". That was safe here and wrong everywhere else: the same defect was
 * still live on five other CASCADE parents, including [CreativeArtifactDao] one
 * level down, where writing a revision deleted every revision of that artifact.
 * A fix applied to one of six places.
 *
 * [upsert] is now a real `@Upsert` — an UPDATE-or-INSERT that fires no delete —
 * so it is safe on an existing row, and `CascadeParentReplaceAuditTest` fails
 * the build if REPLACE returns anywhere in this tree. The targeted updates
 * remain because naming your columns is still the clearer way to change one
 * field, and because they do not require reading the row first.
 */
@Dao
interface CreativeProjectDao {
    @Upsert
    suspend fun upsert(project: CreativeProjectEntity)

    @Query("UPDATE creative_projects SET worldJson = :worldJson, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateWorld(id: String, worldJson: String, updatedAt: Long)

    @Query(
        """
        UPDATE creative_projects
        SET name = :name, description = :description, genre = :genre,
            tone = :tone, templateId = :templateId, updatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updateMetadata(
        id: String,
        name: String,
        description: String,
        genre: String,
        tone: String,
        templateId: String,
        updatedAt: Long,
    )

    @Query(
        "UPDATE creative_projects SET turnCount = :turnCount, lastSessionEnded = :lastSessionEnded, " +
            "updatedAt = :updatedAt WHERE id = :id",
    )
    suspend fun updateTurn(id: String, turnCount: Int, lastSessionEnded: Long, updatedAt: Long)

    @Upsert
    suspend fun insertAll(projects: List<CreativeProjectEntity>)

    @Query("SELECT * FROM creative_projects ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<CreativeProjectEntity>>

    @Query("SELECT * FROM creative_projects ORDER BY updatedAt DESC")
    suspend fun allForBackup(): List<CreativeProjectEntity>

    @Query("SELECT * FROM creative_projects WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CreativeProjectEntity?

    @Query("DELETE FROM creative_projects WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM creative_projects")
    suspend fun deleteAll()
}