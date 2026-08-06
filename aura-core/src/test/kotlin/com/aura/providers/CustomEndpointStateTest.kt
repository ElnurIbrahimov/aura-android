package com.aura.providers

import com.aura.security.SecureDataStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class CustomEndpointStateTest {

    private fun newState(): CustomEndpointState {
        val store = mockk<SecureDataStore>(relaxed = true)
        coEvery { store.getString(any()) } returns null
        return CustomEndpointState(store)
    }

    @Test
    fun `fresh state is unconfigured`() {
        val state = newState()
        assertFalse(state.isConfigured())
    }

    @Test
    fun `setEndpoint makes state configured`() {
        val state = newState()
        state.setEndpoint("https://api.example.com/v1", "sk-test-123")
        assertTrue(state.isConfigured())
        assertEquals("https://api.example.com/v1", state.baseUrl)
        assertEquals("sk-test-123", state.apiKey)
    }

    @Test
    fun `setEndpoint trims trailing slash from baseUrl`() {
        val state = newState()
        state.setEndpoint("https://api.example.com/v1/", "sk-test-123")
        assertEquals("https://api.example.com/v1", state.baseUrl)
    }

    @Test
    fun `setEndpoint overrides models`() {
        val state = newState()
        state.setEndpoint(
            baseUrl = "https://api.example.com/v1",
            apiKey = "sk-test-123",
            modelOverride = listOf("custom-1", "custom-2"),
        )
        assertEquals(listOf("custom-1", "custom-2"), state.modelOverride)
    }

    @Test
    fun `snapshot is atomic`() {
        val state = newState()
        state.setEndpoint("https://api.example.com/v1", "sk-1")
        val (url, key, models) = state.snapshot()
        assertEquals("https://api.example.com/v1", url)
        assertEquals("sk-1", key)
        assertTrue(models.isEmpty())
    }

    @Test
    fun `setEndpoint with blank key leaves state unconfigured`() {
        val state = newState()
        state.setEndpoint("https://api.example.com/v1", "  ")
        assertFalse(state.isConfigured())
    }

    @Test
    @kotlinx.coroutines.ExperimentalCoroutinesApi
    fun `setEndpoint persists to secure data store`() = runTest {
        val store = mockk<SecureDataStore>(relaxed = true)
        coEvery { store.getString(any()) } returns null
        // Inject a TestDispatcher tied to this test's scheduler so the
        // persistence coroutine launched by setEndpoint() is driven
        // deterministically by advanceUntilIdle() — no Thread.sleep race.
        val state = CustomEndpointState(store, StandardTestDispatcher(testScheduler), Unit)
        state.setEndpoint("https://api.example.com/v1", "sk-test")
        advanceUntilIdle()
        coVerify {
            store.putString(CustomEndpointState.KEY_BASE_URL, "https://api.example.com/v1")
        }
        coVerify {
            store.putString(CustomEndpointState.KEY_API_KEY, "sk-test")
        }
    }

    @Test
    fun `reload reads persisted state on init`() = runTest(timeout = 10.seconds) {
        val store = mockk<SecureDataStore>()
        coEvery { store.getString(CustomEndpointState.KEY_BASE_URL) } returns "https://api.example.com/v1"
        coEvery { store.getString(CustomEndpointState.KEY_API_KEY) } returns "sk-restored"
        coEvery { store.getString(CustomEndpointState.KEY_MODEL_OVERRIDE) } returns null
        val state = CustomEndpointState(store)
        state.reload()
        assertEquals("https://api.example.com/v1", state.baseUrl)
        assertEquals("sk-restored", state.apiKey)
    }
}