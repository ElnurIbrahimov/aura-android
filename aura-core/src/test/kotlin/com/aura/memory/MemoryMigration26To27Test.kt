package com.aura.memory

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
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
 * `MIGRATION_26_27` — scoping the FTS update trigger to the column it indexes.
 *
 * The schema does not change, so `MigrationReplayTest` passes this hop
 * trivially and proves nothing about it: `27.json` is `26.json` with a new
 * version number and the same identity hash, because Room's schema export does
 * not record hand-written triggers. The behaviour is the entire point of the
 * migration, so the behaviour is what this asserts.
 *
 * **The detector is a sentinel planted in the index.** "Did the trigger fire?"
 * has no direct handle — a delete-and-reinsert of unchanged text leaves the
 * index looking exactly as it did. So each test writes a distinctive term into
 * `memories_fts` that does not appear in `memories.content`: if the trigger
 * fires it rebuilds the row from the table and the sentinel is gone; if it does
 * not fire the sentinel survives. Absence and presence are then both meaningful.
 *
 * The hop is applied on a v16-shaped table because 16→17 is what creates the
 * index and its triggers, and every migration in between adds columns this one
 * never reads. That is a deliberate narrowing to the thing under test, not a
 * claim about the full chain, which `MigrationReplayTest` owns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MemoryMigration26To27Test {

    private fun openV16(): SupportSQLiteDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(16) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE memories (
                                id TEXT NOT NULL PRIMARY KEY,
                                content TEXT NOT NULL,
                                source TEXT NOT NULL,
                                category TEXT NOT NULL,
                                scope TEXT NOT NULL DEFAULT 'general',
                                importance REAL NOT NULL DEFAULT 0.5,
                                embedding BLOB,
                                createdAt INTEGER NOT NULL DEFAULT 0,
                                accessedAt INTEGER NOT NULL DEFAULT 0,
                                accessCount INTEGER NOT NULL DEFAULT 0,
                                decayScore REAL NOT NULL DEFAULT 1.0,
                                tags TEXT NOT NULL DEFAULT '',
                                metadata TEXT NOT NULL DEFAULT '',
                                sourceConversationId TEXT NOT NULL DEFAULT '',
                                sourceTurnTimestamp INTEGER NOT NULL DEFAULT 0,
                                embeddingModel TEXT,
                                embeddingVersion INTEGER NOT NULL DEFAULT 0
                            )
                            """.trimIndent(),
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        return helper.writableDatabase
    }

    private fun SupportSQLiteDatabase.insertMemory(id: String, content: String) {
        execSQL(
            "INSERT INTO memories (id, content, source, category) VALUES (?, ?, 'user', 'fact')",
            arrayOf(id, content),
        )
    }

    private fun SupportSQLiteDatabase.ftsMatchIds(match: String): List<String> {
        val ids = mutableListOf<String>()
        query(
            "SELECT m.id FROM memories m JOIN memories_fts f ON f.rowid = m.rowid WHERE f.content MATCH ?",
            arrayOf(match),
        ).use { c -> while (c.moveToNext()) ids.add(c.getString(0)) }
        return ids
    }

    /** Replace the index row's text with a term that appears nowhere in `memories`. */
    private fun SupportSQLiteDatabase.plantSentinel(id: String) {
        execSQL("DELETE FROM `memories_fts` WHERE docid = (SELECT rowid FROM memories WHERE id = ?)", arrayOf(id))
        execSQL(
            "INSERT INTO `memories_fts`(docid, `memoryId`, `content`) " +
                "SELECT rowid, id, 'sentineltoken' FROM memories WHERE id = ?",
            arrayOf(id),
        )
    }

    /** A `touch`: exactly the columns `MemoryDao.touch` writes, and no others. */
    private fun SupportSQLiteDatabase.touch(id: String) {
        execSQL(
            "UPDATE memories SET accessedAt = 1, accessCount = accessCount + 1, " +
                "decayScore = MIN(1.0, decayScore + 0.1) WHERE id = ?",
            arrayOf(id),
        )
    }

    /**
     * The trigger exactly as it shipped, before this migration.
     *
     * Written out rather than obtained from `MIGRATION_16_17`, because that path
     * now installs the *fixed* definition — `MemoryFtsSchema.TRIGGERS` is the
     * single source and 16→17 reads it too. Only a database that ran 16→17
     * before this change carries the old trigger, and that is precisely the
     * database this migration exists for, so the test has to reconstruct it.
     */
    private fun SupportSQLiteDatabase.installLegacyUpdateTrigger() {
        execSQL("DROP TRIGGER IF EXISTS memories_fts_after_update")
        execSQL(
            """
            CREATE TRIGGER memories_fts_after_update AFTER UPDATE ON memories BEGIN
                DELETE FROM `memories_fts` WHERE docid = old.rowid;
                INSERT INTO `memories_fts`(docid, `memoryId`, `content`) VALUES (new.rowid, new.id, new.content);
            END
            """.trimIndent(),
        )
    }

    @Test
    fun `the migration replaces a legacy trigger that reindexed on every touch`() {
        // Both halves in one test, on one database, because the claim is about
        // a *change* on an installed device: the same touch that rebuilt the
        // index row before must leave it alone after.
        val db = openV16()
        db.insertMemory("m1", "kotlin coroutines")
        MemoryMigrations.MIGRATION_16_17.migrate(db)
        db.installLegacyUpdateTrigger()

        db.plantSentinel("m1")
        db.touch("m1")
        assertTrue(
            "the legacy trigger rebuilt the index row on a touch — this is the defect",
            db.ftsMatchIds("\"sentineltoken\"").isEmpty(),
        )

        MemoryMigrations.MIGRATION_26_27.migrate(db)

        db.plantSentinel("m1")
        db.touch("m1")
        assertEquals(
            "after the migration the same touch must not reindex",
            listOf("m1"),
            db.ftsMatchIds("\"sentineltoken\""),
        )
        db.close()
    }

    @Test
    fun `the migration is required — editing the trigger SQL alone cannot reach an installed device`() {
        // CREATE TRIGGER IF NOT EXISTS is a no-op when the name is taken, so
        // installTriggers on a database that already has the legacy trigger
        // leaves it in place. Without the DROP in reinstallTriggers, upgraded
        // devices and fresh installs would run different index behaviour
        // indefinitely, and both would look correct in isolation.
        val db = openV16()
        db.insertMemory("m1", "kotlin coroutines")
        MemoryMigrations.MIGRATION_16_17.migrate(db)
        db.installLegacyUpdateTrigger()

        MemoryFtsSchema.installTriggers(db)

        db.plantSentinel("m1")
        db.touch("m1")
        assertTrue(
            "installTriggers alone must not be assumed to replace anything",
            db.ftsMatchIds("\"sentineltoken\"").isEmpty(),
        )
        db.close()
    }

    @Test
    fun `after the migration, a touch leaves the index alone`() {
        val db = openV16()
        db.insertMemory("m1", "kotlin coroutines")
        MemoryMigrations.MIGRATION_16_17.migrate(db)
        MemoryMigrations.MIGRATION_26_27.migrate(db)
        db.plantSentinel("m1")

        db.touch("m1")

        assertEquals(
            "a touch changes no indexed column and must not reindex",
            listOf("m1"),
            db.ftsMatchIds("\"sentineltoken\""),
        )
        db.close()
    }

    @Test
    fun `after the migration, a content change still reindexes`() {
        // The half that must not regress. Scoping the trigger too tightly would
        // leave stale text matching forever, which is worse than reindexing too
        // often and would look like nothing at all until a search went wrong.
        val db = openV16()
        db.insertMemory("m1", "original wording")
        MemoryMigrations.MIGRATION_16_17.migrate(db)
        MemoryMigrations.MIGRATION_26_27.migrate(db)
        db.plantSentinel("m1")

        db.execSQL("UPDATE memories SET content = 'revised wording' WHERE id = 'm1'")

        assertTrue("the sentinel must be replaced", db.ftsMatchIds("\"sentineltoken\"").isEmpty())
        assertEquals(listOf("m1"), db.ftsMatchIds("\"revised\""))
        assertTrue("stale text must stop matching", db.ftsMatchIds("\"original\"").isEmpty())
        db.close()
    }

    @Test
    fun `the migration leaves the insert and delete triggers working`() {
        // reinstallTriggers drops all three and recreates them, so both of the
        // triggers this migration does not care about pass through it.
        val db = openV16()
        MemoryMigrations.MIGRATION_16_17.migrate(db)
        MemoryMigrations.MIGRATION_26_27.migrate(db)

        db.insertMemory("m2", "postgres full text search")
        assertEquals(listOf("m2"), db.ftsMatchIds("\"postgres\""))

        db.execSQL("DELETE FROM memories WHERE id = 'm2'")
        assertTrue(db.ftsMatchIds("\"postgres\"").isEmpty())
        db.close()
    }

    @Test
    fun `a whole-row Room update still reindexes`() {
        // Room's @Update names every column in the SET list, including content,
        // so scoping the trigger does not spare `updateAll` — which is why
        // `runDecayPass` needs its own narrow query rather than this migration.
        // Asserted so the limit is recorded rather than assumed.
        val db = openV16()
        db.insertMemory("m1", "kotlin coroutines")
        MemoryMigrations.MIGRATION_16_17.migrate(db)
        MemoryMigrations.MIGRATION_26_27.migrate(db)
        db.plantSentinel("m1")

        db.execSQL(
            "UPDATE memories SET content = 'kotlin coroutines', decayScore = 0.5 WHERE id = 'm1'",
        )

        assertTrue(
            "content named in the SET list fires the trigger even when its value is unchanged",
            db.ftsMatchIds("\"sentineltoken\"").isEmpty(),
        )
        db.close()
    }
}
