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
 * `MIGRATION_27_28` — the full-text index over `document_chunks`.
 *
 * Unlike 26→27 this one does change the schema, so the exported `28.json` and
 * Room's own validation cover the table's existence. What they do not cover is
 * the half that is hand-written SQL and therefore invisible to the schema
 * export: the backfill and the three triggers. `MemoryFtsSchema`'s KDoc records
 * why that gap matters — Room's `createAllTables` builds a virtual table and
 * never the triggers that fill it, so an index can exist, validate, and index
 * nothing, indistinguishably from "no document matched".
 *
 * Applied to a minimal v27-shaped pair of tables rather than the real thing.
 * The migration reads two columns of `document_chunks` and nothing else, and a
 * faithful 27-column reproduction would assert the fidelity of the fixture
 * rather than the behaviour of the migration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class MemoryMigration27To28Test {

    private fun openV27(): SupportSQLiteDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(27) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE documents (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL)"
                        )
                        db.execSQL(
                            """
                            CREATE TABLE document_chunks (
                                id TEXT NOT NULL PRIMARY KEY,
                                documentId TEXT NOT NULL,
                                ordinal INTEGER NOT NULL,
                                charStart INTEGER NOT NULL,
                                charEnd INTEGER NOT NULL,
                                pageNumber INTEGER NOT NULL DEFAULT 0,
                                text TEXT NOT NULL,
                                contentHash TEXT NOT NULL,
                                embedding BLOB,
                                embeddingModel TEXT,
                                embeddingVersion INTEGER NOT NULL DEFAULT 0,
                                embeddedAt INTEGER NOT NULL DEFAULT 0
                            )
                            """.trimIndent()
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                })
                .build()
        )
        return helper.writableDatabase
    }

    private fun SupportSQLiteDatabase.insertChunk(id: String, documentId: String, ordinal: Int, text: String) {
        execSQL(
            "INSERT INTO document_chunks (id, documentId, ordinal, charStart, charEnd, text, contentHash) " +
                "VALUES (?, ?, ?, 0, ${text.length}, ?, 'hash-$id')",
            arrayOf<Any>(id, documentId, ordinal, text),
        )
    }

    private fun SupportSQLiteDatabase.indexedChunkIds(match: String): List<String> =
        query("SELECT chunkId FROM document_chunks_fts WHERE content MATCH ?", arrayOf(match)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    @Test
    fun `chunks that already existed are backfilled into the new index`() {
        val db = openV27()
        db.execSQL("INSERT INTO documents (id, name) VALUES ('doc-1', 'manual.txt')")
        db.insertChunk("doc-1:0", "doc-1", 0, "the turbine housing is cast in one piece")

        MemoryMigrations.MIGRATION_27_28.migrate(db)

        assertEquals(listOf("doc-1:0"), db.indexedChunkIds("\"turbine\""))
        db.close()
    }

    @Test
    fun `the migration installs triggers, not just a table`() {
        // The table alone validates against 28.json and indexes nothing. This
        // is the difference between a migration that passes Room's check and a
        // migration that works.
        val db = openV27()
        db.execSQL("INSERT INTO documents (id, name) VALUES ('doc-2', 'later.txt')")

        MemoryMigrations.MIGRATION_27_28.migrate(db)
        db.insertChunk("doc-2:0", "doc-2", 0, "written after the migration ran")

        assertEquals(listOf("doc-2:0"), db.indexedChunkIds("\"written\""))
        db.close()
    }

    @Test
    fun `a deleted chunk leaves no index row behind`() {
        val db = openV27()
        db.execSQL("INSERT INTO documents (id, name) VALUES ('doc-3', 'transient.txt')")
        MemoryMigrations.MIGRATION_27_28.migrate(db)
        db.insertChunk("doc-3:0", "doc-3", 0, "ephemeral paragraph")
        assertTrue(db.indexedChunkIds("\"ephemeral\"").isNotEmpty())

        db.execSQL("DELETE FROM document_chunks WHERE id = 'doc-3:0'")

        assertTrue(db.indexedChunkIds("\"ephemeral\"").isEmpty())
        db.close()
    }

    @Test
    fun `replacing a chunk indexes it once, not twice`() {
        // `INSERT OR REPLACE` — what Room generates for the chunk DAO — performs
        // its implicit delete without firing DELETE triggers, and the new row
        // gets a new rowid because the key is TEXT. The insert trigger deletes
        // by chunkId first for exactly this, and a duplicate here would inflate
        // the document frequency of every term in the re-imported passage.
        val db = openV27()
        db.execSQL("INSERT INTO documents (id, name) VALUES ('doc-4', 'revised.txt')")
        MemoryMigrations.MIGRATION_27_28.migrate(db)
        db.insertChunk("doc-4:0", "doc-4", 0, "the coupling is bronze")

        db.execSQL(
            "INSERT OR REPLACE INTO document_chunks (id, documentId, ordinal, charStart, charEnd, text, contentHash) " +
                "VALUES ('doc-4:0', 'doc-4', 0, 0, 24, 'the coupling is titanium', 'hash-revised')"
        )

        assertEquals(listOf("doc-4:0"), db.indexedChunkIds("\"coupling\""))
        assertTrue(db.indexedChunkIds("\"bronze\"").isEmpty())
        assertEquals(listOf("doc-4:0"), db.indexedChunkIds("\"titanium\""))
        db.close()
    }

    @Test
    fun `running the migration twice is not an error`() {
        // Every statement is `IF NOT EXISTS`, and a migration that throws on a
        // partially-applied database is a migration that bricks the app rather
        // than recovering — there is no destructive fallback on upgrade.
        val db = openV27()
        db.execSQL("INSERT INTO documents (id, name) VALUES ('doc-5', 'twice.txt')")

        MemoryMigrations.MIGRATION_27_28.migrate(db)
        db.insertChunk("doc-5:0", "doc-5", 0, "idempotent enough")
        MemoryMigrations.MIGRATION_27_28.migrate(db)

        // Backfilled a second time over a row the trigger had already indexed;
        // the insert trigger's delete-by-chunkId is what keeps that from
        // doubling.
        assertEquals(listOf("doc-5:0"), db.indexedChunkIds("\"idempotent\""))
        db.close()
    }
}
