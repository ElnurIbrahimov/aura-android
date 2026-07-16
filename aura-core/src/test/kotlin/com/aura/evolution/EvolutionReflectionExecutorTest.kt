package com.aura.evolution

import com.aura.data.UserPreferences
import com.aura.providers.ModelRole
import com.aura.providers.ModelRoleRouter
import com.aura.providers.ProviderRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvolutionReflectionExecutorTest {
    @Test
    fun `role router falls back to background when evolution model unset`() = runBlocking {
        val prefs = mockk<UserPreferences>()
        val registry = mockk<ProviderRegistry>()
        every { prefs.forRole(ModelRole.EVOLUTION) } returns flowOf(null)
        every { prefs.defaultModel } returns flowOf("default")
        coEvery { registry.configured() } returns emptyList()

        val router = ModelRoleRouter(prefs, registry)
        assertEquals("default", router.resolve(ModelRole.EVOLUTION))
    }

    @Test
    fun `role router returns evolution model when set`() = runBlocking {
        val prefs = mockk<UserPreferences>()
        val registry = mockk<ProviderRegistry>()
        every { prefs.forRole(ModelRole.EVOLUTION) } returns flowOf("evol-model")
        every { prefs.defaultModel } returns flowOf(null)
        coEvery { registry.configured() } returns emptyList()

        val router = ModelRoleRouter(prefs, registry)
        assertEquals("evol-model", router.resolve(ModelRole.EVOLUTION))
    }
}
