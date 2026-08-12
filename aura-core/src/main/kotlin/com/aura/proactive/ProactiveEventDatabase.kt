package com.aura.proactive

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProactiveEventEntity::class,
        ProactiveInteractionEntity::class,
        ProactiveOutcomeEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class ProactiveEventDatabase : RoomDatabase() {
    abstract fun proactiveEventDao(): ProactiveEventDao
    abstract fun proactiveInteractionDao(): ProactiveInteractionDao
    abstract fun proactiveOutcomeDao(): ProactiveOutcomeDao
}
