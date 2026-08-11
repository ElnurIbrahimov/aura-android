package com.aura.tools

import android.content.Context
import com.aura.agent.ToolContext
import com.aura.agentrun.AgentRunContextSnapshot
import com.aura.agentrun.AgentRunExecutorService
import com.aura.agentrun.AgentRunStore
import com.aura.hands.Hand
import com.aura.hands.HandRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class HandRunEnqueuerTest {

    /**
     * `mockkObject` rewrites the singleton in place for the whole JVM, not for
     * the enclosing test. Without this teardown, `AgentRunExecutorService.enqueue`
     * stayed a no-op stub for every test that happened to run after this one in
     * the same Gradle worker — so any later test asserting that a run gets
     * enqueued would pass against a stub it never asked for, and the order it
     * passed in would depend on class ordering rather than on the code.
     */
    @After
    fun tearDown() {
        unmockkObject(AgentRunExecutorService)
    }

    @Test
    fun `enqueue serializes ToolContext into run metadata`() = runTest {
        mockkObject(AgentRunExecutorService)
        every { AgentRunExecutorService.enqueue(any(), any()) } returns Unit

        val appContext = mockk<Context>(relaxed = true)
        val handRepository = mockk<HandRepository>(relaxed = true)
        val agentRunStore = mockk<AgentRunStore>(relaxed = true)
        val enqueuer = HandRunEnqueuer(appContext, handRepository, agentRunStore)

        val hand = Hand(
            id = "h1",
            name = "MorningBrief",
            steps = """[{"tool":"web_search","args":{"query":"weather"}}]""",
            enabled = true,
        )
        coEvery { handRepository.getByName("MorningBrief") } returns hand
        every { handRepository.parseVariables("{}") } returns emptyMap()
        every { handRepository.decodeConditions(hand.conditions) } returns emptyList()
        every { handRepository.parseSteps(hand.steps) } returns emptyList()
        coEvery { handRepository.recordRun(any(), any(), any()) } returns Unit

        val createdRun = com.aura.agentrun.AgentRunEntity(
            id = "run-1",
            goalId = "goal-1",
            triggerType = "AGENT",
        )
        val metadataSlot = slot<String>()
        coEvery { agentRunStore.createRun(any(), any(), any(), any(), capture(metadataSlot)) } returns createdRun

        val context = ToolContext(
            conversationId = "conv-1",
            userMessage = "run the morning brief",
            approvedRemoteCostTools = setOf("web_search"),
            memoryEnabled = false,
            activeAgentId = "agent_1",
        )

        val runId = enqueuer.enqueue(
            handName = "MorningBrief",
            trigger = "AGENT",
            context = context,
        )

        assertNotNull(runId)
        assertEquals("run-1", runId)
        val snapshot = AgentRunContextSnapshot.fromJson(metadataSlot.captured)
        assertEquals("run the morning brief", snapshot.userMessage)
        assertEquals(setOf("web_search"), snapshot.approvedRemoteCostTools)
        assertEquals(false, snapshot.memoryEnabled)
        assertEquals("agent_1", snapshot.activeAgentId)
    }
}
