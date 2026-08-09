package com.aura.providers

import com.aura.data.UserPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRoleRouterTest {

    private val userPreferences = mockk<UserPreferences>(relaxed = true)
    private val providerRegistry = mockk<ProviderRegistry>(relaxed = true)
    private val router = ModelRoleRouter(userPreferences, providerRegistry)

    @Test
    fun resolve_returns_role_specific_model_when_set() = runTest {
        every { userPreferences.forRole(ModelRole.CREATIVE_DRAFT) } returns flowOf("ollama:deepseek-v4-pro:cloud")
        coEvery { userPreferences.defaultModel } returns flowOf("ollama:gemma4:e4b")
        // Need to mock the Flow returned by forRole
        val result = router.resolve(ModelRole.CREATIVE_DRAFT)
        assertEquals("ollama:deepseek-v4-pro:cloud", result)
    }

    @Test
    fun resolve_falls_back_to_default_when_role_not_set() = runTest {
        every { userPreferences.forRole(ModelRole.PLANNER) } returns flowOf(null)
        every { userPreferences.defaultModel } returns flowOf("ollama:deepseek-v4-pro:cloud")
        val result = router.resolve(ModelRole.PLANNER)
        assertEquals("ollama:deepseek-v4-pro:cloud", result)
    }

    @Test
    fun resolve_returns_null_when_nothing_configured() = runTest {
        every { userPreferences.forRole(ModelRole.VERIFIER) } returns flowOf(null)
        every { userPreferences.defaultModel } returns flowOf(null)
        val result = router.resolve(ModelRole.VERIFIER)
        assertNull(result)
    }

    @Test
    fun resolve_conversation_uses_default_model_directly() = runTest {
        every { userPreferences.forRole(ModelRole.CONVERSATION) } returns flowOf("ollama:deepseek-v4-pro:cloud")
        val result = router.resolve(ModelRole.CONVERSATION)
        assertEquals("ollama:deepseek-v4-pro:cloud", result)
    }

    /**
     * A role belongs in Settings only if something reads it. A row that
     * persists, backs up, restores and routes nothing is worse than no row —
     * it looks like a working control.
     */
    @Test
    fun configurable_roles_are_only_those_something_reads() {
        val configurable = ModelRole.configurable

        // EMBEDDING is a capability rather than a chat model, configured elsewhere.
        assertTrue(ModelRole.EMBEDDING !in configurable)
        // VERIFIER has no consumer — there is no verification pass to route.
        assertTrue(ModelRole.VERIFIER !in configurable)

        assertTrue(ModelRole.CONVERSATION in configurable)
        assertTrue(ModelRole.CREATIVE_DRAFT in configurable)
        assertTrue(ModelRole.CREATIVE_CRITIC in configurable)
        assertTrue(ModelRole.PLANNER in configurable)
        assertTrue(ModelRole.FAST in configurable)
        assertTrue(ModelRole.REASONING in configurable)
    }

    /**
     * Dropping the Settings row must not drop the data. The enum constant, its
     * preference key and its `AuraBackup` field all survive, so a value a user
     * already saved still round-trips and the backup schema needs no version
     * bump to remove a field.
     */
    @Test
    fun verifier_preference_plumbing_survives_the_removed_row() {
        assertTrue(ModelRole.VERIFIER in ModelRole.entries)
        assertTrue(ModelRole.VERIFIER.key == "verifier_model")
    }

    private fun assertNull(value: Any?) {
        assertTrue("Expected null but was $value", value == null)
    }
}