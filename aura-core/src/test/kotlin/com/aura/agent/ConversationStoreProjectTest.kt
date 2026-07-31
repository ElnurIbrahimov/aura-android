package com.aura.agent

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationStoreProjectTest {

    private val dao = mockk<ConversationDao>(relaxed = true)
    private val embedder = mockk<com.aura.memory.Embedder>(relaxed = true)
    private val store = ConversationStore(dao, embedder)

    @Test
    fun `projectOf returns null when no project tag`() {
        val conv = Conversation(id = "1", metadata = mapOf("pinned" to "true"))
        assertNull(store.projectOf(conv))
    }

    @Test
    fun `projectOf returns project name when tagged`() {
        val conv = Conversation(id = "1", metadata = mapOf("project" to "My App"))
        assertEquals("My App", store.projectOf(conv))
    }

    @Test
    fun `projectOf returns null for blank project tag`() {
        val conv = Conversation(id = "1", metadata = mapOf("project" to ""))
        assertNull(store.projectOf(conv))
    }

    @Test
    fun `setProject updates metadata with project tag`() = runTest {
        val entity = ConversationEntity(id = "1", title = "Test", createdAt = 0L, updatedAt = 0L, systemPrompt = null, model = null, metadataJson = "{}")
        coEvery { dao.getById("1") } returns entity

        store.setProject("1", "Research Project")

        coVerify {
            dao.insert(match {
                val meta = Json.decodeFromString<Map<String, String>>(it.metadataJson)
                meta["project"] == "Research Project"
            })
        }
    }

    @Test
    fun `setProject with null removes existing project tag`() = runTest {
        val entity = ConversationEntity(id = "1", title = "Test", createdAt = 0L, updatedAt = 0L, systemPrompt = null, model = null, metadataJson = """{"project":"Old Project","pinned":"true"}""",
        )
        coEvery { dao.getById("1") } returns entity

        store.setProject("1", null)

        coVerify {
            dao.insert(match {
                val meta = Json.decodeFromString<Map<String, String>>(it.metadataJson)
                !meta.containsKey("project") && meta["pinned"] == "true"
            })
        }
    }

    @Test
    fun `setProject returns false when conversation not found`() = runTest {
        coEvery { dao.getById("missing") } returns null
        assertFalse(store.setProject("missing", "Test"))
    }

    @Test
    fun `setProject preserves existing metadata when adding project`() = runTest {
        val entity = ConversationEntity(id = "1", title = "Test", createdAt = 0L, updatedAt = 0L, systemPrompt = null, model = null, metadataJson = """{"pinned":"true","custom":"value"}""",
        )
        coEvery { dao.getById("1") } returns entity

        store.setProject("1", "New Project")

        coVerify {
            dao.insert(match {
                val meta = Json.decodeFromString<Map<String, String>>(it.metadataJson)
                meta["project"] == "New Project" &&
                meta["pinned"] == "true" &&
                meta["custom"] == "value"
            })
        }
    }
}