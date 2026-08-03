package com.aura.agent.council

import com.aura.agent.AgentStore
import com.aura.agent.forum.DebateRoundUseCase
import com.aura.agent.forum.ForumEngine
import com.aura.agent.state.AgentStateStore
import com.aura.proactive.ProactiveAwarenessEngine
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CouncilOrchestratorTest {

    private lateinit var orchestrator: CouncilOrchestrator
    private val agentStore: AgentStore = mockk(relaxed = true)
    private val stateStore: AgentStateStore = mockk(relaxed = true)
    private val forumEngine: ForumEngine = mockk(relaxed = true)
    private val debateUseCase: DebateRoundUseCase = mockk(relaxed = true)

    @Before
    fun setUp() {
        orchestrator = CouncilOrchestrator(agentStore, stateStore, forumEngine, debateUseCase)

        // Mock agent store to return agents
        coEvery { agentStore.allOnce() } returns listOf(
            com.aura.agent.AgentEntity(
                id = "agent_general", name = "general", icon = "i",
                description = "d", identity = "id", toolsAllowed = "",
                isBuiltin = true, isDefault = true,
            ),
            com.aura.agent.AgentEntity(
                id = "agent_researcher", name = "researcher", icon = "i",
                description = "d", identity = "id", toolsAllowed = "",
                isBuiltin = true,
            ),
            com.aura.agent.AgentEntity(
                id = "agent_executive", name = "executive", icon = "i",
                description = "d", identity = "id", toolsAllowed = "",
                isBuiltin = true,
            ),
        )

        // Mock state store
        coEvery { stateStore.getState(any()) } returns null
        coEvery { stateStore.getRelationshipsFor(any()) } returns emptyList()
        coEvery { stateStore.unresolvedObservations(any(), any()) } returns emptyList()
    }

    @Test
    fun runFromFindings_emptyFindings_returnsEmpty() = runBlocking {
        val results = orchestrator.runFromFindings(emptyList())
        assertTrue(results.isEmpty())
    }

    @Test
    fun runFromFindings_withFindings_runsSession() = runBlocking {
        // Mock debate to return stances
        coEvery {
            debateUseCase.execute(any(), any(), any(), any())
        } returns listOf(
            DebateRoundUseCase.DebateEntry("agent_general", "general", "The user should take a break", 0.5f),
            DebateRoundUseCase.DebateEntry("agent_researcher", "researcher", "I agree, research shows breaks help", 0.6f),
            DebateRoundUseCase.DebateEntry("agent_executive", "executive", "Agreed, schedule it", 0.7f),
        )

        // Mock forum
        coEvery { forumEngine.post(any(), any(), any(), any(), any(), any(), any()) } returns 1L
        coEvery { forumEngine.vote(any(), any(), any(), any()) } returns 1L
        coEvery { forumEngine.tally(any()) } returns ForumEngine.VoteTally(3, 0, 0)
        coEvery { forumEngine.hasQuorum(any()) } returns true
        coEvery { forumEngine.setStatus(any(), any()) } returns Unit

        val findings = listOf(
            ProactiveAwarenessEngine.ProactiveFinding(
                type = "stress",
                title = "User seems stressed",
                message = "Stress correlation detected in recent conversations",
                urgency = 0.8f,
            ),
        )

        val results = orchestrator.runFromFindings(findings, "Calendar: Meeting at 3pm")

        assertEquals(1, results.size)
        assertTrue(results[0].quorumReached)
        assertNotNull(results[0].proposal)
    }

    @Test
    fun runFromFindings_top3ByUrgency() = runBlocking {
        coEvery {
            debateUseCase.execute(any(), any(), any(), any())
        } returns listOf(
            DebateRoundUseCase.DebateEntry("agent_general", "general", "stance", 0.5f),
        )
        coEvery { forumEngine.post(any(), any(), any(), any(), any(), any(), any()) } returns 1L
        coEvery { forumEngine.vote(any(), any(), any(), any()) } returns 1L
        coEvery { forumEngine.tally(any()) } returns ForumEngine.VoteTally(1, 0, 0)
        coEvery { forumEngine.hasQuorum(any()) } returns false
        coEvery { forumEngine.setStatus(any(), any()) } returns Unit

        val findings = (1..5).map { i ->
            ProactiveAwarenessEngine.ProactiveFinding(
                type = "type_$i",
                title = "Finding $i",
                message = "Message $i",
                urgency = i * 0.1f,
            )
        }

        val results = orchestrator.runFromFindings(findings)
        // Should only process top 3 by urgency (findings 5, 4, 3)
        assertEquals(3, results.size)
    }

    @Test
    fun intervention_extractedFromStance_withBreakKeyword() = runBlocking {
        coEvery {
            debateUseCase.execute(any(), any(), any(), any())
        } returns listOf(
            DebateRoundUseCase.DebateEntry("agent_general", "general", "The user should take a break and go for a walk", 0.7f),
            DebateRoundUseCase.DebateEntry("agent_researcher", "researcher", "Agreed, breaks are essential", 0.6f),
            DebateRoundUseCase.DebateEntry("agent_executive", "executive", "Schedule it", 0.5f),
        )
        coEvery { forumEngine.post(any(), any(), any(), any(), any(), any(), any()) } returns 1L
        coEvery { forumEngine.vote(any(), any(), any(), any()) } returns 1L
        coEvery { forumEngine.tally(any()) } returns ForumEngine.VoteTally(3, 0, 0)
        coEvery { forumEngine.hasQuorum(any()) } returns true
        coEvery { forumEngine.setStatus(any(), any()) } returns Unit

        val findings = listOf(
            ProactiveAwarenessEngine.ProactiveFinding("stress", "Stress", "Stressed", 0.9f),
        )
        val results = orchestrator.runFromFindings(findings)
        val proposal = results[0].proposal
        assertNotNull(proposal)
        assertTrue(proposal is Intervention.SelfCare)
    }
}