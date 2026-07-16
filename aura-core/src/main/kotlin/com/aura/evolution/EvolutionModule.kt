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

    @Provides
    @Singleton
    fun provideEvolutionDatabase(
        @ApplicationContext context: Context,
    ): EvolutionDatabase = RoomConfig.builder(
        context,
        EvolutionDatabase::class.java,
        "evolution.db",
        migrations = arrayOf(MIGRATION_2_3),
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

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE evolution_settings ADD COLUMN totalRuns INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE evolution_settings ADD COLUMN totalCandidates INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE evolution_settings ADD COLUMN shadowEnabled INTEGER NOT NULL DEFAULT 0")
    }
}

