package com.aura.dream

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aura.data.RoomConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for the dream consolidator's Room database.
 *
 * Schema policy: v2 added the routines, kg_edge_proposals, and
 * contradictions tables. The MIGRATION_1_2 below creates the three
 * new tables **and their seven indices** on upgrade; fresh installs at
 * v2 get both directly from the schema declaration. The migration has
 * to state the indices itself — Room only generates them for a fresh
 * install, and it validates them on every open.
 */
@Module
@InstallIn(SingletonComponent::class)
object DreamConsolidationModule {

    /**
     * v1 -> v2: create routines, kg_edge_proposals, contradictions.
     *
     * Each table needs its CREATE TABLE **and** every CREATE INDEX the entity
     * declares. This comment used to say the opposite — that Room generates the
     * indices from the `@Index` annotations, so writing them here was unnecessary
     * — and that sentence is what shipped the bug. Room generates indices in
     * `createAllTables`, which runs only for a fresh install. A hand-written
     * [Migration] gets nothing for free, and Room's `TableInfo` validation
     * compares indices when the database is opened: a v1 install upgrading here
     * landed on v2 with three correct tables, zero indices, and an
     * `IllegalStateException: Migration didn't properly handle` on first touch.
     *
     * The two UNIQUE indices are load-bearing beyond query speed. They are the
     * dedup mechanism the DAOs' `OnConflictStrategy.REPLACE` upserts rely on —
     * without `index_routines_signature`, a routine is written again on every
     * cycle instead of being replaced, and without
     * `index_kg_edge_proposals_fromNodeId_toNodeId`, a proposal the user already
     * dismissed comes back.
     *
     * The SQL below is copied from `schemas/…DreamConsolidationDatabase/2.json`,
     * which is the only trustworthy source for it.
     */
    internal val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS routines (
                    id TEXT NOT NULL PRIMARY KEY,
                    signature TEXT NOT NULL,
                    displayLabel TEXT NOT NULL,
                    occurrenceCount INTEGER NOT NULL,
                    distinctConversations INTEGER NOT NULL,
                    sourceConversationIds TEXT NOT NULL,
                    firstSeenAt INTEGER NOT NULL,
                    lastSeenAt INTEGER NOT NULL,
                    description TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS kg_edge_proposals (
                    id TEXT NOT NULL PRIMARY KEY,
                    fromNodeId TEXT NOT NULL,
                    toNodeId TEXT NOT NULL,
                    fromLabel TEXT NOT NULL,
                    toLabel TEXT NOT NULL,
                    similarity REAL NOT NULL,
                    proposedEdge TEXT NOT NULL,
                    status TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    decidedAt INTEGER
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS contradictions (
                    id TEXT NOT NULL PRIMARY KEY,
                    olderSummaryId TEXT NOT NULL,
                    newerSummaryId TEXT NOT NULL,
                    olderText TEXT NOT NULL,
                    newerText TEXT NOT NULL,
                    triggerPhrase TEXT NOT NULL,
                    confidence REAL NOT NULL,
                    status TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    resolvedAt INTEGER
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_routines_occurrenceCount` ON `routines` (`occurrenceCount`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_routines_signature` ON `routines` (`signature`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_kg_edge_proposals_status` ON `kg_edge_proposals` (`status`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_kg_edge_proposals_similarity` ON `kg_edge_proposals` (`similarity`)")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_kg_edge_proposals_fromNodeId_toNodeId` " +
                    "ON `kg_edge_proposals` (`fromNodeId`, `toNodeId`)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_contradictions_status` ON `contradictions` (`status`)")
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS " +
                    "`index_contradictions_olderSummaryId_newerSummaryId` " +
                    "ON `contradictions` (`olderSummaryId`, `newerSummaryId`)",
            )
        }
    }

    /**
     * v2 -> v3: link contradictions to the beliefs they were detected
     * between, so belief revision can record what it resolved. Both
     * columns are nullable — the existing summary-linked detector keeps
     * writing rows with these null, while the belief-linked detector
     * populates them.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE contradictions ADD COLUMN olderBeliefId TEXT")
            db.execSQL("ALTER TABLE contradictions ADD COLUMN newerBeliefId TEXT")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): DreamConsolidationDatabase = RoomConfig.builder(
        context,
        DreamConsolidationDatabase::class.java,
        "aura-dream.db",
        migrations = arrayOf(MIGRATION_1_2, MIGRATION_2_3),
    ).build()

    @Provides
    fun provideDreamConsolidationDao(
        db: DreamConsolidationDatabase,
    ): DreamConsolidationDao = db.dreamConsolidationDao()

    @Provides
    fun provideRoutineDao(
        db: DreamConsolidationDatabase,
    ): RoutineDao = db.routineDao()

    @Provides
    fun provideKgEdgeProposalDao(
        db: DreamConsolidationDatabase,
    ): KgEdgeProposalDao = db.kgEdgeProposalDao()

    @Provides
    fun provideContradictionDao(
        db: DreamConsolidationDatabase,
    ): ContradictionDao = db.contradictionDao()
}
