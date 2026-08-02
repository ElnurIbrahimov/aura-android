package com.aura.ui.viewmodel

import com.aura.agent.AgentEntity
import com.aura.agent.AgentStore
import com.aura.agent.AgentTemplates
import com.aura.agent.PersonalityProfile
import com.aura.agent.ToolRegistry
import com.aura.providers.ToolDefinition
import com.aura.providers.ToolParameters
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentEditorViewModelTest {

    private val agentStore = mockk<AgentStore>(relaxed = true)
    private val toolRegistry = mockk<ToolRegistry>(relaxed = true)

    @Before
    fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After
    fun tearDown() { Dispatchers.resetMain() }

    private fun makeVm(): AgentEditorViewModel {
        every { toolRegistry.definitions() } returns listOf(
            ToolDefinition(name = "web_search", description = "Search", parameters = ToolParameters(), category = "search"),
            ToolDefinition(name = "timer", description = "Set timer", parameters = ToolParameters(), category = "utility"),
        )
        return AgentEditorViewModel(agentStore, toolRegistry)
    }

    @Test
    fun `availableTools returns sorted tool names`() {
        val vm = makeVm()

        val tools = vm.availableTools

        assertEquals(2, tools.size)
        assertEquals("timer", tools[0])
        assertEquals("web_search", tools[1])
    }

    @Test
    fun `availableTools returns empty when registry is empty`() {
        every { toolRegistry.definitions() } returns emptyList()
        val vm = AgentEditorViewModel(agentStore, toolRegistry)

        assertEquals(0, vm.availableTools.size)
    }

    @Test
    fun `showTemplatePicker sets showTemplatePicker true`() {
        val vm = makeVm()

        vm.showTemplatePicker()

        assertTrue(vm.state.value.showTemplatePicker)
    }

    @Test
    fun `dismissTemplatePicker sets showTemplatePicker false`() {
        val vm = makeVm()
        vm.showTemplatePicker()

        vm.dismissTemplatePicker()

        assertFalse(vm.state.value.showTemplatePicker)
    }

    @Test
    fun `applyTemplate populates state from template`() {
        val vm = makeVm()
        val template = AgentTemplates.Template(
            name = "Researcher",
            description = "Research assistant",
            systemPromptHint = "You are a researcher",
            toolsAllowed = setOf("web_search"),
            personality = PersonalityProfile(warmth = 0.3f),
        )

        vm.applyTemplate(template)

        val state = vm.state.value
        assertEquals("Researcher", state.name)
        assertEquals("You are a researcher", state.identity)
        assertEquals(setOf("web_search"), state.toolsAllowed)
        assertFalse(state.showTemplatePicker)
    }

    @Test
    fun `loadAgent populates state from store`() = runTest {
        val agent = AgentEntity(
            id = "agent-1",
            name = "Test Agent",
            icon = "robot",
            description = "desc",
            identity = "You are Test Agent",
            toolsAllowed = """["web_search"]""",
            preferredModel = "ollama:deepseek-v4-pro:cloud",
            memoryScope = "private",
            personalityJson = "{}",
            color = 0,
            isBuiltin = false,
        )
        coEvery { agentStore.byId("agent-1") } returns agent
        val vm = makeVm()

        vm.loadAgent("agent-1")

        val state = vm.state.value
        assertEquals("agent-1", state.id)
        assertEquals("Test Agent", state.name)
        assertEquals("You are Test Agent", state.identity)
    }

    @Test
    fun `loadAgent with unknown id is no-op`() = runTest {
        coEvery { agentStore.byId("unknown") } returns null
        val vm = makeVm()

        vm.loadAgent("unknown")

        assertNull(vm.state.value.id)
    }

    @Test
    fun `templates exposes all templates`() {
        val vm = makeVm()

        assertTrue(vm.templates.isNotEmpty())
    }
}