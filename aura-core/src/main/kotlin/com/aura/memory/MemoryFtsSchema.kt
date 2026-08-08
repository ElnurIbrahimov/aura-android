package com.aura.memory

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * DDL for the `memories_fts` index and the triggers that keep it in sync.
 *
 * Lives here rather than inline in `MIGRATION_16_17` because it is needed in
 * two places that are easy to let drift apart:
 *
 * - the **migration**, for databases upgrading from v16, and
 * - the **fresh-install callback**, because Room's generated `createAllTables`
 *   creates entities, indices and views but *not* triggers. A new install would
 *   otherwise get the virtual table with nothing ever writing to it, and
 *   lexical recall would return nothing — silently, since an empty index is
 *   indistinguishable from "no memory matched".
 *
 * Triggers rather than Kotlin-side synchronisation, deliberately: `memories` is
 * written from `insert`, `insertAll`, `insertAllWithEdits`, `update`,
 * `updateAll`, `updateWithAudit`, `restoreWithAudit`, four delete queries, the
 * decay pass, the dedup merge in [MemoryStore.maybeStore] and the backup
 * restore. Any of those forgetting to touch the index would produce a partially
 * indexed store, and `MemoryDaoContractTest`'s 26 tests all write through
 * `dao.insert` — Kotlin-side sync would have left every one of them exercising
 * an empty index while still passing.
 */
internal object MemoryFtsSchema {

    /**
     * Copied verbatim from Room's generated `17.json` `createSql`, with
     * `${'$'}{TABLE_NAME}` substituted. `MigrationTestHelper.runMigrationsAndValidate`
     * compares the resulting schema against that file exactly, so this must not
     * be "equivalent" DDL — it must be the same DDL.
     */
    const val CREATE_TABLE =
        "CREATE VIRTUAL TABLE IF NOT EXISTS `memories_fts` " +
            "USING FTS4(`memoryId` TEXT NOT NULL, `content` TEXT NOT NULL)"

    /**
     * Backfill for existing rows. `docid` is FTS4's alias for the implicit
     * rowid, and pinning it to `memories.rowid` is what makes the join in
     * [MemoryDao.searchFts] work.
     */
    const val BACKFILL =
        "INSERT INTO `memories_fts`(docid, `memoryId`, `content`) SELECT rowid, id, content FROM memories"

    /**
     * The insert trigger deletes any existing index row for the same
     * `memoryId` before inserting, which is not redundant.
     *
     * `@Insert(onConflict = REPLACE)` — used by `insert`, `insertAll` and the
     * backup restore — compiles to `INSERT OR REPLACE`, and SQLite runs
     * REPLACE's implicit row deletion **without firing DELETE triggers** unless
     * `PRAGMA recursive_triggers` is on (it is off by default). `memories.id`
     * is a TEXT primary key rather than the rowid, so the replacement row also
     * gets a *new* rowid. Both together mean a plain
     * `INSERT INTO memories_fts(docid, …)` leaves the superseded index row
     * orphaned at the old rowid and adds a second one — the same memory
     * indexed twice.
     *
     * That is not merely wasteful now that BM25 takes its document frequencies
     * from this index: duplicates inflate `df`, which deflates IDF for exactly
     * the terms in the memories that get rewritten most often. It also lets one
     * memory occupy two candidate slots in recall. Deleting by `memoryId`
     * first makes the trigger idempotent under both plain inserts and replaces.
     *
     * The update trigger keys on `old.rowid` instead, because an in-place
     * UPDATE keeps the rowid.
     */
    val TRIGGERS = listOf(
        """
        CREATE TRIGGER IF NOT EXISTS memories_fts_after_insert AFTER INSERT ON memories BEGIN
            DELETE FROM `memories_fts` WHERE `memoryId` = new.id;
            INSERT INTO `memories_fts`(docid, `memoryId`, `content`) VALUES (new.rowid, new.id, new.content);
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS memories_fts_after_delete AFTER DELETE ON memories BEGIN
            DELETE FROM `memories_fts` WHERE docid = old.rowid;
        END
        """.trimIndent(),
        """
        CREATE TRIGGER IF NOT EXISTS memories_fts_after_update AFTER UPDATE ON memories BEGIN
            DELETE FROM `memories_fts` WHERE docid = old.rowid;
            INSERT INTO `memories_fts`(docid, `memoryId`, `content`) VALUES (new.rowid, new.id, new.content);
        END
        """.trimIndent(),
    )

    /** Create the triggers only. Used by [triggerCallback]. */
    fun installTriggers(db: SupportSQLiteDatabase) {
        TRIGGERS.forEach { db.execSQL(it) }
    }

    /**
     * The one callback that installs the triggers on a freshly created
     * database. Production wires it in `MemoryModule.provideDatabase`; tests
     * building an in-memory `MemoryDatabase` must add it too.
     *
     * Shared rather than duplicated on purpose: a test database with the FTS
     * table but no triggers indexes nothing, and every lexical-recall assertion
     * against it would quietly become "returns empty" — passing or failing for
     * reasons unrelated to what it meant to check.
     */
    val triggerCallback: androidx.room.RoomDatabase.Callback =
        object : androidx.room.RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) = installTriggers(db)
        }

    /** Create the table, backfill it, and install the triggers. Used by the migration. */
    fun createAndBackfill(db: SupportSQLiteDatabase) {
        db.execSQL(CREATE_TABLE)
        db.execSQL(BACKFILL)
        installTriggers(db)
    }
}
