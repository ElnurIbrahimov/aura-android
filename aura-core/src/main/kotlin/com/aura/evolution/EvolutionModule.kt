package com.aura.evolution

import android.content.Context
import androidx.room.Room
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
        migrations = emptyArray(),
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
