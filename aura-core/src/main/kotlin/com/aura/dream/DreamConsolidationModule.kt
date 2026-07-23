package com.aura.dream

import android.content.Context
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
 * Schema policy: v1 is the initial release; no migrations yet. When v2
 * ships (e.g. adding clusterQualityScore column for ranking), define
 * MIGRATION_1_2 here and append it to the array.
 */
@Module
@InstallIn(SingletonComponent::class)
object DreamConsolidationModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context,
    ): DreamConsolidationDatabase = RoomConfig.builder(
        context,
        DreamConsolidationDatabase::class.java,
        "aura-dream.db",
        migrations = emptyArray(),
    ).build()

    @Provides
    fun provideDreamConsolidationDao(
        db: DreamConsolidationDatabase,
    ): DreamConsolidationDao = db.dreamConsolidationDao()
}
