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
 * - Destructive fallback on downgrade **only** under `-PdevDb`; upgrades always
 *   require explicit migrations.
 *
 * That last line used to read "debug-only", which sounds like a developer
 * safeguard and was not one: README's install instructions build
 * `:app:assembleDebug` and `adb install` it, so the sideloaded APK on the phone
 * was the build with the wipe enabled. Installing a previous APK — the ordinary
 * way to back out of a bad build — emptied all eleven databases silently.
 *
 * With the flag off, a downgrade throws `Can't downgrade database from version
 * X to Y` when the database is opened, so the app refuses to launch until the
 * newer APK is reinstalled. Louder and worse-looking; recoverable, which the
 * wipe is not.
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
                if (BuildConfig.ALLOW_DESTRUCTIVE_DOWNGRADE) {
                    fallbackToDestructiveMigrationOnDowngrade()
                }
            }
        return builder
    }
}
