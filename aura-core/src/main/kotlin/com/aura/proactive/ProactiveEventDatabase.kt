package com.aura.proactive

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProactiveEventEntity::class,
        ProactiveInteractionEntity::class,
        ProactiveOutcomeEntity::class,
        com.aura.health.WorkerRunEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class ProactiveEventDatabase : RoomDatabase() {
    abstract fun proactiveEventDao(): ProactiveEventDao
    abstract fun proactiveInteractionDao(): ProactiveInteractionDao
    abstract fun proactiveOutcomeDao(): ProactiveOutcomeDao
    abstract fun workerRunDao(): com.aura.health.WorkerRunDao
}
