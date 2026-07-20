package com.aura.agent

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [AgentEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun agentDao(): AgentDao
}