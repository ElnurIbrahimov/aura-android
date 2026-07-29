package com.aura.search

import com.aura.agent.ConversationDao
import com.aura.agent.ConversationEntity
import com.aura.hands.Hand
import com.aura.hands.HandDao
import com.aura.kg.KnowledgeGraphDao
import com.aura.kg.NodeEntity
import com.aura.memory.MemoryDao
import com.aura.memory.MemoryEntity
import com.aura.skills.Skill
import com.aura.skills.SkillsStore
import com.aura.tasks.TaskDao
import com.aura.tasks.TaskEntity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that a failing data source doesn't crash the entire search
 * and that other sources continue to return results.
 *
 * Regression coverage for the error logging added 2026-07-29.
 */
class GlobalSearchRepositoryErrorIsolationTest {

    private val conversationDao = mockk<ConversationDao>()
    private val memoryDao = mockk<MemoryDao>()
    private val taskDao = mockk<TaskDao>()
    private val handDao = mockk<HandDao>()
    private val kgDao = mockk<KnowledgeGraphDao>()
    private val skillsStore = mockk<SkillsStore>()

    private val repo = GlobalSearchRepository(
        conversationDao = conversationDao,
        memoryDao = memoryDao,
        taskDao = taskDao,
        handDao = handDao,
        kgDao = kgDao,
        skillsStore = skillsStore,
    )

    @Test
    fun `search survives one failing data source`() = runBlocking {
        // One source fails, the rest succeed.
        coEvery { conversationDao.searchVisible(any(), any()) } throws RuntimeException("DB corrupt")
        coEvery { memoryDao.searchByText(any(), any()) } returns listOf(
            MemoryEntity(id = "m1", content = "hello world", category = "general", source = "user"),
        )
        coEvery { taskDao.all() } returns emptyList()
        coEvery { handDao.getAll() } returns emptyList()
        every { skillsStore.skills } returns MutableStateFlow(emptyList())
        coEvery { kgDao.searchNodes(any(), any()) } returns emptyList()

        val results = repo.search("hello")

        // Should still get memory results even though conversations failed.
        assertTrue("Should return memory results despite conversation failure", results.isNotEmpty())
        assertEquals(SearchCategory.MEMORY, results.first().category)
    }

    @Test
    fun `search returns empty list when all sources throw`() = runBlocking {
        coEvery { conversationDao.searchVisible(any(), any()) } throws RuntimeException("DB down")
        coEvery { memoryDao.searchByText(any(), any()) } throws RuntimeException("DB down")
        coEvery { taskDao.all() } throws RuntimeException("DB down")
        coEvery { handDao.getAll() } throws RuntimeException("DB down")
        every { skillsStore.skills } returns MutableStateFlow(emptyList())
        coEvery { kgDao.searchNodes(any(), any()) } throws RuntimeException("DB down")

        val results = repo.search("hello")

        // Should not crash — returns empty list gracefully.
        assertEquals("All sources failed, should return empty list", 0, results.size)
    }

    @Test
    fun `search returns empty for blank query without hitting DB`() = runBlocking {
        // Blank query should short-circuit without calling any DAO.
        val results = repo.search("   ")
        assertEquals(0, results.size)
    }
}
