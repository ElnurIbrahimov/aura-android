package com.aura.dream

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DreamDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DreamConsolidationDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    /**
     * The hop that had no instrumented test, and needed one most.
     *
     * MIGRATION_1_2 created three tables and none of their seven indices, because
     * a comment above it asserted Room would generate them from the `@Index`
     * annotations. Room does that in `createAllTables` — fresh installs only.
     * `runMigrationsAndValidate` runs Room's own `TableInfo` comparison, which
     * checks indices, so this test fails outright on the version of the migration
     * that shipped. The JVM replay could not: it compared tables and columns, and
     * those were correct.
     *
     * The two UNIQUE indices are asserted by behaviour rather than by name, since
     * they are what the DAOs' REPLACE upserts rely on to dedup.
     */
    @Test
    fun migrate1To2_createsTheNewTablesWithTheirIndices() {
        val name = "test-dream-1-2.db"
        val db = helper.createDatabase(name, 1)
        // Every v1 column, all eight NOT NULL with no default. Guessing this is
        // how migrate28To29 died on its seed row before the migration it exists
        // to prove ever ran.
        db.execSQL(
            "INSERT INTO dream_summaries (id, clusterId, compressedText, sourceMemoryIds, " +
                "dominantTags, sourceCount, modelUsed, createdAt) " +
                "VALUES ('d1', 'c1', 'a summary', '[]', '[]', 1, 'test-model', 1000)",
        )
        db.close()

        // Throws if any declared index is absent — the whole point of this test.
        val migrated = helper.runMigrationsAndValidate(
            name, 2, true, DreamConsolidationModule.MIGRATION_1_2,
        )

        migrated.query("SELECT compressedText FROM dream_summaries WHERE id = 'd1'").use {
            assertTrue("the v1 summary was lost", it.moveToFirst())
            assertEquals("a summary", it.getString(0))
        }

        // index_routines_signature is UNIQUE, so the same signature replaces
        // rather than accumulating. Without it a routine is re-written every cycle.
        val insert = "INSERT OR REPLACE INTO routines (id, signature, displayLabel, occurrenceCount, " +
            "distinctConversations, sourceConversationIds, firstSeenAt, lastSeenAt, description, " +
            "createdAt, updatedAt) VALUES "
        migrated.execSQL(insert + "('r1', 'sig-a', 'A', 2, 1, '[]', 0, 0, '', 0, 0)")
        migrated.execSQL(insert + "('r2', 'sig-a', 'A again', 3, 1, '[]', 0, 0, '', 0, 0)")
        migrated.query("SELECT COUNT(*) FROM routines").use {
            assertTrue(it.moveToFirst())
            assertEquals("index_routines_signature is not UNIQUE, so routines double-write", 1, it.getInt(0))
        }

        // index_kg_edge_proposals_fromNodeId_toNodeId is UNIQUE on the pair, so a
        // proposal the user already saw cannot come back under a new id.
        val proposal = "INSERT OR REPLACE INTO kg_edge_proposals (id, fromNodeId, toNodeId, fromLabel, " +
            "toLabel, similarity, proposedEdge, status, createdAt) VALUES "
        migrated.execSQL(proposal + "('p1', 'n1', 'n2', 'N1', 'N2', 0.9, 'RELATES_TO', 'PENDING', 0)")
        migrated.execSQL(proposal + "('p2', 'n1', 'n2', 'N1', 'N2', 0.9, 'RELATES_TO', 'PENDING', 0)")
        migrated.query("SELECT COUNT(*) FROM kg_edge_proposals").use {
            assertTrue(it.moveToFirst())
            assertEquals("the (fromNodeId, toNodeId) index is not UNIQUE", 1, it.getInt(0))
        }

        migrated.close()
    }

    @Test
    fun migrate2To3_preservesRows_andAddsNullableBeliefColumns() {
        val name = "test-dream-2-3.db"
        val db = helper.createDatabase(name, 2)
        db.execSQL(
            "INSERT INTO contradictions (id, olderSummaryId, newerSummaryId, olderText, newerText, " +
                "triggerPhrase, confidence, status, createdAt) " +
                "VALUES ('c1', 's1', 's2', 'old', 'new', 'no longer', 0.6, 'UNRESOLVED', 1000)",
        )
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            name, 3, true, DreamConsolidationModule.MIGRATION_2_3,
        )

        val cursor = migrated.query("SELECT olderBeliefId, newerBeliefId FROM contradictions WHERE id = 'c1'")
        cursor.use {
            assertTrue("pre-existing contradiction was lost", it.moveToFirst())
            // Existing summary-level rows must survive with NULL belief ids.
            assertTrue(it.isNull(0) && it.isNull(1))
        }
    }
}
