package com.aura.agent

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aura.agent.state.AgentObservationEntity
import com.aura.agent.state.AgentRelationshipEntity
import com.aura.agent.state.AgentStateDao
import com.aura.agent.state.AgentObservationDao
import com.aura.agent.state.AgentRelationshipDao
import com.aura.agent.state.AgentStateEntity

@Database(
    entities = [
        AgentEntity::class,
        AgentStateEntity::class,
        AgentRelationshipEntity::class,
        AgentObservationEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun agentDao(): AgentDao
    abstract fun agentStateDao(): AgentStateDao
    abstract fun agentRelationshipDao(): AgentRelationshipDao
    abstract fun agentObservationDao(): AgentObservationDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Agent state: persistent mood, energy, goal, stance.
                // No DEFAULT clauses — Room entity defaults are handled in Kotlin,
                // not in the SQL schema. NOT NULL columns without defaults are
                // fine because Room always supplies values on INSERT.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agent_state (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        agentId TEXT NOT NULL,
                        mood REAL NOT NULL,
                        energy REAL NOT NULL,
                        currentGoal TEXT NOT NULL,
                        stanceOnUser REAL NOT NULL,
                        participationCount INTEGER NOT NULL,
                        lastActiveAt INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY (agentId) REFERENCES agents(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_agent_state_agentId ON agent_state(agentId)")

                // Agent relationships: affinity between pairs of agents.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agent_relationships (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        agentAId TEXT NOT NULL,
                        agentBId TEXT NOT NULL,
                        affinity REAL NOT NULL,
                        conflictCount INTEGER NOT NULL,
                        collaborationCount INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        FOREIGN KEY (agentAId) REFERENCES agents(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY (agentBId) REFERENCES agents(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_agent_relationships_agentAId_agentBId ON agent_relationships(agentAId, agentBId)")

                // Agent observations: private notes agents keep about user/agents/self.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS agent_observations (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        agentId TEXT NOT NULL,
                        targetType TEXT NOT NULL,
                        targetId TEXT NOT NULL,
                        content TEXT NOT NULL,
                        sentiment REAL NOT NULL,
                        weight REAL NOT NULL,
                        resolved INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY (agentId) REFERENCES agents(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_observations_agentId ON agent_observations(agentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_observations_agentId_resolved ON agent_observations(agentId, resolved)")
            }
        }

        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2)
    }
}