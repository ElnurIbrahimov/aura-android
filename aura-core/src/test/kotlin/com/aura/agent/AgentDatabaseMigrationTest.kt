package com.aura.agent

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AgentDatabaseMigrationTest {

    @Rule
    @JvmField
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AgentDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate1To2_createsStateRelationshipsObservationsTables() {
        val db = helper.createDatabase("agents", 1)
        db.execSQL("INSERT INTO agents (id, name, icon, description, identity, toolsAllowed, memoryScope, personalityJson, isBuiltin, isDefault, createdAt, updatedAt, color) VALUES ('test', 'test', 'test', 'desc', 'identity', '', 'shared', '{}', 1, 0, 0, 0, 0)")
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            "agents",
            2,
            true,
            AgentDatabase.MIGRATION_1_2,
        )

        val cursor = migrated.query("SELECT name FROM sqlite_master WHERE type='table'")
        val tables = mutableListOf<kotlin.String>()
        cursor.use {
            while (it.moveToNext()) {
                tables.add(it.getString(0))
            }
        }
        assertTrue("agents table should exist", tables.contains("agents"))
        assertTrue("agent_state table should exist", tables.contains("agent_state"))
        assertTrue("agent_relationships table should exist", tables.contains("agent_relationships"))
        assertTrue("agent_observations table should exist", tables.contains("agent_observations"))
        migrated.close()
    }

    @Test
    fun migrate2To3_createsForumTables() {
        // Start at v2 (create with v1 first, migrate to v2, then migrate to v3)
        val db = helper.createDatabase("agents", 1)
        db.execSQL("INSERT INTO agents (id, name, icon, description, identity, toolsAllowed, memoryScope, personalityJson, isBuiltin, isDefault, createdAt, updatedAt, color) VALUES ('test', 'test', 'test', 'desc', 'identity', '', 'shared', '{}', 1, 0, 0, 0, 0)")
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            "agents",
            3,
            true,
            AgentDatabase.MIGRATION_1_2,
            AgentDatabase.MIGRATION_2_3,
        )

        val cursor = migrated.query("SELECT name FROM sqlite_master WHERE type='table'")
        val tables = mutableListOf<kotlin.String>()
        cursor.use {
            while (it.moveToNext()) {
                tables.add(it.getString(0))
            }
        }
        assertTrue("forum_posts table should exist", tables.contains("forum_posts"))
        assertTrue("forum_votes table should exist", tables.contains("forum_votes"))
        migrated.close()
    }
}