package com.aura.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import com.aura.core.BuildConfig

/**
 * Shared Room database builder configuration.
 *
 * Applies:
 * - Schema export (production builds validate migrations).
 * - Explicit migrations.
 * - Debug-only destructive fallback on downgrade so developers don't get blocked
 *   during schema churn; upgrades always require explicit migrations.
 */
object RoomConfig {

    fun <T : RoomDatabase> builder(
        context: Context,
        klass: Class<T>,
        name: String,
        migrations: Array<Migration>,
        /**
         * Optional callback for schema Room does not generate itself.
         *
         * Room's `createAllTables` covers entities, indices and views — not
         * triggers. A database that keeps an FTS index in sync with triggers
         * therefore gets them from its migration on an *upgrade* and from
         * nowhere at all on a *fresh install*, which is the harder case to
         * notice: the index simply stays empty and lexical recall returns
         * nothing, with no error anywhere.
         */
        callback: RoomDatabase.Callback? = null,
    ): RoomDatabase.Builder<T> {
        val builder = Room.databaseBuilder(context, klass, name)
            .addMigrations(*migrations)
            .apply {
                callback?.let { addCallback(it) }
                if (BuildConfig.DEBUG) {
                    fallbackToDestructiveMigrationOnDowngrade()
                }
            }
        return builder
    }
}
