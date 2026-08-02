package com.aura.ui.viewmodel

import com.aura.agent.ToolRegistry
import com.aura.providers.ToolDefinition
import com.aura.providers.ToolParameters
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [ToolsViewModel] — verifies search filtering and
 * category grouping logic.
 */
class ToolsViewModelTest {

    private fun makeTool(name: String, category: String, description: String = ""): ToolDefinition =
        ToolDefinition(
            name = name,
            description = description,
            parameters = ToolParameters(),
            category = category,
        )

    private fun makeRegistry(vararg tools: ToolDefinition): ToolRegistry {
        val registry = mockk<ToolRegistry>(relaxed = true)
        every { registry.definitions() } returns tools.toList()
        return registry
    }

    @Test
    fun `loads all tools sorted by name on init`() {
        val registry = makeRegistry(
            makeTool("zebra_search", "search"),
            makeTool("alpha_tool", "utility"),
            makeTool("mid_tool", "search"),
        )
        val vm = ToolsViewModel(registry)

        val state = vm.state.value
        assertEquals(3, state.tools.size)
        assertEquals("alpha_tool", state.tools[0].name)
        assertEquals("mid_tool", state.tools[1].name)
        assertEquals("zebra_search", state.tools[2].name)
    }

    @Test
    fun `query filters by name case-insensitive`() {
        val registry = makeRegistry(
            makeTool("web_search", "search", "Search the web"),
            makeTool("calendar_read", "calendar", "Read calendar"),
            makeTool("timer", "utility", "Set a timer"),
        )
        val vm = ToolsViewModel(registry)

        vm.setQuery("search")

        val grouped = vm.state.value.grouped
        assertEquals(1, grouped.sumOf { it.second.size })
        assertEquals("web_search", grouped.first().second.first().name)
    }

    @Test
    fun `query filters by description`() {
        val registry = makeRegistry(
            makeTool("web_search", "search", "Search the web"),
            makeTool("calendar_read", "calendar", "Read calendar events"),
        )
        val vm = ToolsViewModel(registry)

        vm.setQuery("calendar events")

        val grouped = vm.state.value.grouped
        assertEquals(1, grouped.sumOf { it.second.size })
    }

    @Test
    fun `empty query shows all tools`() {
        val registry = makeRegistry(
            makeTool("web_search", "search"),
            makeTool("timer", "utility"),
        )
        val vm = ToolsViewModel(registry)

        vm.setQuery("")
        assertEquals(2, vm.state.value.grouped.sumOf { it.second.size })
    }

    @Test
    fun `grouped buckets by category`() {
        val registry = makeRegistry(
            makeTool("web_search", "search"),
            makeTool("tavily_search", "search"),
            makeTool("timer", "utility"),
        )
        val vm = ToolsViewModel(registry)

        val grouped = vm.state.value.grouped
        val searchGroup = grouped.find { it.first == "search" }
        val utilityGroup = grouped.find { it.first == "utility" }
        assertNotNull(searchGroup)
        assertNotNull(utilityGroup)
        assertEquals(2, searchGroup!!.second.size)
        assertEquals(1, utilityGroup!!.second.size)
    }

    private fun <T> assertNotNull(value: T?) {
        assertTrue(value != null)
    }
}