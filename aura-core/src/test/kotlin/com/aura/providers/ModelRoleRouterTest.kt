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
        // VERIFIER was excluded here on exactly this rule, with a KDoc saying it
        // could come back when something read it. ConsultGate reads it, so the
        // row is a working control again and belongs in Settings.
        assertTrue(ModelRole.VERIFIER in configurable)

        assertTrue(ModelRole.CONVERSATION in configurable)
        assertTrue(ModelRole.CREATIVE_DRAFT in configurable)
        assertTrue(ModelRole.CREATIVE_CRITIC in configurable)
        assertTrue(ModelRole.PLANNER in configurable)
        assertTrue(ModelRole.FAST in configurable)
        assertTrue(ModelRole.REASONING in configurable)
    }

    /**
     * The key outlived the row, which is why restoring the row was free.
     *
     * While VERIFIER had no consumer the constant, its preference key and its
     * `AuraBackup` field were all deliberately kept, so values saved before the
     * row was dropped kept round-tripping through the gap. Giving the role a
     * consumer therefore needed no migration and no backup schema bump — and
     * the key must keep this exact spelling for that to stay true of anyone
     * restoring an old backup.
     */
    @Test
    fun verifier_preference_key_is_stable_across_the_row_being_dropped_and_restored() {
        assertTrue(ModelRole.VERIFIER in ModelRole.entries)
        assertTrue(ModelRole.VERIFIER.key == "verifier_model")
    }

    private fun assertNull(value: Any?) {
        assertTrue("Expected null but was $value", value == null)
    }
}