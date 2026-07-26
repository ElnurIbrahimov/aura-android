package com.aura.dream

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
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
