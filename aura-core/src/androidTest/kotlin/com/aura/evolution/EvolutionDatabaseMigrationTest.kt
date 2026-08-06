package com.aura.evolution

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class EvolutionDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        EvolutionDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate1To2_addsTotalRunsAndTotalCandidates() {
        val db = helper.createDatabase("test-evolution-v1.db", 1)
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            "test-evolution-v1.db",
            2,
            true,
            EvolutionModule.ALL_MIGRATIONS[0],
        )
        migrated.query("PRAGMA table_info(evolution_settings)").use { cursor ->
            val names = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                names += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
            assertTrue("totalRuns column missing", names.contains("totalRuns"))
            assertTrue("totalCandidates column missing", names.contains("totalCandidates"))
        }
        migrated.close()
    }

    @Test
    fun migrate2To3_addsShadowEnabled() {
        val db = helper.createDatabase("test-evolution-v2.db", 2)
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            "test-evolution-v2.db",
            3,
            true,
            EvolutionModule.ALL_MIGRATIONS[1],
        )
        migrated.query("PRAGMA table_info(evolution_settings)").use { cursor ->
            val names = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                names += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
            assertTrue("shadowEnabled column missing", names.contains("shadowEnabled"))
        }
        migrated.close()
    }

    @Test
    fun migrate1To4_chained_addsAllColumns() {
        val db = helper.createDatabase("test-evolution-chained.db", 1)
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            "test-evolution-chained.db",
            4,
            true,
            *EvolutionModule.ALL_MIGRATIONS,
        )
        migrated.query("PRAGMA table_info(evolution_settings)").use { cursor ->
            val names = mutableSetOf<String>()
            while (cursor.moveToNext()) {
                names += cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }
            assertTrue("totalRuns missing in chained migration", names.contains("totalRuns"))
            assertTrue("totalCandidates missing in chained migration", names.contains("totalCandidates"))
            assertTrue("shadowEnabled missing in chained migration", names.contains("shadowEnabled"))
        }
        migrated.close()
    }

    @Test
    fun migrate3To4_collapsesDuplicates_cleansRemovedActions_addsIndex() {
        val db = helper.createDatabase("test-evolution-v3.db", 3)
        // Two duplicate PATCH_SKILL candidates for the same key — the newer
        // (createdAt=2000) must survive the collapse.
        db.execSQL(
            "INSERT INTO evolution_candidates (id, domain, action, targetId, argsJson, rationale, score, evidenceIdsJson, status, reflectionResult, createdAt, updatedAt) " +
                "VALUES ('dup-old', 'SKILL', 'PATCH_SKILL', 's1', '{}', 'old', 0.5, '[]', 'PENDING', '', 1000, 1000)"
        )
        db.execSQL(
            "INSERT INTO evolution_candidates (id, domain, action, targetId, argsJson, rationale, score, evidenceIdsJson, status, reflectionResult, createdAt, updatedAt) " +
                "VALUES ('dup-new', 'SKILL', 'PATCH_SKILL', 's1', '{}', 'new', 0.6, '[]', 'PENDING', '', 2000, 2000)"
        )
        // A candidate for a removed action — must be deleted.
        db.execSQL(
            "INSERT INTO evolution_candidates (id, domain, action, targetId, argsJson, rationale, score, evidenceIdsJson, status, reflectionResult, createdAt, updatedAt) " +
                "VALUES ('removed-c', 'PROACTIVE', 'NEW_PROACTIVE_RULE', 'e1', '{}', '', 0.5, '[]', 'PENDING', '', 1500, 1500)"
        )
        // An OPEN proposal for a removed action — must be superseded.
        db.execSQL(
            "INSERT INTO evolution_proposals (id, domain, action, targetId, title, description, summary, patchJson, status, requiresApproval, autoApply, confidence, evidenceIdsJson, candidateIdsJson, applySagaJson, rollbackSnapshotJson, outcomeNote, createdAt, updatedAt, resolvedAt) " +
                "VALUES ('open-removed', 'PROACTIVE', 'NEW_PROACTIVE_RULE', 'e1', 't', '', '', '{}', 'PENDING_REVIEW', 1, 0, 0.5, '[]', '[]', '{}', '{}', '', 1500, 1500, NULL)"
        )
        // An APPLIED proposal for a removed action — history, must be kept as-is.
        db.execSQL(
            "INSERT INTO evolution_proposals (id, domain, action, targetId, title, description, summary, patchJson, status, requiresApproval, autoApply, confidence, evidenceIdsJson, candidateIdsJson, applySagaJson, rollbackSnapshotJson, outcomeNote, createdAt, updatedAt, resolvedAt) " +
                "VALUES ('applied-removed', 'MEMORY', 'FORGET_MEMORY', 'm1', 't', '', '', '{}', 'APPLIED', 1, 0, 0.5, '[]', '[]', '{}', '{}', 'done', 1500, 1500, 1600)"
        )
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            "test-evolution-v3.db",
            4,
            true,
            EvolutionModule.ALL_MIGRATIONS[2],
        )
        // Duplicate collapse kept only the newest row per key.
        migrated.query("SELECT id FROM evolution_candidates WHERE domain='SKILL' AND action='PATCH_SKILL' AND targetId='s1'").use { cursor ->
            assertTrue("expected exactly one surviving candidate", cursor.count == 1)
            cursor.moveToFirst()
            org.junit.Assert.assertEquals("dup-new", cursor.getString(0))
        }
        // Removed-action candidate deleted.
        migrated.query("SELECT COUNT(*) FROM evolution_candidates WHERE action='NEW_PROACTIVE_RULE'").use { cursor ->
            cursor.moveToFirst()
            org.junit.Assert.assertEquals(0, cursor.getInt(0))
        }
        // Open removed-action proposal superseded; applied history untouched.
        migrated.query("SELECT status FROM evolution_proposals WHERE id='open-removed'").use { cursor ->
            cursor.moveToFirst()
            org.junit.Assert.assertEquals("SUPERSEDED", cursor.getString(0))
        }
        migrated.query("SELECT status FROM evolution_proposals WHERE id='applied-removed'").use { cursor ->
            cursor.moveToFirst()
            org.junit.Assert.assertEquals("APPLIED", cursor.getString(0))
        }
        // The dedup index exists with the exact Room-generated name.
        migrated.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name='index_evolution_candidates_domain_action_targetId'"
        ).use { cursor ->
            cursor.moveToFirst()
            org.junit.Assert.assertEquals(1, cursor.getInt(0))
        }
        migrated.close()
    }
}