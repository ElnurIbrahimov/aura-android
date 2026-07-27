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
class CapabilitiesViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val capabilityRegistry = mockk<CapabilityRegistry>(relaxed = true)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): CapabilitiesViewModel {
        return CapabilitiesViewModel(
            application = mockk(relaxed = true),
            capabilityRegistry = capabilityRegistry,
        )
    }

    @Test
    fun `shows all capability kinds`() = runTest(dispatcher) {
        every { capabilityRegistry.configuredForKind(any()) } returns emptyList()
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(CapabilityKind.entries.size, vm.state.value.size)
    }

    @Test
    fun `marks configured kinds active with provider label`() = runTest(dispatcher) {
        val mockProvider = mockk<CapabilityProvider>()
        every { mockProvider.prefix } returns "stability"
        every { capabilityRegistry.configuredForKind(any()) } returns emptyList()
        every { capabilityRegistry.configuredForKind(CapabilityKind.ImageGeneration) } returns listOf(mockProvider)

        val vm = viewModel()
        advanceUntilIdle()

        val imageCard = vm.state.value.first { it.kind == CapabilityKind.ImageGeneration }
        assertTrue(imageCard.isConfigured)
        assertEquals("Stability AI", imageCard.providerLabel)
    }
}
