package com.aura.evolution

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aura.data.RoomConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object EvolutionModule {

    // Exposed for migration tests in androidTest source set.
    val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

    @Provides
    @Singleton
    fun provideEvolutionDatabase(
        @ApplicationContext context: Context,
    ): EvolutionDatabase = RoomConfig.builder(
        context,
        EvolutionDatabase::class.java,
        "evolution.db",
        migrations = ALL_MIGRATIONS,
    ).build()

    @Provides
    @Singleton
    fun provideEvolutionEvidenceDao(db: EvolutionDatabase): EvolutionEvidenceDao = db.evidenceDao()

    @Provides
    @Singleton
    fun provideEvolutionCandidateDao(db: EvolutionDatabase): EvolutionCandidateDao = db.candidateDao()

    @Provides
    @Singleton
    fun provideEvolutionProposalDao(db: EvolutionDatabase): EvolutionProposalDao = db.proposalDao()

    @Provides
    @Singleton
    fun provideEvolutionRevisionDao(db: EvolutionDatabase): EvolutionRevisionDao = db.revisionDao()

    @Provides
    @Singleton
    fun provideEvolutionSettingsDao(db: EvolutionDatabase): EvolutionSettingsDao = db.settingsDao()
}

internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE evolution_settings ADD COLUMN totalRuns INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE evolution_settings ADD COLUMN totalCandidates INTEGER NOT NULL DEFAULT 0")
    }
}

internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE evolution_settings ADD COLUMN shadowEnabled INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * Evolution rebuild (Phase 4):
 * 1. Collapse historic duplicate candidates — keep the newest row per
 *    (domain, action, targetId). SQLite's bare-column-with-MAX() semantics
 *    guarantee the surviving rowid belongs to a max-createdAt row per group.
 * 2. Add the D5 dedup lookup index (non-unique by design).
 * 3. Clean up rows referencing removed actions: delete their candidates,
 *    supersede their still-open proposals (applied history rows are kept).
 */
internal val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Duplicate-candidate collapse (keep newest per key). Must run
        //    before the index is created so index creation is cheap and the
        //    one-row-per-key invariant holds from the first post-migration read.
        db.execSQL(
            """
            DELETE FROM evolution_candidates WHERE rowid NOT IN (
                SELECT rowid FROM (
                    SELECT rowid, MAX(createdAt) FROM evolution_candidates
                    GROUP BY domain, action, targetId
                )
            )
            """.trimIndent()
        )
        // 2. D5 dedup index — exact Room-generated name.
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_evolution_candidates_domain_action_targetId` " +
                "ON `evolution_candidates` (`domain`, `action`, `targetId`)"
        )
        // 3. Removed-action cleanup.
        val keptActions = "('PATCH_SKILL', 'RETIRE_SKILL', 'PROMOTE_TO_HAND', 'CONSOLIDATE_MEMORIES')"
        db.execSQL("DELETE FROM evolution_candidates WHERE action NOT IN $keptActions")
        val now = System.currentTimeMillis()
        db.execSQL(
            "UPDATE evolution_proposals SET status = 'SUPERSEDED', resolvedAt = $now, updatedAt = $now, " +
                "outcomeNote = 'superseded: action removed in evolution rebuild' " +
                "WHERE action NOT IN $keptActions AND status IN ('PENDING_REVIEW', 'APPROVED', 'APPLY_FAILED')"
        )
    }
}

