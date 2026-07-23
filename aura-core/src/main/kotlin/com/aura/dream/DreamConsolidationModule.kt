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
 * new tables on upgrade; fresh installs at v2 get them directly from
 * the schema declaration.
 */
@Module
@InstallIn(SingletonComponent::class)
object DreamConsolidationModule {

    /**
     * v1 -> v2: create routines, kg_edge_proposals, contradictions.
     * All three are new tables (not column additions), so a single
     * CREATE TABLE per table does it. The unique indices are
     * declared on the entities; CREATE INDEX statements here
     * aren't needed because Room generates them from the @Index
     * annotations when the schema is exported.
     */
    private val MIGRATION_1_2 = object : Migration(1, 2) {
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
        migrations = arrayOf(MIGRATION_1_2),
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
