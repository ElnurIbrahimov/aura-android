package com.aura.agent.council

import com.aura.agent.state.AgentStateEntity
import com.aura.agent.state.AgentStateStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentMoodEngineTest {

    private val stateStore: AgentStateStore = mockk(relaxed = true)
    private lateinit var moodEngine: AgentMoodEngine

    @Before
    fun setUp() {
        moodEngine = AgentMoodEngine(stateStore)
    }

    @Test
    fun `applyTimeDecay decays mood when active within 1 hour`() = runBlocking {
        val now = System.currentTimeMillis()
        val state = AgentStateEntity(
            agentId = "agent_general",
            mood = 80f,
            energy = 90f,
            lastActiveAt = now - 1800_000L, // 30 min ago = active
        )
        coEvery { stateStore.getState("agent_general") } returns state

        moodEngine.applyTimeDecay("agent_general", now)

        // Should decay: mood -= 2 * 0.5h = 1, energy -= 3 * 0.5h = 1.5
        coVerify { stateStore.setMoodEnergy("agent_general", 79f, 88.5f) }
    }

    @Test
    fun `applyTimeDecay recovers mood when idle for over 1 hour`() = runBlocking {
        val now = System.currentTimeMillis()
        val state = AgentStateEntity(
            agentId = "agent_general",
            mood = 40f,
            energy = 50f,
            lastActiveAt = now - 7200_000L, // 2h ago = idle
        )
        coEvery { stateStore.getState("agent_general") } returns state

        moodEngine.applyTimeDecay("agent_general", now)

        // Should recover: mood += 5 * 2h = 10, energy += 8 * 2h = 16
        coVerify { stateStore.setMoodEnergy("agent_general", 50f, 66f) }
    }

    @Test
    fun `applyTimeDecay clamps at 100`() = runBlocking {
        val now = System.currentTimeMillis()
        val state = AgentStateEntity(
            agentId = "agent_general",
            mood = 95f,
            energy = 95f,
            lastActiveAt = now - 7200_000L, // 2h idle → recover 10 mood, 16 energy
        )
        coEvery { stateStore.getState("agent_general") } returns state

        moodEngine.applyTimeDecay("agent_general", now)

        // 95 + 10 = 105 → clamped to 100, 95 + 16 = 111 → clamped to 100
        coVerify { stateStore.setMoodEnergy("agent_general", 100f, 100f) }
    }

    @Test
    fun `applyTimeDecay clamps at 0`() = runBlocking {
        val now = System.currentTimeMillis()
        val state = AgentStateEntity(
            agentId = "agent_general",
            mood = 5f,
            energy = 3f,
            lastActiveAt = now - 1800_000L, // 30 min active → decay 1 mood, 1.5 energy
        )
        coEvery { stateStore.getState("agent_general") } returns state

        moodEngine.applyTimeDecay("agent_general", now)

        // 5 - 1 = 4, 3 - 1.5 = 1.5
        coVerify { stateStore.setMoodEnergy("agent_general", 4f, 1.5f) }
    }

    @Test
    fun `applyTimeDecay skips when elapsed time is negligible`() = runBlocking {
        val now = System.currentTimeMillis()
        val state = AgentStateEntity(
            agentId = "agent_general",
            mood = 70f,
            energy = 80f,
            lastActiveAt = now - 10_000L, // 10s ago — skip
        )
        coEvery { stateStore.getState("agent_general") } returns state

        moodEngine.applyTimeDecay("agent_general", now)

        // Should not call setMoodEnergy
        coVerify(exactly = 0) { stateStore.setMoodEnergy(any(), any(), any()) }
    }

    @Test
    fun `canParticipate returns false when energy below threshold`() = runBlocking {
        coEvery { stateStore.getState("agent_general") } returns AgentStateEntity(
            agentId = "agent_general",
            energy = 15f, // below 20
        )
        assertFalse(moodEngine.canParticipate("agent_general"))
    }

    @Test
    fun `canParticipate returns true when energy above threshold`() = runBlocking {
        coEvery { stateStore.getState("agent_general") } returns AgentStateEntity(
            agentId = "agent_general",
            energy = 50f,
        )
        assertTrue(moodEngine.canParticipate("agent_general"))
    }

    @Test
    fun `canParticipate returns true when state is null`() = runBlocking {
        coEvery { stateStore.getState("agent_general") } returns null
        assertTrue(moodEngine.canParticipate("agent_general"))
    }

    @Test
    fun `filterAvailable excludes exhausted agents`() = runBlocking {
        coEvery { stateStore.getState("agent_general") } returns AgentStateEntity(agentId = "agent_general", energy = 50f)
        coEvery { stateStore.getState("agent_researcher") } returns AgentStateEntity(agentId = "agent_researcher", energy = 10f)
        coEvery { stateStore.getState("agent_executive") } returns AgentStateEntity(agentId = "agent_executive", energy = 30f)

        val result = moodEngine.filterAvailable(listOf("agent_general", "agent_researcher", "agent_executive"))
        assertEquals(2, result.size)
        assertTrue(result.contains("agent_general"))
        assertTrue(result.contains("agent_executive"))
        assertFalse(result.contains("agent_researcher"))
    }

    @Test
    fun `decayAll applies decay to all agents`() = runBlocking {
        val now = System.currentTimeMillis()
        coEvery { stateStore.getState("agent_general") } returns AgentStateEntity(
            agentId = "agent_general", mood = 80f, energy = 90f, lastActiveAt = now - 3600_000L,
        )
        coEvery { stateStore.getState("agent_researcher") } returns AgentStateEntity(
            agentId = "agent_researcher", mood = 70f, energy = 60f, lastActiveAt = now - 3600_000L,
        )

        moodEngine.decayAll(listOf("agent_general", "agent_researcher"), now)

        coVerify(atLeast = 2) { stateStore.setMoodEnergy(any(), any(), any()) }
    }
}