package com.aura.ui.viewmodel

import android.app.Application
import com.aura.capabilities.CapabilityKind
import com.aura.capabilities.CapabilityProvider
import com.aura.capabilities.CapabilityRegistry
import com.aura.hands.HandDao
import com.aura.kg.KnowledgeGraphRepository
import com.aura.memory.MemoryStore
import com.aura.proactive.ProactiveEventBus
import com.aura.proactive.ProactiveEvents
import com.aura.skills.SkillsStore
import com.aura.tasks.ReminderDao
import com.aura.tasks.TaskDao
import com.aura.tools.CalendarReadTool
import com.aura.agent.ToolRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelCapabilitiesTest {

    private val dispatcher = StandardTestDispatcher()
    private val application = mockk<Application>(relaxed = true)
    private val memoryStore = mockk<MemoryStore>(relaxed = true)
    private val taskDao = mockk<TaskDao>(relaxed = true)
    private val proactiveEvents = mockk<ProactiveEvents>(relaxed = true)
    private val calendarReadTool = mockk<CalendarReadTool>(relaxed = true)
    private val knowledgeGraphRepository = mockk<KnowledgeGraphRepository>(relaxed = true)
    private val reminderDao = mockk<ReminderDao>(relaxed = true)
    private val handDao = mockk<HandDao>(relaxed = true)
    private val toolRegistry = mockk<ToolRegistry>(relaxed = true)
    private val skillsStore = mockk<SkillsStore>(relaxed = true)
    private val capabilityRegistry = mockk<CapabilityRegistry>(relaxed = true)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { memoryStore.observeCount() } returns flowOf(0)
        coEvery { memoryStore.recent(any()) } returns emptyList()
        every { taskDao.observeAll() } returns flowOf(emptyList())
        coEvery { taskDao.allPending() } returns emptyList()
        every { handDao.observeAll() } returns flowOf(emptyList())
        coEvery { skillsStore.awaitLoaded() } returns Unit
        every { skillsStore.skills } returns MutableStateFlow(emptyList())
        every { reminderDao.observeUpcoming(any()) } returns flowOf(emptyList())
        every { proactiveEvents.latest } returns MutableStateFlow(null)
        every { proactiveEvents.unreadCount } returns MutableStateFlow(0)
        every { proactiveEvents.history } returns MutableStateFlow(emptyList())
        every { toolRegistry.definitions() } returns emptyList()
        every { capabilityRegistry.forKind(any()) } returns null
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun viewModel(): HomeViewModel {
        return HomeViewModel(
            application = application,
            memoryStore = memoryStore,
            taskDao = taskDao,
            proactiveEvents = proactiveEvents,
            calendarReadTool = calendarReadTool,
            knowledgeGraphRepository = knowledgeGraphRepository,
            reminderDao = reminderDao,
            handDao = handDao,
            toolRegistry = toolRegistry,
            skillsStore = skillsStore,
            capabilityRegistry = capabilityRegistry,
        )
    }

    @Test
    fun `active capabilities appear in state`() = runTest(dispatcher) {
        val provider = mockk<CapabilityProvider>()
        every { provider.prefix } returns "exa"
        every { capabilityRegistry.forKind(CapabilityKind.WebSearch) } returns provider

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(1, vm.state.value.activeCapabilities.size)
        assertEquals("Exa Search", vm.state.value.activeCapabilities[CapabilityKind.WebSearch])
        assertTrue(vm.state.value.hasHomeData())
    }
}
