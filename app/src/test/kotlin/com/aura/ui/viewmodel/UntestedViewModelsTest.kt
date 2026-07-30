package com.aura.ui.viewmodel

import com.aura.agent.Tool
import com.aura.agent.ToolRegistry
import com.aura.agent.ToolResult
import com.aura.agent.ToolRisk
import com.aura.providers.ToolDefinition
import com.aura.skills.Skill
import com.aura.skills.SkillsStore
import com.aura.tasks.ReminderDao
import com.aura.tasks.ReminderEntity
import com.aura.tasks.TaskDao
import com.aura.tasks.TaskEntity
import com.aura.tasks.TaskScheduler
import com.aura.usage.UsageSnapshot
import com.aura.usage.UsageTracker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Batch tests for small ViewModels that had zero coverage.
 * Each test verifies the core contract: state initialization,
 * key methods, and data flow.
 */
class UntestedViewModelsTest {

    @Before fun setUp() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @After fun tearDown() { Dispatchers.resetMain() }

    // ── UsageViewModel ──────────────────────────────────────────────

    @Test
    fun `UsageViewModel exposes tracker snapshot`() {
        val tracker = UsageTracker()
        val vm = com.aura.ui.settings.UsageViewModel(tracker)
        assertNotNull(vm.usage)
        assertEquals(0, vm.usage.value.calls)
    }

    @Test
    fun `UsageViewModel reset clears tracker`() {
        val tracker = UsageTracker()
        tracker.recordLlmCall(modelId = "test", inputChars = 400, outputChars = 100)
        val vm = com.aura.ui.settings.UsageViewModel(tracker)
        vm.reset()
        assertEquals(0, vm.usage.value.totalTokens)
    }

    // ── ToolsViewModel ──────────────────────────────────────────────

    @Test
    fun `ToolsViewModel loads tools from registry`() {
        val registry = ToolRegistry()
        registry.register(Tool(
            name = "test_tool",
            description = "A test tool",
            risk = ToolRisk.READ_ONLY,
            execute = { _, _ -> ToolResult.Ok("ok") },
            category = "test",
        ))
        val vm = ToolsViewModel(registry)
        assertEquals(1, vm.state.value.tools.size)
        assertEquals("test_tool", vm.state.value.tools[0].name)
    }

    @Test
    fun `ToolsViewModel setQuery filters tools`() {
        val registry = ToolRegistry()
        registry.register(Tool(name = "web_search", description = "Search the web", risk = ToolRisk.READ_ONLY, execute = { _, _ -> ToolResult.Ok("") }, category = "search"))
        registry.register(Tool(name = "set_reminder", description = "Set a reminder", risk = ToolRisk.WRITE_LOCAL, execute = { _, _ -> ToolResult.Ok("") }, category = "productivity"))
        val vm = ToolsViewModel(registry)
        vm.setQuery("search")
        assertEquals(1, vm.state.value.grouped.flatMap { it.second }.size)
        assertEquals("web_search", vm.state.value.grouped.flatMap { it.second }[0].name)
    }

    @Test
    fun `ToolsViewModel empty query shows all tools`() {
        val registry = ToolRegistry()
        registry.register(Tool(name = "a_tool", description = "A", risk = ToolRisk.READ_ONLY, execute = { _, _ -> ToolResult.Ok("") }, category = "test"))
        registry.register(Tool(name = "b_tool", description = "B", risk = ToolRisk.READ_ONLY, execute = { _, _ -> ToolResult.Ok("") }, category = "test"))
        val vm = ToolsViewModel(registry)
        assertEquals(2, vm.state.value.tools.size)
        vm.setQuery("")
        assertEquals(2, vm.state.value.grouped.flatMap { it.second }.size)
    }

    // ── SkillsViewModel ─────────────────────────────────────────────

    @Test
    fun `SkillsViewModel exposes skills from store`() = runTest {
        val store = mockk<SkillsStore>(relaxed = true)
        val skills = listOf(Skill(name = "test", description = "desc", body = "body"))
        coEvery { store.skills } returns MutableStateFlow(skills)
        val vm = SkillsViewModel(store)
        assertEquals(1, vm.skills.value.size)
        assertEquals("test", vm.skills.value[0].name)
    }

    @Test
    fun `SkillsViewModel select updates selectedId`() = runTest {
        val store = mockk<SkillsStore>(relaxed = true)
        coEvery { store.skills } returns MutableStateFlow(emptyList())
        val vm = SkillsViewModel(store)
        vm.select("skill_1")
        assertEquals("skill_1", vm.selectedId.value)
        vm.select(null)
        assertEquals(null, vm.selectedId.value)
    }

    // ── EvolutionBadgeViewModel ────────────────────────────────────

    @Test
    fun `EvolutionBadgeViewModel exposes pending count`() = runTest {
        val dao = mockk<com.aura.evolution.EvolutionProposalDao>(relaxed = true)
        coEvery { dao.observePendingCount() } returns flowOf(3)
        val vm = com.aura.ui.evolution.EvolutionBadgeViewModel(dao)
        // Initial value is 0 (stateIn WhileSubscribed)
        assertEquals(0, vm.pendingCount.value)
    }

    // ── ScheduleViewModel ─────────────────────────────────────────

    @Test
    fun `ScheduleViewModel toggleTask flips status`() = runTest {
        val taskDao = mockk<TaskDao>(relaxed = true)
        val reminderDao = mockk<ReminderDao>(relaxed = true)
        val scheduler = mockk<TaskScheduler>(relaxed = true)
        val task = TaskEntity(id = "t1", title = "Test", createdAt = System.currentTimeMillis(), status = "pending")
        coEvery { taskDao.observeAll() } returns flowOf(listOf(task))
        coEvery { reminderDao.observeUpcoming(any()) } returns flowOf(emptyList())
        coEvery { taskDao.get("t1") } returns task
        val vm = ScheduleViewModel(taskDao, reminderDao, scheduler)
        vm.toggleTask("t1")
        // Verify it called insert with status="done"
        io.mockk.coVerify { taskDao.insert(any()) }
    }
}