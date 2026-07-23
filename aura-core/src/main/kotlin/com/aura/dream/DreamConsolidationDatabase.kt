package com.aura.dream

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Separate Room database for the dream consolidator.
 *
 * Why a separate file: the consolidator writes infrequently (once per
 * day at most) and a small, isolated DB keeps the schema churn of
 * dream iterations off the main `memory.db` which the chat loop reads
 * on every turn. If dream is disabled, this DB never grows past
 * version 1.
 */
@Database(
    entities = [DreamSummaryEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class DreamConsolidationDatabase : RoomDatabase() {
    abstract fun dreamConsolidationDao(): DreamConsolidationDao
}
