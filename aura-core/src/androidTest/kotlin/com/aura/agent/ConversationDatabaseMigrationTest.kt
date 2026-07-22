package com.aura.agent

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class ConversationDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ConversationDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate1To2_preservesConversations_andAddsEmbedding() {
        val db = helper.createDatabase("test-conversations.db", 1)
        // Insert a conversation using the v1 schema (no embedding column).
        db.execSQL(
            """
            INSERT INTO conversations (id, title, createdAt, updatedAt, systemPrompt, model, metadataJson, turnsJson)
            VALUES ('conv-1', 'Test chat', 1700000000, 1700000100, 'You are Aura', 'ollama:glm-5.1:cloud', '{}', '[]')
            """.trimIndent(),
        )
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            "test-conversations.db",
            2,
            true,
            ConversationModule.MIGRATION_1_2,
        )

        // Verify the conversation survived.
        val cursor = migrated.query("SELECT * FROM conversations WHERE id = 'conv-1'")
        cursor.use {
            assert(it.moveToFirst()) { "Conversation row should survive migration" }
            assert(it.getString(it.getColumnIndexOrThrow("title")) == "Test chat")
            // The embedding column should exist and be NULL (lazily populated).
            val embeddingIdx = it.getColumnIndexOrThrow("embedding")
            assert(it.isNull(embeddingIdx)) { "embedding should be NULL after migration" }
        }

        migrated.close()
    }

    @Test
    fun migrate3To4_preservesTurns_andAddsCompactionState() {
        val db = helper.createDatabase("test-conversations-3-4.db", 3)
        db.execSQL(
            "INSERT INTO conversations (id, title, createdAt, updatedAt, systemPrompt, model, metadataJson, turnsJson, embedding) " +
                "VALUES ('conv-3', 'Long chat', 1, 2, '', 'test:model', '{}', '[{\"user\":\"hello\"}]', NULL)",
        )
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            "test-conversations-3-4.db", 4, true, ConversationModule.MIGRATION_3_4,
        )

        migrated.query(
            "SELECT turnsJson, contextSummary, summaryThroughTurn FROM conversations WHERE id = 'conv-3'",
        ).use {
            assert(it.moveToFirst())
            assert(it.getString(0).contains("hello"))
            assert(it.getString(1) == "")
            assert(it.getInt(2) == 0)
        }
        migrated.close()
    }

    @Test
    fun migrate2To3_preservesConversations_andAddsUpdatedAtIndex() {
        val db = helper.createDatabase("test-conversations-2-3.db", 2)
        db.execSQL(
            "INSERT INTO conversations (id, title, createdAt, updatedAt, systemPrompt, model, metadataJson, turnsJson, embedding) " +
                "VALUES ('conv-2', 'Indexed chat', 1, 2, '', 'test:model', '{}', '[]', NULL)",
        )
        db.close()
        val migrated = helper.runMigrationsAndValidate(
            "test-conversations-2-3.db", 3, true, ConversationModule.MIGRATION_2_3,
        )
        migrated.query("SELECT title FROM conversations WHERE id = 'conv-2'").use {
            assert(it.moveToFirst() && it.getString(0) == "Indexed chat")
        }
        migrated.query("PRAGMA index_list('conversations')").use {
            var found = false
            while (it.moveToNext()) found = found || it.getString(it.getColumnIndexOrThrow("name")) == "index_conversations_updatedAt"
            assert(found)
        }
        migrated.close()
    }

    @Test
    fun migrate5To6_preservesConversations_andAddsDeletedAt() {
        // Soft-delete was added in commit e21d6d55. Migration 5→6
        // adds the deletedAt column (nullable, no default = NULL for
        // existing rows = visible). Without this test, an invalid
        // SQL statement or column type mismatch would silently break
        // on upgrade — every existing user would lose their History.
        val db = helper.createDatabase("test-conversations-5-6.db", 5)
        db.execSQL(
            "INSERT INTO conversations (id, title, createdAt, updatedAt, systemPrompt, model, " +
                "metadataJson, turnsJson, contextSummary, summaryThroughTurn, agentId) " +
                "VALUES ('conv-5', 'Pre-soft-delete chat', 1, 2, '', 'test:model', '{}', " +
                "'[{\"user\":\"hello\"}]', '', 0, 'coder')",
        )
        db.close()

        val migrated = helper.runMigrationsAndValidate(
            "test-conversations-5-6.db", 6, true, ConversationModule.MIGRATION_5_6,
        )

        // The pre-existing row should survive with all its data intact.
        migrated.query(
            "SELECT title, agentId, deletedAt FROM conversations WHERE id = 'conv-5'",
        ).use {
            assert(it.moveToFirst())
            assert(it.getString(0) == "Pre-soft-delete chat")
            assert(it.getString(1) == "coder")
            // deletedAt must exist and default to NULL for visible rows.
            assert(it.isNull(2)) { "deletedAt should default to NULL (visible)" }
        }

        // Verify the column is actually present (not just the data).
        migrated.query("PRAGMA table_info('conversations')").use {
            var found = false
            while (it.moveToNext()) {
                found = found || it.getString(it.getColumnIndexOrThrow("name")) == "deletedAt"
            }
            assert(found) { "deletedAt column should exist after migration" }
        }
        migrated.close()
    }
}