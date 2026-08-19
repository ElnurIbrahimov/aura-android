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
 *
 * Schema policy: v1 was the original 1-table release. v2 (2026-07-23)
 * added the routines, kg_edge_proposals, and contradictions tables
 * to back the v2 9-phase pipeline. The MIGRATION_1_2 in
 * [DreamConsolidationModule] creates the new tables and their indices
 * on upgrade; fresh installs at v2 get both directly from the schema
 * declaration. Upgrading installs got no indices at all until
 * 2026-08-19, which Room rejects at open time.
 */
@Database(
    entities = [
        DreamSummaryEntity::class,
        RoutineEntity::class,
        KgEdgeProposalEntity::class,
        ContradictionEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class DreamConsolidationDatabase : RoomDatabase() {
    abstract fun dreamConsolidationDao(): DreamConsolidationDao
    abstract fun routineDao(): RoutineDao
    abstract fun kgEdgeProposalDao(): KgEdgeProposalDao
    abstract fun contradictionDao(): ContradictionDao
}
