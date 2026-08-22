package com.aura.memory

import androidx.room.Dao
import androidx.room.Query

/**
 * Direct access to the FTS index.
 *
 * Recall itself does not use this — it goes through [MemoryDao.searchFts],
 * which joins back to `memories` for the scope filter and the decay ordering.
 * This exists for the index's own invariants: the triggers installed by
 * `MIGRATION_16_17` are the only writer, and a test that asserts they fire
 * needs to read the index directly rather than inferring it from a join.
 */
@Dao
interface MemoryFtsDao {

    /** Rows currently in the index. Should equal `SELECT COUNT(*) FROM memories`. */
    @Query("SELECT COUNT(*) FROM memories_fts")
    suspend fun count(): Int

    /**
     * The indexed content for a memory id, or null when the triggers missed it.
     *
     * Kept although only tests call it: it is the only way to observe a write
     * production really performs, and a write nothing can read back is a write
     * nothing can prove.
     * The index is maintained by SQL triggers, which no Kotlin code executes —
     * so this is the only way to tell a working trigger from a silently empty
     * index, and an empty index returns no candidates and no error.
     */
    @Query("SELECT content FROM memories_fts WHERE memoryId = :memoryId")
    suspend fun contentFor(memoryId: String): String?
}
