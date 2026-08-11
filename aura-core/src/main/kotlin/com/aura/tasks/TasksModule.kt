package com.aura.tasks

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
object TasksModule {

    /**
     * Migration 1→2: adds the `reminders` table for [ReminderEntity].
     * The `tasks` table is unchanged from v1. Existing task rows survive
     * untouched because the columns and constraints are identical.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS reminders (
                    id TEXT NOT NULL PRIMARY KEY,
                    message TEXT NOT NULL,
                    triggerAt INTEGER NOT NULL,
                    createdAt INTEGER NOT NULL,
                    taskId TEXT NOT NULL
                )
                """.trimIndent(),
            )
        }
    }

    /**
     * Migration 2→3: adds indexes that eliminate the full-table scans on
     * the two most-hit queries:
     *  - tasks.status alone (allPending) and tasks(status, dueAt) (dueInRange)
     *  - reminders.triggerAt (observeUpcoming)
     *
     * IF NOT EXISTS so the migration is safe to re-run.
     */
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_status ON tasks(status)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_tasks_status_dueAt ON tasks(status, dueAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_reminders_triggerAt ON reminders(triggerAt)")
        }
    }

    /** Migration 3→4: durable reminder lifecycle and recurring scheduling. */
    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE reminders ADD COLUMN workId TEXT NOT NULL DEFAULT ''")
            db.execSQL("UPDATE reminders SET workId = id WHERE workId = ''")
            db.execSQL("ALTER TABLE reminders ADD COLUMN recurrence TEXT NOT NULL DEFAULT 'none'")
            db.execSQL("ALTER TABLE reminders ADD COLUMN status TEXT NOT NULL DEFAULT 'scheduled'")
            db.execSQL("ALTER TABLE reminders ADD COLUMN firedAt INTEGER")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_reminders_status_triggerAt ON reminders(status, triggerAt)",
            )
        }
    }

    /** Migration 4→5: adds recurrence column to tasks for recurring agent schedules. */
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE tasks ADD COLUMN recurrence TEXT")
        }
    }

    /**
     * Migration 5→6: gives tasks an attention model.
     *
     * `priority` records what you said a task was worth when you wrote it down
     * and never moves again. `salience` is what the evidence says it is worth
     * now — and the two disagree constantly, which is the point.
     */
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE tasks ADD COLUMN salience REAL NOT NULL DEFAULT 1.0")
            db.execSQL("ALTER TABLE tasks ADD COLUMN lastTouchedAt INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE tasks ADD COLUMN deferCount INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE tasks ADD COLUMN quietSince INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE tasks ADD COLUMN revivedReason TEXT NOT NULL DEFAULT ''")
            // Existing tasks start fully bright and are dimmed by the first
            // decay pass on their real evidence, rather than being retroactively
            // judged by a rule they were never created under.
            db.execSQL("UPDATE tasks SET lastTouchedAt = createdAt WHERE lastTouchedAt = 0")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_tasks_status_salience ON tasks(status, salience)",
            )
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TaskDatabase =
        RoomConfig.builder(
            context,
            TaskDatabase::class.java,
            "aura-tasks.db",
            migrations = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6),
        ).build()

    @Provides
    fun provideTaskDao(db: TaskDatabase): TaskDao = db.taskDao()

    @Provides
    fun provideReminderDao(db: TaskDatabase): ReminderDao = db.reminderDao()
}
