package com.aura.agent

import com.aura.agent.policy.ConfirmationLevel
import com.aura.agent.policy.PolicyEngine
import com.aura.agent.policy.ToolPolicy
import com.aura.agent.policy.ToolPolicyStore
import com.aura.memory.MemoryStore
import com.aura.providers.FinishReason
import com.aura.providers.Provider
import com.aura.providers.ProviderRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertIs

/**
 * Mirror of [MemoryAugmentedAgenticLoopPermissionTest] for the
 * CONFIRMATION gate. A tool whose [ToolPolicy] sets
 * `confirmation=EXPLICIT` is blocked by [PolicyEngine] until the user
 * confirms: [com.aura.agent.ToolExecutor] returns
 * [ToolResult.NeedsConfirmation], the loop pauses with
 * `GateRequested(kind=CONFIRMATION)`, and `resumeAfterGate` replays the
 * held tool with the tool name merged into `ToolContext.confirmedTools`
 * so the policy check passes.
 *
 * Contracts pinned here:
 * 1. The loop pauses and emits `GateRequested(kind=CONFIRMATION)`;
 *    the tool body never ran (the policy gate fires before execution).
 * 2. `resumeAfterGate` executes the tool exactly once and the result
 *    lands via setToolResult in the continued conversation.
 * 3. `denyPendingGate` clears the gate; a subsequent resume emits
 *    no_pending and never executes the tool.
 *
 * Uses `runBlocking` (real time) for the same reason as the permission
 * test: tool execution crosses `runInterruptible(Dispatchers.IO)`,
 * which virtual-time schedulers do not wait for.
 */
class MemoryAugmentedAgenticLoopConfirmationTest {

    private fun passthroughCompactor(): ConversationCompactor =
        mockk<ConversationCompactor>().also { compactor ->
            coEvery { compactor.compactIfNeeded(any(), any()) } answers { firstArg() }
        }

    private fun mockProviderRegistry(): ProviderRegistry {
        val provider = mockk<Provider>(relaxed = true)
        every { provider.prefix } returns "test"
        every { provider.isConfigured() } returns true
        val registry = mockk<ProviderRegistry>(relaxed = true)
        coEvery { registry.parse(any<String>()) } returns (provider to "test-model")
        return registry
    }

    /**
     * Full harness: real ToolRegistry + real ToolExecutor wired with a
     * real PolicyEngine whose ToolPolicyStore returns
     * confirmation=EXPLICIT for the test tool, and a scripted Brain.
     * Returns the loop plus the tool's execution counter.
     */
    private fun buildHarness(brain: Brain): Pair<MemoryAugmentedAgenticLoop, IntArray> {
        val calls = intArrayOf(0)
        val toolRegistry = ToolRegistry()
        toolRegistry.register(
            Tool(
                name = "confirm_test_tool",
                description = "Test tool gated by an EXPLICIT confirmation policy",
                risk = ToolRisk.READ_ONLY,
                parameters = com.aura.providers.ToolParameters(),
                execute = { _, _ ->
                    calls[0] += 1
                    ToolResult.Ok("Weather: sunny, 25C")
                },
            ),
        )

        val policyStore = mockk<ToolPolicyStore>()
        coEvery { policyStore.getPolicy("confirm_test_tool") } returns ToolPolicy(
            toolName = "confirm_test_tool",
            confirmation = ConfirmationLevel.EXPLICIT,
        )
        val executor = ToolExecutor(
            toolRegistry,
            context = mockk(relaxed = true),
            policyEngine = PolicyEngine(policyStore),
        )

        val memoryStore = mockk<MemoryStore>(relaxed = true)
        val kgExtractor = mockk<com.aura.kg.ConversationKgExtractor>(relaxed = true)
        val userProfileStore = mockk<com.aura.profile.UserProfileStore>(relaxed = true)
        val handRepository = mockk<com.aura.hands.HandRepository>(relaxed = true)
        every { userProfileStore.getSystemPrompt() } returns ""
        coEvery { handRepository.getEnabled() } returns emptyList()

        val loop = MemoryAugmentedAgenticLoop(
            brain, toolRegistry, executor, memoryStore, kgExtractor,
            userProfileStore, handRepository, mockProviderRegistry(), passthroughCompactor(),
        )
        return loop to calls
    }

    /** Brain scripted to call the tool once, then answer with final text. */
    private fun scriptedBrain(): Brain {
        val brain = mockk<Brain>(relaxed = true)
        coEvery { brain.stream(any(), any(), any(), any()) } returnsMany listOf(
            flowOf(
                BrainChunk.ToolCallStart("tc1", "confirm_test_tool"),
                BrainChunk.ToolCallDelta("tc1", "{}"),
                BrainChunk.ToolCallEnd("tc1", "confirm_test_tool", "{}"),
                BrainChunk.Finished(FinishReason.tool_calls.name),
            ),
            flowOf(
                BrainChunk.Text("It is sunny and 25C today."),
                BrainChunk.Finished(FinishReason.stop.name),
            ),
        )
        return brain
    }

