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
    fun migrate1To3_chained_addsAllColumns() {
        val db = helper.createDatabase("test-evolution-chained.db", 1)
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            "test-evolution-chained.db",
            3,
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
}