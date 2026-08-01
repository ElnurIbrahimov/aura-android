package com.aura.agent

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StrategyBanditModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): StrategyBanditDatabase =
        Room.databaseBuilder(context, StrategyBanditDatabase::class.java, "strategy_bandit.db")
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()

    @Provides
    fun provideStrategyBanditDao(db: StrategyBanditDatabase): StrategyBanditDao = db.dao()
}
