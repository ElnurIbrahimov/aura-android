package com.aura.ui.viewmodel

import android.app.Application
import com.aura.agent.AgentStore
import com.aura.agent.ToolRegistry
import com.aura.capabilities.CapabilityKind
import com.aura.capabilities.CapabilityProvider
import com.aura.capabilities.CapabilityRegistry
import com.aura.data.UserPreferences
import com.aura.emotion.EmotionEngine
import com.aura.hands.HandDao
import com.aura.kg.KnowledgeGraphRepository
import com.aura.memory.MemoryStore
import com.aura.proactive.ProactiveEventBus
import com.aura.proactive.ProactiveEvents
import com.aura.skills.SkillsStore
import com.aura.tasks.ReminderDao
import com.aura.tasks.TaskDao
import com.aura.tools.CalendarReadTool
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test



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
    private val agentStore = mockk<AgentStore>(relaxed = true)
    private val userPreferences = mockk<UserPreferences>(relaxed = true)
    private val emotionEngine = mockk<EmotionEngine>(relaxed = true)

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
                every { agentStore.all() } returns flowOf(emptyList())
        coEvery { agentStore.byId(any()) } returns null
        every { userPreferences.defaultModel } returns flowOf("ollama:general")
        every { userPreferences.agentId } returns flowOf(null)
        every { emotionEngine.snapshot() } returns EmotionEngine.EmotionSnapshot()
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
            creativeProjectStore = mockk(relaxed = true),
            capabilityRegistry = capabilityRegistry,
            agentStore = agentStore,
            userPreferences = userPreferences,
            emotionEngine = emotionEngine,
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

    @Test
    fun `the active agent is named even though defaultModel never completes`() = runTest(dispatcher) {
        // observeActiveAgent opened with `userPreferences.defaultModel.collect { }` — an
        // empty-bodied collect on a DataStore flow, which never completes. Everything after
        // it in that coroutine was unreachable, so activeAgentId and activeAgentName were
        // never set and AgentPresence rendered unnamed on the first screen you see.
        //
        // No test caught it because every harness here stubs preferences with flowOf(),
        // which completes immediately and lets the next line run. Production uses a
        // DataStore flow that does not. This one is a MutableStateFlow for that reason.
        every { userPreferences.defaultModel } returns MutableStateFlow("ollama:general")
        every { userPreferences.agentId } returns MutableStateFlow("agent-1")
        coEvery { agentStore.byId("agent-1") } returns mockk(relaxed = true) {
            every { name } returns "Nova"
        }

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals("agent-1", vm.state.value.activeAgentId)
        assertEquals("Nova", vm.state.value.activeAgentName)
    }
}
