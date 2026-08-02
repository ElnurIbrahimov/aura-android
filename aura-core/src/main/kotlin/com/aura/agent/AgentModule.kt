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
object AgentModule {

    @Provides
    @Singleton
    fun provideAgentDatabase(@ApplicationContext context: Context): AgentDatabase =
        com.aura.data.RoomConfig.builder(context, AgentDatabase::class.java, "agents.db", arrayOf())
            .build()

    @Provides
    @Singleton
    fun provideAgentDao(db: AgentDatabase): AgentDao = db.agentDao()
}