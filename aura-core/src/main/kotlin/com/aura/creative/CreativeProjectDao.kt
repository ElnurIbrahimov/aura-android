package com.aura.creative

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * ## Never use [upsert] to modify an existing project
 *
 * `OnConflictStrategy.REPLACE` compiles to SQLite's `INSERT OR REPLACE`, which
 * is a **DELETE followed by an INSERT** — not an update. Three tables declare
 * `onDelete = CASCADE` against `creative_projects`: [CreativeArtifactEntity],
 * [CreativeBranchEntity] and [CreativeGenerationJobEntity]. Replacing a project
 * row therefore destroys every artifact, revision, branch and generation job
 * belonging to it.
 *
 * Not theoretical. A long-form run drafted thirteen scenes on a device and
 * finished with the project holding thirteen beats marked "drafted", each naming
 * an artifact id — and `creative_artifacts`, `creative_revisions`,
 * `creative_branches` and `creative_generation_jobs` all holding **zero rows**.
 * Each scene was written, then its beat was marked, and marking the beat is what
 * deleted the scene. Saving the World tab did the same thing.
 *
 * The targeted updates below name their columns and cannot cascade. [upsert] and
 * [insertAll] remain for genuine inserts — creation, and backup restore into
 * emptied tables.
 */
@Dao
interface CreativeProjectDao {
    /** Insert only. To change an existing row, use one of the targeted updates below. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
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