    @Test
    fun `confirmation-gated tool - loop pauses with GateRequested(kind=CONFIRMATION)`() = runBlocking {
        val (loop, calls) = buildHarness(scriptedBrain())

        // Short message (< 20 chars) so the planning step is skipped.
        val conv = Conversation().addUser("weather now")
        val events = mutableListOf<AgentEvent>()
        loop.run(conv, model = "test:model", maxSteps = 5).collect { events += it }

        // The loop paused with a CONFIRMATION gate.
        val gate = events.filterIsInstance<AgentEvent.GateRequested>().firstOrNull()
        assertNotNull(gate)
        assertEquals("confirm_test_tool", gate!!.toolName)
        assertEquals(MemoryAugmentedAgenticLoop.GateKind.CONFIRMATION, gate.kind)
        assertEquals(ConfirmationLevel.EXPLICIT.name, gate.level)
        assertEquals("tc1", gate.toolCallId)

        // The held snapshot mirrors the event.
        val held = loop.peekPendingGate(conv.id)
        assertNotNull(held)
        assertEquals(MemoryAugmentedAgenticLoop.GateKind.CONFIRMATION, held!!.kind)
        assertEquals(ConfirmationLevel.EXPLICIT.name, held.confirmationLevel)
        assertEquals("confirm_test_tool", held.toolName)

        // The policy gate fired BEFORE the tool body — it never executed.
        assertEquals(0, calls[0])
        assertTrue("run() should end with Done", events.last() is AgentEvent.Done)
    }

    @Test
    fun `resumeAfterGate executes the tool exactly once and lands the result`() = runBlocking {
        val (loop, calls) = buildHarness(scriptedBrain())

        // 1. Run — pauses on the confirmation gate.
        val conv = Conversation().addUser("weather now")
        loop.run(conv, model = "test:model", maxSteps = 5).collect { /* drain */ }
        assertNotNull("expected pause", loop.peekPendingGate(conv.id))
        assertEquals(0, calls[0])

        // 2. Resume — the confirmed set now carries the tool, so the
        //    policy check passes and the held tool runs.
        val resumeEvents = mutableListOf<AgentEvent>()
        loop.resumeAfterGate(conv.id).collect { resumeEvents += it }

        // The held tool executed exactly once.
        assertEquals(1, calls[0])
        val toolResult = resumeEvents.filterIsInstance<AgentEvent.ToolResult>()
            .firstOrNull { it.name == "confirm_test_tool" }
        assertNotNull(toolResult)
        assertEquals("tc1", toolResult!!.id)
        assertEquals("Weather: sunny, 25C", toolResult.result)

        // The result landed via setToolResult in the continued
        // conversation: the final Result snapshot carries the tool turn
        // with the actual output (not a dangling call).
        val result = resumeEvents.filterIsInstance<AgentEvent.Result>().lastOrNull()
        assertNotNull(result)
        val toolTurn = result!!.conversation.turns
            .flatMap { it.toolTurns }
            .firstOrNull { it.id == "tc1" }
        assertNotNull("tool turn missing from continued conversation", toolTurn)
        assertEquals("Weather: sunny, 25C", toolTurn!!.result)

        // The continued run produced the final assistant text.
        val textDeltas = resumeEvents.filterIsInstance<AgentEvent.TextDelta>().map { it.text }
        assertTrue(
            "expected continuation to produce assistant text, got: $textDeltas",
            textDeltas.any { it.contains("sunny") },
        )

        // The gate is cleared after resume.
        assertNull(loop.peekPendingGate(conv.id))
    }

    @Test
    fun `denyPendingGate clears the gate and a subsequent resume emits no_pending`() = runBlocking {
        val (loop, calls) = buildHarness(scriptedBrain())

        val conv = Conversation().addUser("weather now")
        loop.run(conv, model = "test:model", maxSteps = 5).collect { /* drain */ }
        assertNotNull("expected pause", loop.peekPendingGate(conv.id))

        // Deny clears the gate without executing the tool.
        loop.denyPendingGate(conv.id)
        assertNull("deny should clear the pending gate", loop.peekPendingGate(conv.id))
        assertEquals(0, calls[0])

        // A subsequent resume finds nothing and emits no_pending.
        val resumeEvents = mutableListOf<AgentEvent>()
        loop.resumeAfterGate(conv.id).collect { resumeEvents += it }
        val err = resumeEvents.filterIsInstance<AgentEvent.Error>().firstOrNull()
        assertIs<AgentEvent.Error>(err)
        assertEquals("no_pending", err.code)
        assertTrue("expected Done to terminate the flow", resumeEvents.last() is AgentEvent.Done)
        assertEquals(0, calls[0]) // the tool never ran
    }
}
