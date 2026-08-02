package com.aura.creative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration tests for the creative system.
 * Verifies the wiring between SmartCodexInjector, GenreCraftPrompts,
 * ProseCraftTools, and CreativeCouncil.
 */
class CreativeSystemIntegrationTest {

    @Test
    fun `GenreCraftPrompts returns non-null for known templates`() {
        val prompt = GenreCraftPrompts.forTemplate("novel")
        assertNotNull(prompt)
        assertTrue(prompt!!.isNotBlank())
        assertTrue(prompt.length > 100)
    }

    @Test
    fun `GenreCraftPrompts returns null for unknown template`() {
        val prompt = GenreCraftPrompts.forTemplate("nonexistent")
        assertEquals(null, prompt)
    }

    @Test
    fun `GenreCraftPrompts forMode returns non-blank for all 6 modes`() {
        for (mode in CreativeMode.entries) {
            val prompt = GenreCraftPrompts.forMode(mode)
            assertTrue("Mode $mode prompt should not be blank", prompt.isNotBlank())
            assertTrue("Mode $mode prompt should contain guidance",
                prompt.length > 50)
        }
    }

    @Test
    fun `SmartCodexInjector filters world bible to relevant entries only`() {
        val world = WorldBible(
            characters = listOf(
                WorldCharacter(name = "Alice", role = "protagonist", traits = listOf("brave"), backstory = ""),
                WorldCharacter(name = "Bob", role = "antagonist", traits = listOf("cunning"), backstory = ""),
                WorldCharacter(name = "Charlie", role = "minor", traits = listOf("quiet"), backstory = ""),
            ),
            locations = listOf(
                WorldLocation(name = "Castle", description = "Ancient fortress", type = "fortress"),
                WorldLocation(name = "Forest", description = "Dark woods", type = "wilderness"),
            ),
        )
        val injector = SmartCodexInjector()
        val filtered = injector.filterRelevant(world, "Alice walked into the Castle")
        assertTrue(filtered.characters.any { it.name == "Alice" })
        assertTrue(filtered.locations.any { it.name == "Castle" })
        // Bob and Forest should be filtered out
        assertTrue(filtered.characters.none { it.name == "Bob" })
    }

    @Test
    fun `SmartCodexInjector returns empty when no matches`() {
        val world = WorldBible(
            characters = listOf(
                WorldCharacter(name = "Alice", role = "protagonist", traits = emptyList(), backstory = ""),
            ),
        )
        val injector = SmartCodexInjector()
        val filtered = injector.filterRelevant(world, "Once upon a time")
        assertTrue(filtered.characters.isEmpty())
        assertFalse(injector.hasContent(filtered))
    }

    @Test
    fun `SmartCodexInjector hasContent returns true when characters present`() {
        val world = WorldBible(
            characters = listOf(
                WorldCharacter(name = "Alice", role = "protagonist", traits = emptyList(), backstory = ""),
            ),
        )
        val injector = SmartCodexInjector()
        val filtered = injector.filterRelevant(world, "Alice")
        assertTrue(injector.hasContent(filtered))
    }

    @Test
    fun `ProseCraftTools CraftTool enum has 6 entries`() {
        assertEquals(6, ProseCraftTools.CraftTool.entries.size)
    }

    @Test
    fun `CreativeCouncil has 10 roles`() {
        assertEquals(10, CouncilRole.entries.size)
    }

    @Test
    fun `every council role has a non-blank display name`() {
        for (role in CouncilRole.entries) {
            assertTrue("Role $role should have non-blank displayName",
                role.displayName.isNotBlank())
        }
    }

    @Test
    fun `SmartCodexInjector handles empty world bible`() {
        val injector = SmartCodexInjector()
        val filtered = injector.filterRelevant(WorldBible(), "test prompt")
        assertNotNull(filtered)
        assertTrue(filtered.characters.isEmpty())
    }

    private fun assertFalse(condition: Boolean) {
        org.junit.Assert.assertFalse(condition)
    }
}