package com.aura.proactive

import android.content.Context
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
object ProactiveEventModule {

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Migration 1→2: adds the `payload` column to `proactive_events`
            // for structured event routing data. Existing rows get the default
            // empty string, matching the entity's `payload: String = ""` default.
            db.execSQL(
                "ALTER TABLE proactive_events ADD COLUMN payload TEXT NOT NULL DEFAULT ''",
            )
        }
    }

    /**
     * Migration 2→3: adds an index on `timestamp` so the `recent(100)` and
     * `countSince()` queries on the Home screen badge are O(log n) instead
     * of a full table scan. Without this, the badge cost grows linearly
     * with the number of accumulated events.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS index_proactive_events_timestamp ON proactive_events(timestamp)")
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS proactive_interactions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    eventId INTEGER NOT NULL,
                    action TEXT NOT NULL,
                    feedback TEXT NOT NULL DEFAULT '',
                    timestamp INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS index_proactive_interactions_eventId ON proactive_interactions(eventId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_proactive_interactions_timestamp ON proactive_interactions(timestamp)")
        }
    }

    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE proactive_events ADD COLUMN correlationTag TEXT NOT NULL DEFAULT ''")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ProactiveEventDatabase =
        RoomConfig.builder(
            context,
            ProactiveEventDatabase::class.java,
            "aura-proactive.db",
            migrations = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5),
        ).build()

    @Provides
    fun provideProactiveEventDao(db: ProactiveEventDatabase): ProactiveEventDao = db.proactiveEventDao()

    @Provides
    fun provideProactiveInteractionDao(db: ProactiveEventDatabase): ProactiveInteractionDao = db.proactiveInteractionDao()
}
