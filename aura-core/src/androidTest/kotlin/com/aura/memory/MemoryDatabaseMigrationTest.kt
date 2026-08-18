package com.aura.memory

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MemoryDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MemoryDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate1To2() {
        val db = helper.createDatabase("test-aura-memory.db", 1)
        db.close()

        helper.runMigrationsAndValidate(
            "test-aura-memory.db",
            2,
            true,
            MemoryModule.MIGRATION_1_2,
        )
    }

    @Test
    fun migrate1To2_preservesKgSchema() {
        val db = helper.createDatabase("test-aura-memory.db", 1)
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            "test-aura-memory.db",
            2,
            true,
            MemoryModule.MIGRATION_1_2,
        )

        // Verify kg_nodes columns match the v2 schema.
        val nodeCursor = migrated.query("PRAGMA table_info(kg_nodes)")
        val nodeColumns = mutableSetOf<String>()
        nodeCursor.use {
            while (it.moveToNext()) {
                nodeColumns.add(it.getString(it.getColumnIndexOrThrow("name")))
            }
        }
        assert("createdAt" in nodeColumns) { "kg_nodes should have createdAt after 1→2" }

        // Verify kg_edges columns match the v2 schema.
        val edgeCursor = migrated.query("PRAGMA table_info(kg_edges)")
        val edgeColumns = mutableSetOf<String>()
        edgeCursor.use {
            while (it.moveToNext()) {
                edgeColumns.add(it.getString(it.getColumnIndexOrThrow("name")))
            }
        }
        assert("properties" in edgeColumns) { "kg_edges should have properties after 1→2" }
        assert("confidence" in edgeColumns) { "kg_edges should have confidence after 1→2" }
        assert("createdAt" in edgeColumns) { "kg_edges should have createdAt after 1→2" }

        // Verify all expected indices were created.
        val indexCursor = migrated.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name LIKE 'index_kg_%'",
        )
        val indexNames = mutableSetOf<String>()
        indexCursor.use {
            while (it.moveToNext()) {
                indexNames.add(it.getString(0))
            }
        }
        assert("index_kg_nodes_label" in indexNames)
        assert("index_kg_nodes_type" in indexNames)
        assert("index_kg_nodes_label_type" in indexNames)
        assert("index_kg_edges_sourceId" in indexNames)
        assert("index_kg_edges_targetId" in indexNames)
        assert("index_kg_edges_sourceId_targetId_type" in indexNames)

        migrated.close()
    }

    @Test
    fun migrate2To3_preservesMemories_andCreatesEditHistory() {
        // Start at v2 (post-KG, pre-edit-history).
        val db = helper.createDatabase("test-aura-memory-v2.db", 2)
        // Insert a memory row using the v2 schema (memories table exists,
        // kg_nodes and kg_edges exist, but memory_edits does not).
        db.execSQL(
            """
            INSERT INTO memories (id, content, source, category, importance, embedding, createdAt, accessedAt, accessCount, decayScore, tags, metadata)
            VALUES ('mem-1', 'User likes coffee', 'user', 'preference', 0.8, NULL, 1700000000, 1700000000, 0, 1.0, '', '{}')
            """.trimIndent(),
        )
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            "test-aura-memory-v2.db",
            3,
            true,
            MemoryModule.MIGRATION_2_3,
        )

        // Verify the memory survived.
        val cursor = migrated.query("SELECT * FROM memories WHERE id = 'mem-1'")
        cursor.use {
            assert(it.moveToFirst()) { "Memory row should survive 2→3 migration" }
            assert(it.getString(it.getColumnIndexOrThrow("content")) == "User likes coffee")
        }

        // Verify the memory_edits table exists with the FK + index.
        val editTableCursor = migrated.query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='memory_edits'",
        )
        editTableCursor.use {
            assert(it.moveToFirst()) { "memory_edits table should exist after 2→3 migration" }
        }

        val editIndexCursor = migrated.query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name='index_memory_edits_memoryId'",
        )
        editIndexCursor.use {
            assert(it.moveToFirst()) { "memory_edits index should exist after 2→3 migration" }
        }

        migrated.close()
    }

    @Test
    fun migrate3To4_preservesRows_andAddsConversationProvenance() {
        val db = helper.createDatabase("test-aura-memory-v3.db", 3)
        db.execSQL(
            """
            INSERT INTO memories (id, content, source, category, importance, embedding, createdAt, accessedAt, accessCount, decayScore, tags, metadata)
            VALUES ('mem-1', 'User likes coffee', 'user', 'preference', 0.8, NULL, 1700000000, 1700000000, 0, 1.0, '', '{}')
            """.trimIndent(),
        )
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            "test-aura-memory-v3.db",
            4,
            true,
            MemoryModule.MIGRATION_3_4,
        )

        migrated.query(
            "SELECT content, sourceConversationId, sourceTurnTimestamp FROM memories WHERE id = 'mem-1'",
        ).use {
            assert(it.moveToFirst())
            assert(it.getString(0) == "User likes coffee")
            assert(it.getString(1).isEmpty())
            assert(it.getLong(2) == 0L)
        }
        migrated.close()
    }

    @Test
    fun migrate5To6_createsCreativeProjectStore() {
        val db = helper.createDatabase("test-aura-memory-v5.db", 5)
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            "test-aura-memory-v5.db",
            6,
            true,
            MemoryModule.MIGRATION_5_6,
        )
        migrated.query("PRAGMA table_info(creative_projects)").use { cursor ->
            val names = mutableSetOf<String>()
            val nameColumn = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) names += cursor.getString(nameColumn)
            assertTrue(names.containsAll(setOf("id", "name", "worldJson", "templateId", "turnCount", "updatedAt")))
        }
        migrated.close()
    }

    @Test
    fun migrate4To5_createsDocumentLibrary() {
        val db = helper.createDatabase("test-aura-memory-v4.db", 4)
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            "test-aura-memory-v4.db",
            5,
            true,
            MemoryModule.MIGRATION_4_5,
        )

        migrated.query("PRAGMA table_info(documents)").use { cursor ->
            val columns = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                columns += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
            assert(columns == setOf(
                "id", "name", "mimeType", "sourceUri", "importedAt", "characterCount", "chunkCount",
            )) { "Unexpected document columns: $columns" }
        }
        migrated.close()
    }

    @Test
    fun migrate1To3_chained_preservesData() {
        val db = helper.createDatabase("test-aura-memory-chained.db", 1)
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            "test-aura-memory-chained.db",
            3,
            true,
            MemoryModule.MIGRATION_1_2,
            MemoryModule.MIGRATION_2_3,
        )

        // After chained migration, all three tables (memories, kg_nodes,
        // kg_edges, memory_edits) should exist.
        val tablesCursor = migrated.query(
            "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name",
        )
        val tableNames = mutableListOf<String>()
        tablesCursor.use {
            while (it.moveToNext()) {
                tableNames.add(it.getString(0))
            }
        }
        assert("memories" in tableNames) { "memories table missing after chained migration" }
        assert("kg_nodes" in tableNames) { "kg_nodes table missing after chained migration" }
        assert("kg_edges" in tableNames) { "kg_edges table missing after chained migration" }
        assert("memory_edits" in tableNames) { "memory_edits table missing after chained migration" }

        migrated.close()
    }

    /**
     * Full chain from the oldest schema we can still instantiate (v6) to
     * head (v14).
     *
     * MemoryDatabase's Room schema exports skip 7.json through 10.json —
     * those versions shipped without their schema files being committed, and
     * they were never in git, so they cannot be recovered. The direct
     * consequence is that no test can call `createDatabase(name, 7..10)`:
     * MigrationTestHelper needs the JSON to build the starting schema. That
     * left MIGRATION_6_7 through MIGRATION_13_14 — eight migrations, four of
     * them 70+ lines of DDL — with no coverage at all.
     *
     * This closes the gap from the other end. Starting at v6 (whose schema
     * does exist) and validating against v14 (which also exists) forces
     * every migration in between to execute, and Room verifies the final
     * schema matches 14.json exactly. A migration that creates a column with
     * the wrong type, forgets an index, or throws mid-way fails here.
     *
     * What this does NOT give us is a per-step assertion about data
     * preserved across an individual hop in 6..10. If a specific migration
     * needs that, the fix is to commit the missing schema files by checking
     * out the revision where the database was at that version and building —
     * not to weaken this test.
     */
    @Test
    fun migrate6To18_fullChain_validatesAgainstHeadSchema() {
        val name = "test-aura-memory-6-to-18.db"
        val db = helper.createDatabase(name, 6)
        // Seed a row so the chain runs against a non-empty table. Column set
        // is the v6 shape; later migrations must carry it forward.
        // tags, metadata, sourceConversationId and sourceTurnTimestamp are
        // NOT NULL with no default in 6.json, so they must be supplied.
        db.execSQL(
            "INSERT INTO memories (id, content, source, category, importance, embedding, createdAt, accessedAt, " +
                "accessCount, decayScore, tags, metadata, sourceConversationId, sourceTurnTimestamp) " +
                "VALUES ('m1', 'chain test memory', 'user', 'fact', 0.8, NULL, 1000, 1000, 1, 1.0, '[]', '{}', '', 0)",
        )
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            name,
            18,
            true,
            MemoryModule.MIGRATION_6_7,
            MemoryModule.MIGRATION_7_8,
            MemoryModule.MIGRATION_8_9,
            MemoryModule.MIGRATION_9_10,
            MemoryModule.MIGRATION_10_11,
            MemoryModule.MIGRATION_11_12,
            MemoryModule.MIGRATION_12_13,
            MemoryModule.MIGRATION_13_14,
            MemoryModule.MIGRATION_14_15,
            MemoryModule.MIGRATION_15_16,
            MemoryModule.MIGRATION_16_17,
            MemoryModule.MIGRATION_17_18,
        )

        // The v16->v17 hop builds the FTS index and backfills it. A migration
        // that creates the table but skips the backfill leaves every
        // pre-existing memory unsearchable — the entire store, on upgrade —
        // and does it silently, since an empty index looks like "no match".
        val fts = migrated.query(
            "SELECT m.id FROM memories m JOIN memories_fts f ON f.rowid = m.rowid WHERE f.content MATCH 'chain'",
        )
        fts.use {
            assertTrue("seeded memory was not backfilled into memories_fts", it.moveToFirst())
            assertEquals("m1", it.getString(0))
        }

        val cursor = migrated.query("SELECT id, content FROM memories WHERE id = 'm1'")
        cursor.use {
            assertTrue("seeded memory did not survive the v6 to v18 chain", it.moveToFirst())
            assertTrue(it.getString(1) == "chain test memory")
        }
    }

    /**
     * The scope column added in MIGRATION_11_12 is the one that carries
     * per-agent isolation. A v6 database predates it entirely, so the chain
     * has to introduce it with the documented default rather than leaving
     * rows with NULL — a NULL scope reads as "no agent" everywhere that
     * filters on it, which would silently expose old memories to every agent.
     */
    @Test
    fun migrate6To18_backfillsScopeOnPreexistingRows() {
        val name = "test-aura-memory-scope-backfill.db"
        val db = helper.createDatabase(name, 6)
        db.execSQL(
            "INSERT INTO memories (id, content, source, category, importance, embedding, createdAt, accessedAt, " +
                "accessCount, decayScore, tags, metadata, sourceConversationId, sourceTurnTimestamp) " +
                "VALUES ('m2', 'pre-scope memory', 'user', 'fact', 0.5, NULL, 1000, 1000, 1, 1.0, '[]', '{}', '', 0)",
        )
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            name,
            18,
            true,
            MemoryModule.MIGRATION_6_7,
            MemoryModule.MIGRATION_7_8,
            MemoryModule.MIGRATION_8_9,
            MemoryModule.MIGRATION_9_10,
            MemoryModule.MIGRATION_10_11,
            MemoryModule.MIGRATION_11_12,
            MemoryModule.MIGRATION_12_13,
            MemoryModule.MIGRATION_13_14,
            MemoryModule.MIGRATION_14_15,
            MemoryModule.MIGRATION_15_16,
            MemoryModule.MIGRATION_16_17,
            MemoryModule.MIGRATION_17_18,
        )

        val cursor = migrated.query("SELECT scope FROM memories WHERE id = 'm2'")
        cursor.use {
            assertTrue("pre-scope row missing after migration", it.moveToFirst())
            assertTrue(
                "scope must be backfilled, not NULL",
                !it.isNull(0) && it.getString(0) == "general",
            )
        }
    }

    /**
     * The v17 to v18 hop adds the living-world tables.
     *
     * Two things are worth asserting beyond "it validates". First that the
     * foreign key onto `creative_projects` actually cascades, because a world
     * outliving the project it belongs to would be unreachable rows that
     * nothing ever deletes. Second that the unique index on
     * (projectId, branchId) holds, since it is the only thing preventing two
     * worlds ticking the same branch and each overwriting the other's state.
     */
    @Test
    fun migrate17To18_addsLivingWorldTablesWithCascadeAndUniqueness() {
        val name = "test-aura-memory-17-to-18.db"
        val db = helper.createDatabase(name, 17)
        // All twelve columns, because at v17 `creative_projects` declares every
        // one of them NOT NULL with no default. This INSERT listed ten and could
        // therefore never have run: `metadataJson` and `lastSessionEnded` were
        // missing and SQLite refused the row outright. It went unnoticed because
        // instrumented tests need a device and this one had never been on one —
        // written, committed, counted in the "64 instrumented test methods"
        // figure, and structurally incapable of passing. Found 2026-08-14, the
        // first time these were executed at all.
        db.execSQL(
            "INSERT INTO creative_projects (id, name, description, genre, tone, templateId, worldJson, " +
                "metadataJson, lastSessionEnded, createdAt, updatedAt, turnCount) " +
                "VALUES ('p1', 'Ashfall', '', '', '', 'novel', '{}', '{}', 0, 1000, 1000, 0)",
        )
        db.close()

        val migrated = helper.runMigrationsAndValidate(name, 18, true, MemoryModule.MIGRATION_17_18)

        migrated.execSQL("PRAGMA foreign_keys = ON")
        migrated.execSQL(
            "INSERT INTO living_worlds (id, projectId, branchId, rootSeed, branchSalt, parentWorldId, " +
                "forkedAtTick, worldEpochMs, currentTick, stateJson, status, createdAt, updatedAt) " +
                "VALUES ('w1', 'p1', 'main', 42, 0, '', 0, 1000, 0, '{}', 'running', 1000, 1000)",
        )
        migrated.execSQL(
            "INSERT INTO living_events (id, worldId, branchId, tickIndex, seq, kind, actorId, targetId, ruleId, " +
                "magnitudeMilli, summary, notability, narration, narratedAt, createdAt) " +
                "VALUES ('w1#1.0', 'w1', 'main', 1, 0, 'stock_shift', 'a', '', 'r', 10, 'something happened', " +
                "0.0, '', 0, 1000)",
        )

        var threw = false
        try {
            migrated.execSQL(
                "INSERT INTO living_worlds (id, projectId, branchId, rootSeed, branchSalt, parentWorldId, " +
                    "forkedAtTick, worldEpochMs, currentTick, stateJson, status, createdAt, updatedAt) " +
                    "VALUES ('w2', 'p1', 'main', 7, 0, '', 0, 1000, 0, '{}', 'running', 1000, 1000)",
            )
        } catch (_: Exception) {
            threw = true
        }
        assertTrue("a second world on the same project and branch was allowed", threw)

        migrated.execSQL("DELETE FROM creative_projects WHERE id = 'p1'")
        migrated.query("SELECT COUNT(*) FROM living_worlds").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        migrated.query("SELECT COUNT(*) FROM living_events").use {
            assertTrue(it.moveToFirst())
            assertEquals("events outlived the world they belong to", 0, it.getInt(0))
        }
    }

    /** Every NOT NULL column of `memories` at v18, in order. */
    private fun insertV18Memory(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        id: String,
        content: String,
    ) = db.execSQL(
        "INSERT INTO memories (id, content, source, category, scope, importance, createdAt, accessedAt, " +
            "accessCount, decayScore, tags, metadata, sourceConversationId, sourceTurnTimestamp, embeddingVersion) " +
            "VALUES ('$id', '$content', 'user', 'fact', 'general', 0.6, 1000, 1000, 1, 1.0, '[]', '{}', '', 0, 0)",
    )

    /**
     * The hops from 18 to head, which until now had no instrumented coverage at
     * all — `migrate6To18_fullChain` stopped exactly where this starts, so five
     * migrations were validated only by `MigrationReplayTest` on the JVM, which
     * diffs schemas and never opens SQLite.
     */
    @Test
    fun migrate18To23_fullChain_validatesAgainstHeadSchema() {
        val name = "test-aura-memory-18-to-23.db"
        val db = helper.createDatabase(name, 18)
        insertV18Memory(db, "m18", "chain test memory")
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            name,
            23,
            true,
            MemoryModule.MIGRATION_18_19,
            MemoryModule.MIGRATION_19_20,
            MemoryModule.MIGRATION_20_21,
            MemoryModule.MIGRATION_21_22,
            MemoryModule.MIGRATION_22_23,
        )

        migrated.query("SELECT content FROM memories WHERE id = 'm18'").use {
            assertTrue("seeded memory did not survive the v18 to v23 chain", it.moveToFirst())
            assertEquals("chain test memory", it.getString(0))
        }

        // The four tables these hops introduce. `runMigrationsAndValidate`
        // compares against the head schema and would catch a missing table, but
        // naming them here is what makes a failure legible.
        for (table in listOf("corrections", "open_questions", "place_visits", "creative_analysis")) {
            migrated.query(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '$table'",
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals("$table was not created by the 18->23 chain", 1, it.getInt(0))
            }
        }
    }

    /**
     * `retiredAt` must be NULL on rows that predate it.
     *
     * Retirement is how a memory stops being retrievable without being
     * destroyed, and every `MemoryDao` retrieval path excludes
     * `retiredAt IS NOT NULL`. So a migration that backfilled these columns with
     * anything non-null would retire the user's **entire** memory store on
     * upgrade — and do it silently, because a store that returns nothing looks
     * exactly like a store with no matches. NULL means "this memory is live",
     * which is why all three columns were added nullable and without defaults.
     */
    @Test
    fun migrate18To19_leavesPreexistingMemoriesLive() {
        val name = "test-aura-memory-18-to-19.db"
        val db = helper.createDatabase(name, 18)
        insertV18Memory(db, "m19", "pre-retirement memory")
        db.close()

        val migrated = helper.runMigrationsAndValidate(name, 19, true, MemoryModule.MIGRATION_18_19)

        migrated.query("SELECT retiredAt FROM memories WHERE id = 'm19'").use {
            assertTrue("pre-retirement row missing after migration", it.moveToFirst())
            assertTrue(
                "retiredAt must be NULL on a pre-existing memory, or the whole store reads as retired",
                it.isNull(0),
            )
        }
    }

    /**
     * The v22 to v23 hop adds `creative_analysis`, keyed to the revision it read.
     *
     * Asserted the same way as the 17->18 hop above, and for the same two
     * reasons. The CASCADE onto `creative_revisions` matters because analysis of
     * a revision that no longer exists is unreachable rows nothing deletes; and
     * the unique index on (revisionId, kind) is the only thing stopping two runs
     * of the same analysis on the same revision from both persisting, which
     * would make "the" result of an analysis ambiguous.
     */
    @Test
    fun migrate22To23_creativeAnalysisCascadesAndIsUniquePerRevisionAndKind() {
        val name = "test-aura-memory-22-to-23.db"
        val db = helper.createDatabase(name, 22)
        db.execSQL(
            "INSERT INTO creative_projects (id, name, description, genre, tone, worldJson, templateId, " +
                "metadataJson, turnCount, lastSessionEnded, createdAt, updatedAt) " +
                "VALUES ('p1', 'Ashfall', '', '', '', '{}', 'novel', '{}', 0, 0, 1000, 1000)",
        )
        db.execSQL(
            "INSERT INTO creative_artifacts (id, projectId, branchId, kind, title, currentRevisionId, " +
                "previewText, mimeType, storageUri, contentHash, status, metadataJson, createdAt, updatedAt) " +
                "VALUES ('a1', 'p1', 'main', 'scene', '1. Arrival', 'r1', '', 'text/plain', NULL, 'h', " +
                "'ready', '{}', 1000, 1000)",
        )
        db.execSQL(
            "INSERT INTO creative_revisions (id, artifactId, branchId, parentRevisionId, contentText, " +
                "storageUri, contentHash, authorKind, providerPrefix, modelId, prompt, settingsJson, createdAt) " +
                "VALUES ('r1', 'a1', 'main', NULL, 'She reached the lighthouse.', NULL, 'h', 'model', " +
                "'test', 'test-model', '', '{}', 1000)",
        )
        db.close()

        val migrated = helper.runMigrationsAndValidate(name, 23, true, MemoryModule.MIGRATION_22_23)

        migrated.execSQL("PRAGMA foreign_keys = ON")
        migrated.execSQL(
            "INSERT INTO creative_analysis (id, revisionId, artifactId, kind, payloadJson, headline, note, createdAt) " +
                "VALUES ('an1', 'r1', 'a1', 'pacing', '{}', 0.5, '', 1000)",
        )

        var threw = false
        try {
            migrated.execSQL(
                "INSERT INTO creative_analysis (id, revisionId, artifactId, kind, payloadJson, headline, note, createdAt) " +
                    "VALUES ('an2', 'r1', 'a1', 'pacing', '{}', 0.9, '', 2000)",
            )
        } catch (_: Exception) {
            threw = true
        }
        assertTrue("a second 'pacing' analysis of the same revision was allowed", threw)

        migrated.execSQL("DELETE FROM creative_revisions WHERE id = 'r1'")
        migrated.query("SELECT COUNT(*) FROM creative_analysis").use {
            assertTrue(it.moveToFirst())
            assertEquals("analysis outlived the revision it read", 0, it.getInt(0))
        }
    }

    /**
     * The v23 to v24 hop adds `retrieval_labels`.
     *
     * The assertion that earns its place is the primary key. It is derived —
     * `"$conversationId|$turnTimestamp|$memoryId"` — precisely so a retry, which
     * re-runs recall for the same turn and gets the same memories back, updates
     * its row instead of writing a second one. Under a random key the export
     * would count one query's judgments once per retry, silently weighting
     * whichever questions the user happened to ask twice. `@Upsert` is what makes
     * that an UPDATE rather than the DELETE-plus-INSERT of `INSERT OR REPLACE`,
     * and this is the level at which that is observable.
     *
     * No foreign key is asserted because none exists or can: `conversations`
     * lives in a different Room database, and SQLite has no cross-database
     * foreign keys.
     */
    /**
     * The 24->25 hop, and the property the ledger rests on: the CASCADE is real.
     *
     * A hand-written migration can create a table whose foreign key clause is
     * syntactically present and unenforced — SQLite silently ignores a malformed
     * one, and `runMigrationsAndValidate` compares columns and indices rather
     * than deleting a parent to see what happens. If the CASCADE were not live,
     * deleting a project would leave its notes behind as orphans pointing at
     * nothing, and `purgeAll` would report a clean database over rows that
     * survived it.
     */
    /**
     * The 25->26 hop, and the property the calibration report rests on.
     *
     * `claim_resolutions` CASCADEs from `beliefs`. If that clause were present
     * but unenforced — SQLite ignores a malformed one silently, and
     * `runMigrationsAndValidate` compares columns and indices rather than
     * deleting a parent to find out — then deleting a belief would strand its
     * verdicts pointing at nothing, and `purgeAll` would report a clean database
     * over rows that outlived it.
     */
    @Test
    fun migrate25To26_addsClaimResolutionsWithALiveCascade() {
        val name = "test-aura-memory-25-to-26.db"
        val db = helper.createDatabase(name, 25)
        insertV18Memory(db, "m25", "pre-calibration memory")
        db.close()

        val migrated = helper.runMigrationsAndValidate(name, 26, true, MemoryModule.MIGRATION_25_26)

        migrated.query("SELECT content FROM memories WHERE id = 'm25'").use {
            assertTrue("memory did not survive the 25->26 hop", it.moveToFirst())
            assertEquals("pre-calibration memory", it.getString(0))
        }

        migrated.execSQL("PRAGMA foreign_keys = ON")
        migrated.execSQL(
            "INSERT INTO beliefs (id, subject, predicate, valueJson, confidence, validFrom, " +
                "validTo, status, supersededBy, privacyClass, agentScope, createdAt, updatedAt, " +
                "lastVerifiedAt) VALUES ('b1', 'user', 'location', '\"Baku\"', 0.9, 0, 0, " +
                "'active', NULL, 'personal', 'general', 1, 1, 0)",
        )
        migrated.execSQL(
            "INSERT INTO claim_resolutions (id, beliefId, verdict, verdictSource, " +
                "assertedConfidence, beliefSource, note, resolvedAt) " +
                "VALUES ('r1', 'b1', 'never_true', 'chat_answer', 0.9, 'user_statement', '', 1)",
        )

        migrated.execSQL("DELETE FROM beliefs WHERE id = 'b1'")
        migrated.query("SELECT COUNT(*) FROM claim_resolutions WHERE beliefId = 'b1'").use {
            assertTrue(it.moveToFirst())
            assertEquals(
                "deleting a belief left its verdicts behind, so the CASCADE in the migration is " +
                    "not enforced and purgeAll would report clean over surviving rows",
                0,
                it.getInt(0),
            )
        }
    }

    @Test
    fun migrate24To25_addsProjectsWithALiveCascade() {
        val name = "test-aura-memory-24-to-25.db"
        val db = helper.createDatabase(name, 24)
        insertV18Memory(db, "m24", "pre-project memory")
        db.close()

        val migrated = helper.runMigrationsAndValidate(name, 25, true, MemoryModule.MIGRATION_24_25)

        migrated.query("SELECT content FROM memories WHERE id = 'm24'").use {
            assertTrue("memory did not survive the 24->25 hop", it.moveToFirst())
            assertEquals("pre-project memory", it.getString(0))
        }

        migrated.execSQL("PRAGMA foreign_keys = ON")
        migrated.execSQL(
            "INSERT INTO projects (id, name, description, status, lastTurnAt, turnCount, " +
                "createdAt, updatedAt) VALUES ('p1', 'ARC-AGI-2', '', 'active', 0, 0, 1, 1)",
        )
        migrated.execSQL(
            "INSERT INTO project_notes (id, projectId, kind, subject, body, sourceConversationId, " +
                "sourceTurnAt, state, supersededBy, resolvedAt, createdAt) " +
                "VALUES ('n1', 'p1', 'decision', 'router', 'three-wisdom architecture', 'c1', " +
                "1, 'active', NULL, NULL, 1)",
        )

        migrated.execSQL("DELETE FROM projects WHERE id = 'p1'")
        migrated.query("SELECT COUNT(*) FROM project_notes WHERE projectId = 'p1'").use {
            assertTrue(it.moveToFirst())
            assertEquals(
                "deleting a project left its notes behind, so the CASCADE in the migration is " +
                    "not enforced and purgeAll would report clean over surviving rows",
                0,
                it.getInt(0),
            )
        }
    }

    @Test
    fun migrate23To24_addsRetrievalLabelsKeyedSoRetriesUpdate() {
        val name = "test-aura-memory-23-to-24.db"
        val db = helper.createDatabase(name, 23)
        insertV18Memory(db, "m23", "pre-label memory")
        db.close()

        val migrated = helper.runMigrationsAndValidate(name, 24, true, MemoryModule.MIGRATION_23_24)

        migrated.query("SELECT content FROM memories WHERE id = 'm23'").use {
            assertTrue("memory did not survive the 23->24 hop", it.moveToFirst())
            assertEquals("pre-label memory", it.getString(0))
        }

        // Same conversation, same turn, same memory, recalled twice at different
        // ranks — one row, newest rank.
        migrated.execSQL(
            "INSERT OR REPLACE INTO retrieval_labels (id, conversationId, turnTimestamp, queryText, " +
                "memoryId, rank, grade, gradeSource, heuristicGrade, signalsJson, sampled, judgedAt, " +
                "queryClass, supersededByEdit, createdAt) " +
                "VALUES ('c1|1000|m23', 'c1', 1000, 'what do I like?', 'm23', 3, NULL, '', NULL, " +
                "'[]', 0, NULL, NULL, 0, 1000)",
        )
        migrated.execSQL(
            "INSERT OR REPLACE INTO retrieval_labels (id, conversationId, turnTimestamp, queryText, " +
                "memoryId, rank, grade, gradeSource, heuristicGrade, signalsJson, sampled, judgedAt, " +
                "queryClass, supersededByEdit, createdAt) " +
                "VALUES ('c1|1000|m23', 'c1', 1000, 'what do I like?', 'm23', 1, NULL, '', NULL, " +
                "'[]', 0, NULL, NULL, 0, 1000)",
        )

        migrated.query("SELECT COUNT(*), MIN(rank) FROM retrieval_labels WHERE conversationId = 'c1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("a re-recall of the same turn wrote a second row", 1, it.getInt(0))
            assertEquals("the newer rank did not win", 1, it.getInt(1))
        }

        // An unjudged row must keep a NULL grade. RetrievalMetrics reads a
        // missing or zero grade as "irrelevant", so a NOT NULL default of 0
        // would score every unjudged recall against the ranker.
        migrated.query("SELECT grade FROM retrieval_labels WHERE id = 'c1|1000|m23'").use {
            assertTrue(it.moveToFirst())
            assertTrue("grade must stay NULL until something judges it", it.isNull(0))
        }
    }

    @Test
    fun migrate28To29() {
        val db = helper.createDatabase("test-aura-memory.db", 28)
        db.execSQL(
            "INSERT INTO creative_projects (id, name, description, genre, tone, worldJson, " +
                "templateId, metadataJson, turnCount, lastSessionEnded, createdAt, updatedAt) " +
                "VALUES ('p1', 'P', '', '', '', '{}', 'novel', '{}', 0, 0, 0, 0)",
        )
        db.execSQL(
            "INSERT INTO living_worlds (id, projectId, branchId, rootSeed, branchSalt, " +
                "parentWorldId, forkedAtTick, worldEpochMs, currentTick, stateJson, status, " +
                "createdAt, updatedAt) " +
                "VALUES ('w1', 'p1', 'b1', 7, 0, '', 0, 0, 5, '{}', 'running', 0, 0)",
        )
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            "test-aura-memory.db",
            29,
            true,
            MemoryModule.MIGRATION_28_29,
        )

        // The pre-v29 world survives with an empty genesis — the exact state
        // fork-at-past checks before declaring itself unavailable.
        migrated.query("SELECT genesisJson, currentTick FROM living_worlds WHERE id = 'w1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("", it.getString(0))
            assertEquals(5, it.getInt(1))
        }
    }
}
