package com.aura.proactive

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `MIGRATION_3_4` creates `proactive_interactions` and its two indices.
 *
 * This file used to assert none of that. It built a fresh head-version database
 * with `Room.inMemoryDatabaseBuilder`, inserted a row, and checked the row came
 * back — which passes on any schema Room can create from the current entities,
 * and would have gone on passing if `MIGRATION_3_4` were deleted outright. A
 * test that cannot fail for the reason it is named after occupies the slot
 * where a real one would go.
 *
 * It now does what its sibling `ProactiveMigration4To5Test` does: build a real
 * v3 database, run the migration against it, and assert on the result.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ProactiveMigration3To4Test {

    /** The v3 schema: events only, no interactions table. */
    private fun v3(context: Context, name: String): SupportSQLiteOpenHelper {
        val callback = object : SupportSQLiteOpenHelper.Callback(3) {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE proactive_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "eventType TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, " +
                        "timestamp INTEGER NOT NULL, payload TEXT NOT NULL DEFAULT '')",
                )
            }
            override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
        }
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(callback)
                .build(),
        )
    }

    @Test
    fun `migration 3 to 4 creates the interaction table on a real v3 database`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = v3(context, "proactive-migrate-3-4")

        helper.writableDatabase.use { db ->
            db.execSQL("INSERT INTO proactive_events (eventType,title,body,timestamp) VALUES ('x','t','b',1)")
            // The table must not exist yet, or the assertion below proves nothing.
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='proactive_interactions'").use {
                assertEquals("proactive_interactions already exists at v3", 0, it.count)
            }
        }

        ProactiveEventModule.MIGRATION_3_4.migrate(helper.writableDatabase)

        helper.writableDatabase.use { db ->
            db.execSQL("INSERT INTO proactive_interactions (eventId,action,timestamp) VALUES (1,'dismissed',2)")
            db.query("SELECT eventId, action, feedback FROM proactive_interactions").use { c ->
                assertEquals(1, c.count)
                c.moveToFirst()
                assertEquals(1L, c.getLong(0))
                assertEquals("dismissed", c.getString(1))
                // The DEFAULT is part of the contract: nothing supplies feedback
                // at insert time and a NOT NULL column with no default would
                // reject the write above.
                assertEquals("", c.getString(2))
            }
        }
        helper.close()
    }

    @Test
    fun `migration 3 to 4 creates both indices`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = v3(context, "proactive-migrate-3-4-indices")
        ProactiveEventModule.MIGRATION_3_4.migrate(helper.writableDatabase)

        helper.writableDatabase.use { db ->
            val names = mutableSetOf<String>()
            db.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='proactive_interactions'").use { c ->
                while (c.moveToNext()) names += c.getString(0)
            }
            // eventId is what `forEvent` filters on and timestamp is what the
            // prune orders by; without them both queries table-scan.
            assertTrue("missing eventId index, got $names", "index_proactive_interactions_eventId" in names)
            assertTrue("missing timestamp index, got $names", "index_proactive_interactions_timestamp" in names)
        }
        helper.close()
    }

    @Test
    fun `running the migration twice is not an error`() {
        // Every statement is IF NOT EXISTS, and a re-run happens whenever a
        // rollback replays a chain.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = v3(context, "proactive-migrate-3-4-twice")
        ProactiveEventModule.MIGRATION_3_4.migrate(helper.writableDatabase)
        ProactiveEventModule.MIGRATION_3_4.migrate(helper.writableDatabase)
        helper.writableDatabase.use { db ->
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='proactive_interactions'").use {
                assertEquals(1, it.count)
            }
        }
        helper.close()
    }
}
