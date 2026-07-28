package com.aura.agent

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [StrategyBanditEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class StrategyBanditDatabase : RoomDatabase() {
    abstract fun dao(): StrategyBanditDao
}
