package com.aura.ui.viewmodel

import com.aura.agent.AgentStore
import com.aura.agent.ToolRegistry
import com.aura.agentrun.AgentRunStore
import com.aura.creative.CreativeProjectStore
import com.aura.creative.ProductionPipelineEngine
import com.aura.evolution.EvolutionProposalDao
import com.aura.evolution.EvolutionProposalStore
import com.aura.evolution.EvolutionRollbackManager
import com.aura.evolution.EvolutionSettingsDao
import com.aura.proactive.ProactiveEvents
import com.aura.proactive.ProactiveRunner
import com.aura.skills.SkillsStore
import com.aura.tasks.ReminderDao
import com.aura.tasks.TaskDao
import com.aura.tasks.TaskScheduler
import com.aura.usage.UsageTracker
import com.aura.usage.UsageSnapshot
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the 12 previously-untested ViewModels.
 * Each test constructs the VM with relaxed mocks and verifies
 * the core state machine: initial state, action -> state transition.
 */
class UntestedViewModelsTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── EvolutionBadgeViewModel ──

    @Test
    fun `EvolutionBadgeViewModel exposes pending count from DAO`() = runTest(testDispatcher) {
        val dao = mockk<EvolutionProposalDao>(relaxed = true)
        every { dao.observePendingCount() } returns flowOf(3)
        val vm = com.aura.ui.evolution.EvolutionBadgeViewModel(dao)

        // `pendingCount` is `stateIn(started = WhileSubscribed(5s))`, so the
        // upstream DAO flow is not collected at all until something subscribes
        // — `.value` sits on `initialValue = 0` forever otherwise, no matter how
        // far the dispatcher is advanced.
        //
        // That is why the original assertion was `>= 0` and why its sibling
        // asserting `0` also passed: neither was reading the DAO. Both would
        // have passed against a ViewModel wired to nothing.
        val subscriber = launch { vm.pendingCount.collect {} }
        advanceUntilIdle()

        assertEquals(3, vm.pendingCount.value)
        subscriber.cancel()
    }

    @Test
    fun `EvolutionBadgeViewModel shows no badge until something subscribes`() {
        // Renamed to what it actually pins, which is worth pinning: the badge
        // reads 0 before collection starts, so the bottom nav never flashes a
        // count carried over from a previous process.
        val dao = mockk<EvolutionProposalDao>(relaxed = true)
        every { dao.observePendingCount() } returns flowOf(7)
        val vm = com.aura.ui.evolution.EvolutionBadgeViewModel(dao)
        assertEquals(0, vm.pendingCount.value)
    }

    // ── EvolutionInboxViewModel ──

    @Test
    fun `EvolutionInboxViewModel initial state has empty proposals`() = runTest {
        val dao = mockk<EvolutionProposalDao>(relaxed = true)
        every { dao.observeOpen() } returns flowOf(emptyList())
        every { dao.observePendingCount() } returns flowOf(0)
        val settingsDao = mockk<EvolutionSettingsDao>(relaxed = true)
        val vm = com.aura.ui.evolution.EvolutionInboxViewModel(
            dao,
            mockk(relaxed = true),
            settingsDao,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
        )
        // `size >= 0` was here, which is true of every list that exists.
        assertEquals(emptyList<Any?>(), vm.proposals.value)
    }

    @Test
    fun `EvolutionInboxViewModel dismissOnboarding persists the dismissal`() = runTest {
        val dao = mockk<EvolutionProposalDao>(relaxed = true)
        every { dao.observeOpen() } returns flowOf(emptyList())
        every { dao.observePendingCount() } returns flowOf(0)
        val settingsDao = mockk<EvolutionSettingsDao>(relaxed = true)
        val userPreferences = mockk<com.aura.data.UserPreferences>(relaxed = true)
        val vm = com.aura.ui.evolution.EvolutionInboxViewModel(
            dao,
            mockk(relaxed = true),
            settingsDao,
            mockk(relaxed = true),
            userPreferences,
            mockk(relaxed = true),
        )

        vm.dismissOnboarding()
        // The dismissal happens in `viewModelScope.launch`, so on
        // StandardTestDispatcher nothing runs until this line. The old version
        // asserted nothing, so it never had to notice.
        advanceUntilIdle()

        // Was `vm.dismissOnboarding()` with no assertion at all — named "does
        // not crash", and that is all it could detect. A dismissal that is not
        // written down brings the card back on the next launch, which is the
        // failure a user would actually meet.
        coVerify { userPreferences.setEvolutionOnboardingShown(true) }
        assertEquals(false, vm.showOnboarding.value)
    }

    // ── UsageViewModel ──

    @Test
    fun `UsageViewModel exposes tracker snapshot`() {
        val tracker = mockk<UsageTracker>(relaxed = true)
        val snapshot = UsageSnapshot()
        every { tracker.snapshot } returns MutableStateFlow(snapshot)
        val vm = com.aura.ui.settings.UsageViewModel(tracker, com.aura.usage.BackgroundBudget { System.currentTimeMillis() })
        assertEquals(snapshot, vm.usage.value)
    }

    @Test
    fun `UsageViewModel reset delegates to tracker`() {
        val tracker = mockk<UsageTracker>(relaxed = true)
        every { tracker.snapshot } returns MutableStateFlow(UsageSnapshot())
        every { tracker.reset() } returns Unit
        val vm = com.aura.ui.settings.UsageViewModel(tracker, com.aura.usage.BackgroundBudget { System.currentTimeMillis() })
        vm.reset()
    }

    // ── AgentEditorViewModel ──

    @Test
    fun `AgentEditorViewModel initial state is empty`() {
        val agentStore = mockk<AgentStore>(relaxed = true)
        val toolRegistry = mockk<ToolRegistry>(relaxed = true)
        every { toolRegistry.definitions() } returns emptyList()
        val vm = AgentEditorViewModel(agentStore, toolRegistry)
        assertEquals("", vm.state.value.name)
        assertEquals("", vm.state.value.description)
        assertNull(vm.state.value.id)
    }

    @Test
    fun `AgentEditorViewModel updateName changes state`() {
        val agentStore = mockk<AgentStore>(relaxed = true)
        val toolRegistry = mockk<ToolRegistry>(relaxed = true)
        every { toolRegistry.definitions() } returns emptyList()
        val vm = AgentEditorViewModel(agentStore, toolRegistry)
        vm.updateName("Test Agent")
        assertEquals("Test Agent", vm.state.value.name)
    }

    @Test
    fun `AgentEditorViewModel updateDescription changes state`() {
        val agentStore = mockk<AgentStore>(relaxed = true)
        val toolRegistry = mockk<ToolRegistry>(relaxed = true)
        every { toolRegistry.definitions() } returns emptyList()
        val vm = AgentEditorViewModel(agentStore, toolRegistry)
        vm.updateDescription("A test agent for research")
        assertEquals("A test agent for research", vm.state.value.description)
    }

    @Test
    fun `AgentEditorViewModel showTemplatePicker sets flag`() {
        val agentStore = mockk<AgentStore>(relaxed = true)
        val toolRegistry = mockk<ToolRegistry>(relaxed = true)
        every { toolRegistry.definitions() } returns emptyList()
        val vm = AgentEditorViewModel(agentStore, toolRegistry)
        vm.showTemplatePicker()
        assertTrue(vm.state.value.showTemplatePicker)
        vm.dismissTemplatePicker()
        assertTrue(!vm.state.value.showTemplatePicker)
    }

    // ── AgentRunsViewModel ──

    @Test
    fun `AgentRunsViewModel initial state is empty`() {
        val store = mockk<AgentRunStore>(relaxed = true)
        val context = mockk<android.content.Context>(relaxed = true)
        val vm = AgentRunsViewModel(store, context)
        assertEquals(0, vm.state.value.runs.size)
        assertNull(vm.state.value.selectedRun)
        assertTrue(!vm.state.value.loading)
    }

    @Test
    fun `AgentRunsViewModel clearSelection resets selectedRun`() {
        val store = mockk<AgentRunStore>(relaxed = true)
        val context = mockk<android.content.Context>(relaxed = true)
        val vm = AgentRunsViewModel(store, context)
        vm.clearSelection()
        assertNull(vm.state.value.selectedRun)
    }

    // ── ProactiveHistoryViewModel ──

    @Test
    fun `ProactiveHistoryViewModel exposes events from proactiveEvents`() {
        val events = mockk<ProactiveEvents>(relaxed = true)
        every { events.history } returns MutableStateFlow(emptyList())
        val runner = mockk<ProactiveRunner>(relaxed = true)
        val vm = ProactiveHistoryViewModel(events, runner)
        assertEquals(0, vm.state.value.events.size)
    }

    @Test
    fun `ProactiveHistoryViewModel clearStatus sets null`() {
        val events = mockk<ProactiveEvents>(relaxed = true)
        every { events.history } returns MutableStateFlow(emptyList())
        val runner = mockk<ProactiveRunner>(relaxed = true)
        val vm = ProactiveHistoryViewModel(events, runner)
        vm.clearStatus()
        assertNull(vm.status.value)
    }

    // ── ProductionPipelineViewModel ──

    @Test
    fun `ProductionPipelineViewModel initial state is empty`() {
        val projectStore = mockk<CreativeProjectStore>(relaxed = true)
        val engine = mockk<ProductionPipelineEngine>(relaxed = true)
        val vm = ProductionPipelineViewModel(projectStore, engine)
        assertNull(vm.state.value.selectedProjectId)
        assertNull(vm.state.value.scheduledRunId)
    }

    @Test
    fun `ProductionPipelineViewModel setBrief updates state`() {
        val projectStore = mockk<CreativeProjectStore>(relaxed = true)
        val engine = mockk<ProductionPipelineEngine>(relaxed = true)
        val vm = ProductionPipelineViewModel(projectStore, engine)
        vm.setBrief("Write a novel about space")
        assertEquals("Write a novel about space", vm.state.value.brief)
    }

    @Test
    fun `ProductionPipelineViewModel dismissResult clears scheduledRunId`() {
        val projectStore = mockk<CreativeProjectStore>(relaxed = true)
        val engine = mockk<ProductionPipelineEngine>(relaxed = true)
        val vm = ProductionPipelineViewModel(projectStore, engine)
        vm.dismissResult()
        assertNull(vm.state.value.scheduledRunId)
    }

    // ── ScheduleViewModel ──

    @Test
    fun `ScheduleViewModel initial state has empty lists`() = runTest {
        val taskDao = mockk<TaskDao>(relaxed = true)
        val reminderDao = mockk<ReminderDao>(relaxed = true)
        val scheduler = mockk<TaskScheduler>(relaxed = true)
        every { taskDao.observeAll() } returns flowOf(emptyList())
        every { reminderDao.observeUpcoming(any()) } returns flowOf(emptyList())
        val vm = ScheduleViewModel(taskDao, reminderDao, scheduler)
        // Both were `size >= 0`, true of any list. The test is named "initial
        // state has empty lists" — asserting emptiness is what it always meant.
        assertEquals(emptyList<Any?>(), vm.uiState.value.tasks)
        assertEquals(emptyList<Any?>(), vm.uiState.value.reminders)
    }

    // ── SkillsViewModel ──

    @Test
    fun `SkillsViewModel initial state has empty skills and null selection`() {
        val store = mockk<SkillsStore>(relaxed = true)
        every { store.skills } returns MutableStateFlow(emptyList())
        val vm = SkillsViewModel(store)
        assertEquals(0, vm.skills.value.size)
        assertNull(vm.selectedId.value)
    }

    @Test
    fun `SkillsViewModel select sets selectedId`() {
        val store = mockk<SkillsStore>(relaxed = true)
        every { store.skills } returns MutableStateFlow(emptyList())
        val vm = SkillsViewModel(store)
        vm.select("skill-1")
        assertEquals("skill-1", vm.selectedId.value)
        vm.select(null)
        assertNull(vm.selectedId.value)
    }

    // ── ToolsViewModel ──

    @Test
    fun `ToolsViewModel initial state loads tool definitions`() {
        val registry = mockk<ToolRegistry>(relaxed = true)
        every { registry.definitions() } returns emptyList()
        val vm = ToolsViewModel(registry)
        assertEquals(0, vm.state.value.tools.size)
    }

    @Test
    fun `ToolsViewModel setQuery updates query in state`() {
        val registry = mockk<ToolRegistry>(relaxed = true)
        every { registry.definitions() } returns emptyList()
        val vm = ToolsViewModel(registry)
        vm.setQuery("search")
        assertEquals("search", vm.state.value.query)
    }
}