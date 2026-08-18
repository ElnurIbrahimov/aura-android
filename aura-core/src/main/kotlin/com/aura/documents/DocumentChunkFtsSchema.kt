package com.aura.documents

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * DDL for `document_chunks_fts` and the triggers that keep it in sync.
 *
 * Deliberately the same shape as `MemoryFtsSchema`, including the parts that
 * look redundant and are not — see below. Room's `createAllTables` builds the
 * virtual table on a fresh install but never the triggers, so both the
 * migration and the create-callback have to reach this.
 */
internal object DocumentChunkFtsSchema {

    /**
     * Must match Room's generated `createSql` for [DocumentChunkFtsEntity]
     * exactly, not merely be equivalent: `MigrationTestHelper.runMigrationsAndValidate`
     * compares the migrated schema against the exported JSON character for
     * character.
     */
    const val CREATE_TABLE =
        "CREATE VIRTUAL TABLE IF NOT EXISTS `document_chunks_fts` " +
            "USING FTS4(`chunkId` TEXT NOT NULL, `content` TEXT NOT NULL)"

    /**
     * Backfill for chunks that already exist.
     *
     * Empty in practice on the v27 → v28 upgrade, because nothing had ever
     * written a `document_chunks` row until the same change that added this
     * index. Kept anyway: the statement costs nothing over an empty table, and
     * a backfill that is absent because "there is nothing to backfill yet" is a
     * landmine for whoever restores a backup written by a later version.
     */
    /**
     * `OR REPLACE`, not a plain insert. `docid` is FTS4's alias for the rowid,
     * so re-running the backfill over rows the triggers have already indexed
     * inserts a second row at an occupied docid — which is an error, and the
     * kind that surfaces as "the app will not open" because Room runs
     * migrations before anything else and there is no destructive fallback on
     * upgrade.
     *
     * Room never re-runs a migration, so this is a guard against a hand-rolled
     * repair path rather than the normal one. It costs a word.
     */
    const val BACKFILL =
        "INSERT OR REPLACE INTO `document_chunks_fts`(docid, `chunkId`, `content`) " +
            "SELECT rowid, id, text FROM document_chunks"

    /**
     * Insert deletes by `chunkId` first, for the reason `memories_fts` does:
     * `@Insert(onConflict = REPLACE)` compiles to `INSERT OR REPLACE`, SQLite
     * performs REPLACE's implicit deletion **without firing DELETE triggers**
     * unless `PRAGMA recursive_triggers` is on, and the replacement row gets a
     * new rowid because the primary key is TEXT. A plain insert would therefore
     * strand the old index row and add a second — the same chunk indexed twice,
     * inflating `df` for exactly the passages that get re-imported most.
     *
     * The update trigger is scoped `AFTER UPDATE OF text`, and the scope is
     * load-bearing. `DocumentChunkDao.updateEmbedding` is a narrow UPDATE of the
     * four embedding columns and will run once per chunk over a whole document
     * when something finally computes embeddings; unscoped, each of those would
     * delete and reinsert an index row whose text had not changed. That is the
     * defect `MIGRATION_26_27` had to go back and fix on `memories`, arriving
     * here before it shipped rather than after.
     *
     * `@Update update(chunk)` still reindexes correctly, because Room generates
     * `SET` over every column and `text` is therefore always named.
     */
    val TRIGGERS = listOf(
        """
        CREATE TRIGGER IF NOT EXISTS document_chunks_fts_after_insert AFTER INSERT ON document_chunks BEGIN
            DELETE FROM `document_chunks_fts` WHERE `chunkId` = new.id;
            INSERT INTO `document_chunks_fts`(docid, `chunkId`, `content`) VALUES (new.rowid, new.id, new.text);
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS document_chunks_fts_after_delete AFTER DELETE ON document_chunks BEGIN
            DELETE FROM `document_chunks_fts` WHERE docid = old.rowid;
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS document_chunks_fts_after_update AFTER UPDATE OF text ON document_chunks BEGIN
            DELETE FROM `document_chunks_fts` WHERE docid = old.rowid;
            INSERT INTO `document_chunks_fts`(docid, `chunkId`, `content`) VALUES (new.rowid, new.id, new.text);
        END
        """.trimIndent(),
    )

    fun installTriggers(db: SupportSQLiteDatabase) {
        TRIGGERS.forEach { db.execSQL(it) }
    }

    /** Create the table, backfill it, and install the triggers. Used by the migration. */
    fun createAndBackfill(db: SupportSQLiteDatabase) {
        db.execSQL(CREATE_TABLE)
        db.execSQL(BACKFILL)
        installTriggers(db)
    }
}
