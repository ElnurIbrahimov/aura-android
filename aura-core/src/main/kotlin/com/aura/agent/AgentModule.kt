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
        com.aura.data.RoomConfig.builder(
            context,
            AgentDatabase::class.java,
            "agents.db",
            AgentDatabase.ALL_MIGRATIONS,
        ).build()

    @Provides
    @Singleton
    fun provideAgentDao(db: AgentDatabase): AgentDao = db.agentDao()

    @Provides
    @Singleton
    fun provideAgentStateDao(db: AgentDatabase): com.aura.agent.state.AgentStateDao =
        db.agentStateDao()

    @Provides
    @Singleton
    fun provideAgentRelationshipDao(db: AgentDatabase): com.aura.agent.state.AgentRelationshipDao =
        db.agentRelationshipDao()

    @Provides
    @Singleton
    fun provideAgentObservationDao(db: AgentDatabase): com.aura.agent.state.AgentObservationDao =
        db.agentObservationDao()

    @Provides
    @Singleton
    fun provideAgentStateStore(
        stateDao: com.aura.agent.state.AgentStateDao,
        relDao: com.aura.agent.state.AgentRelationshipDao,
        obsDao: com.aura.agent.state.AgentObservationDao,
    ): com.aura.agent.state.AgentStateStore =
        com.aura.agent.state.AgentStateStore(stateDao, relDao, obsDao)
}