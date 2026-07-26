package com.aura.taste

import com.aura.taste.TasteEngine
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentTasteTest {
    private val signalDao: PreferenceSignalDao = mockk(relaxed = true)
    private val profileDao: StyleProfileDao = mockk(relaxed = true)
    private val routingDao: RoutingOutcomeDao = mockk(relaxed = true)

    @Test
    fun `recordSignal includes agentScope`() = runTest {
        val engine = TasteEngine(signalDao, profileDao, routingDao)
        engine.recordSignal(
            signalType = "chat_reaction",
            category = "general",
            agentScope = "agent:agent_researcher",
        )
        coVerify {
            signalDao.upsert(match { it.agentScope == "agent:agent_researcher" })
        }
    }

    @Test
    fun `recordRoutingOutcome includes agentScope`() = runTest {
        val engine = TasteEngine(signalDao, profileDao, routingDao)
        engine.recordRoutingOutcome(
            modelRole = "agent:agent_writer",
            modelId = "ollama:qwen2:1.5b",
            success = true,
            agentScope = "agent:agent_writer",
        )
        coVerify {
            routingDao.upsert(match { it.agentScope == "agent:agent_writer" })
        }
    }
}
