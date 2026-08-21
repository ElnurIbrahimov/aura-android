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
 * `MIGRATION_16_17` — the FTS index and the triggers that keep it current.
 *
 * A fast JVM-level check, modelled on [MemoryMigration11To12Test]. The full
 * chain validation lives in `androidTest` and needs a device, which means CI
 * never runs it; this at least puts the new hop under a test that does.
 *
 * What could go wrong here fails quietly rather than loudly: a missing backfill
 * or a missing trigger leaves the index empty, `searchFts` returns nothing, and
 * recall degrades to the vector fallback without an error anywhere. So each
 * piece is asserted separately.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MemoryMigration16To17Test {

    /** A v16-shaped `memories` table — only the columns this migration reads. */
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

    private fun SupportSQLiteDatabase.insertMemory(id: String, content: String, scope: String = "general") {
        execSQL(
            "INSERT INTO memories (id, content, source, category, scope) VALUES (?, ?, 'user', 'fact', ?)",
            arrayOf(id, content, scope),
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

    @Test
    fun `migration backfills existing rows into the index`() {
        val db = openV16()
        db.insertMemory("m1", "I love kotlin programming")
        db.insertMemory("m2", "I prefer python")

        MemoryMigrations.MIGRATION_16_17.migrate(db)

        // A migration that creates the table but forgets the backfill leaves
        // every pre-existing memory unsearchable, which is the whole store on
        // an upgrade.
        assertEquals(listOf("m1"), db.ftsMatchIds("\"kotlin\""))
        assertEquals(listOf("m2"), db.ftsMatchIds("\"python\""))
        db.close()
    }

    @Test
    fun `the insert trigger indexes rows added after the migration`() {
        val db = openV16()
        MemoryMigrations.MIGRATION_16_17.migrate(db)

        db.insertMemory("m3", "postgres full text search")

        assertEquals(listOf("m3"), db.ftsMatchIds("\"postgres\""))
        db.close()
    }

    @Test
    fun `the update trigger replaces stale content`() {
        val db = openV16()
        db.insertMemory("m1", "original wording")
        MemoryMigrations.MIGRATION_16_17.migrate(db)

        db.execSQL("UPDATE memories SET content = 'revised wording' WHERE id = 'm1'")

        assertTrue("stale text must stop matching", db.ftsMatchIds("\"original\"").isEmpty())
        assertEquals(listOf("m1"), db.ftsMatchIds("\"revised\""))
        db.close()
    }

    @Test
    fun `the delete trigger removes rows from the index`() {
        val db = openV16()
        db.insertMemory("m1", "temporary note")
        MemoryMigrations.MIGRATION_16_17.migrate(db)

        db.execSQL("DELETE FROM memories WHERE id = 'm1'")

        assertTrue(db.ftsMatchIds("\"temporary\"").isEmpty())
        db.close()
    }

    @Test
    fun `a REPLACE insert does not leave a duplicate index row`() {
        // Room's @Insert(onConflict = REPLACE) is a DELETE + INSERT, so it
        // fires both triggers. If the delete trigger were missing, the index
        // would accumulate a second row for the same memory and inflate the
        // document frequencies BM25 now depends on.
        val db = openV16()
        db.insertMemory("m1", "first version")
        MemoryMigrations.MIGRATION_16_17.migrate(db)

        db.execSQL(
            "INSERT OR REPLACE INTO memories (id, content, source, category, scope) " +
                "VALUES ('m1', 'second version', 'user', 'fact', 'general')",
        )

        var indexed = 0
        db.query("SELECT COUNT(*) FROM memories_fts").use { c -> if (c.moveToFirst()) indexed = c.getInt(0) }
        assertEquals(1, indexed)
        assertEquals(listOf("m1"), db.ftsMatchIds("\"second\""))
        assertTrue(db.ftsMatchIds("\"first\"").isEmpty())
        db.close()
    }
}
