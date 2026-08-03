package com.aura.agent

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aura.agent.forum.ForumPostEntity
import com.aura.agent.forum.ForumVoteEntity
import com.aura.agent.forum.ForumPostDao
import com.aura.agent.forum.ForumVoteDao
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
        ForumPostEntity::class,
        ForumVoteEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun agentDao(): AgentDao
    abstract fun agentStateDao(): AgentStateDao
    abstract fun agentRelationshipDao(): AgentRelationshipDao
    abstract fun agentObservationDao(): AgentObservationDao
    abstract fun forumPostDao(): ForumPostDao
    abstract fun forumVoteDao(): ForumVoteDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Forum posts: agent-to-agent messages, debates, proposals, interventions.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS forum_posts (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        threadId TEXT NOT NULL,
                        agentId TEXT NOT NULL,
                        replyToId INTEGER,
                        type TEXT NOT NULL,
                        title TEXT NOT NULL,
                        body TEXT NOT NULL,
                        sentiment REAL NOT NULL,
                        status TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY (agentId) REFERENCES agents(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_forum_posts_agentId ON forum_posts(agentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_forum_posts_threadId ON forum_posts(threadId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_forum_posts_status ON forum_posts(status)")

                // Forum votes: agent votes on proposals.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS forum_votes (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        postId INTEGER NOT NULL,
                        agentId TEXT NOT NULL,
                        vote TEXT NOT NULL,
                        reason TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY (postId) REFERENCES forum_posts(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY (agentId) REFERENCES agents(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_forum_votes_postId_agentId ON forum_votes(postId, agentId)")
            }
        }

        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}