package com.aura.agent

import com.aura.memory.MemoryStore
import com.aura.providers.FinishReason
import com.aura.providers.Provider
import com.aura.providers.ProviderRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertIs

/**
 * Regression test for the P0 finding in
 * `.hermes/audits/AGENTIC_LOOP_AUDIT.md` A1: the
 * `AgentEvent.PermissionGranted` event was dead code. When a tool
 * returned `ToolResult.NeedsPermission`, the loop appended a
 * "Permission needed: X" string to the conversation and continued,
 * leaving the model with no real tool result to act on. The held tool
 * never re-ran even after the user granted the permission.
 *
 * The fix: the loop now pauses on `NeedsPermission`, stashes a
 * `PendingPermission` snapshot, and emits a new `PermissionRequested`
 * event. The UI grants → calls `resumeAfterPermission()` → the held
 * tool re-executes and the agentic loop continues from `step + 1`.
 *
 * These tests pin all four contracts:
 * 1. The loop emits `PermissionRequested` and exits on NeedsPermission
 * 2. `resumeAfterPermission` re-executes the held tool and continues
 * 3. `resumeAfterPermission` with nothing pending emits no_pending
 * 4. `denyPendingPermission` clears the field without resuming
 *
 * We use `runBlocking` (real time) rather than `runTest` (virtual time)
 * because the loop's tool execution crosses `runInterruptible(Dispatchers.IO)`
 * which is a real-thread primitive. The test scheduler does not wait
 * for real IO threads, so virtual-time tests hang. `runBlocking` waits
 * for the IO thread to complete, matching what the EndToEndTest does.
 */
class MemoryAugmentedAgenticLoopPermissionTest {

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
     * A tool that always returns NeedsPermission the first time it's
     * called, then Ok the second time. This is the canonical pattern for
     * permission-gated tools: the first call says "I need X", the user
     * grants X, the second call succeeds.
     *
     * `Tool` is a data class with `execute` as a lambda property, so we
     * construct the tool with a counter-closing lambda instead of
     * subclassing.
     */
    private fun permissionThenOkTool(
        permission: String,
        rationale: String,
        successOutput: String,
    ): Pair<com.aura.agent.Tool, IntArray> {
        val calls = intArrayOf(0)
        val tool = com.aura.agent.Tool(
            name = "permission_test_tool",
            description = "Test tool that needs a runtime permission",
            risk = com.aura.agent.ToolRisk.READ_ONLY,
            parameters = com.aura.providers.ToolParameters(),
            execute = { _, _ ->
                calls[0] += 1
                if (calls[0] == 1) {
                    com.aura.agent.ToolResult.NeedsPermission(permission, rationale)
                } else {
                    com.aura.agent.ToolResult.Ok(successOutput)
                }
            },
        )
        return tool to calls
    }

    @After
    fun clearPending() {
        // No global state, but the loop is @Singleton in production. Tests
        // use a fresh instance per @Test so this is a no-op safety net.
    }

    @Test
    fun `tool returns NeedsPermission - loop pauses, emits PermissionRequested, holds state`() = runBlocking {
        val (permissionTool, calls) = permissionThenOkTool(
            permission = "android.permission.READ_CALENDAR",
            rationale = "Calendar access is needed to read events.",
            successOutput = "Events: meeting at 3pm",
        )
        val toolRegistry = ToolRegistry()
        toolRegistry.register(permissionTool)

        val brain = mockk<Brain>(relaxed = true)
        // Model emits exactly one tool call and then a final stop.
        coEvery { brain.stream(any(), any(), any(), any()) } returnsMany listOf(
            flowOf(
                BrainChunk.ToolCallStart("tc1", "permission_test_tool"),
                BrainChunk.ToolCallDelta("tc1", "{}"),
                BrainChunk.ToolCallEnd("tc1", "permission_test_tool", "{}"),
                BrainChunk.Finished(FinishReason.tool_calls.name),
            ),
            // Second call (post-resume) would happen here. We don't reach
            // it in this test — we only assert the pause behavior.
            flowOf(
                BrainChunk.Text("I will need calendar access."),
                BrainChunk.Finished(FinishReason.stop.name),
            ),
        )

        val memoryStore = mockk<MemoryStore>(relaxed = true)
        val executor = ToolExecutor(toolRegistry, context = mockk(relaxed = true))
        val kgExtractor = mockk<com.aura.kg.ConversationKgExtractor>(relaxed = true)
        val userProfileStore = mockk<com.aura.profile.UserProfileStore>(relaxed = true)
        val handRepository = mockk<com.aura.hands.HandRepository>(relaxed = true)
        every { userProfileStore.getSystemPrompt() } returns ""
        coEvery { handRepository.getEnabled() } returns emptyList()

        val loop = MemoryAugmentedAgenticLoop(
            brain, toolRegistry, executor, memoryStore, kgExtractor,
            userProfileStore, handRepository, mockProviderRegistry(), passthroughCompactor(),
        )

        // 1. Run the conversation. The loop should pause on the first
        //    NeedsPermission and emit PermissionRequested, then Done.
        // Use a short message (< 20 chars, ≤ 3 words) so the loop's
        // planning step on long messages is skipped. Planning would
        // consume the first brain.stream() call before the tool call,
        // changing the order of mock responses.
        val conv = Conversation().addUser("calendar today")
        val events = mutableListOf<AgentEvent>()
        loop.run(conv, model = "test:model", maxSteps = 5).collect { events += it }

        // 2. Assert: the loop paused. No second brain.stream call.
        val permissionRequested = events.filterIsInstance<AgentEvent.PermissionRequested>().firstOrNull()
        assertNotNull(permissionRequested)
        assertEquals("permission_test_tool", permissionRequested!!.toolName)
        assertEquals("android.permission.READ_CALENDAR", permissionRequested.permission)
        assertEquals("Calendar access is needed to read events.", permissionRequested.rationale)

        // 3. The held request is stashed on the loop for resume.
        val held = loop.peekPendingPermission()
        assertNotNull(held)
        assertEquals("permission_test_tool", held!!.toolName)
        assertEquals("android.permission.READ_CALENDAR", held.permission)
        assertEquals("tc1", held.toolCallId)

        // 4. The loop ended with a Result + Done. No more model steps
        //    happened (the held tool was not re-attempted).
        assertTrue(
            "run() should end with Done",
            events.last() is AgentEvent.Done,
        )
        // The model was only called once — the second brain.stream
        // response ("I will need calendar access") was never consumed
        // because the loop paused on step 1.
        // coEvery's returnsMany is consumed lazily; we just confirm
        // the tool was hit exactly once.
        assertEquals(1, calls[0])
    }

    @Test
    fun `resumeAfterPermission re-executes held tool and continues the run`() = runBlocking {
        val (permissionTool, calls) = permissionThenOkTool(
            permission = "android.permission.READ_CALENDAR",
            rationale = "Calendar access is needed to read events.",
            successOutput = "Events: meeting at 3pm",
        )
        val toolRegistry = ToolRegistry()
        toolRegistry.register(permissionTool)

        val brain = mockk<Brain>(relaxed = true)
        // First call: tool call → permission requested (loop pauses).
        // Second call: tool result injected, model emits final text.
        coEvery { brain.stream(any(), any(), any(), any()) } returnsMany listOf(
            flowOf(
                BrainChunk.ToolCallStart("tc1", "permission_test_tool"),
                BrainChunk.ToolCallDelta("tc1", "{}"),
                BrainChunk.ToolCallEnd("tc1", "permission_test_tool", "{}"),
                BrainChunk.Finished(FinishReason.tool_calls.name),
            ),
            flowOf(
                BrainChunk.Text("You have a meeting at 3pm today."),
                BrainChunk.Finished(FinishReason.stop.name),
            ),
        )

        val memoryStore = mockk<MemoryStore>(relaxed = true)
        val executor = ToolExecutor(toolRegistry, context = mockk(relaxed = true))
        val kgExtractor = mockk<com.aura.kg.ConversationKgExtractor>(relaxed = true)
        val userProfileStore = mockk<com.aura.profile.UserProfileStore>(relaxed = true)
        val handRepository = mockk<com.aura.hands.HandRepository>(relaxed = true)
        every { userProfileStore.getSystemPrompt() } returns ""
        coEvery { handRepository.getEnabled() } returns emptyList()

        val loop = MemoryAugmentedAgenticLoop(
            brain, toolRegistry, executor, memoryStore, kgExtractor,
            userProfileStore, handRepository, mockProviderRegistry(), passthroughCompactor(),
        )

        // 1. Run — pauses on NeedsPermission.
        val conv = Conversation().addUser("calendar today")
        val runEvents = mutableListOf<AgentEvent>()
        loop.run(conv, model = "test:model", maxSteps = 5).collect { runEvents += it }
        assertNotNull("expected pause", loop.peekPendingPermission())

        // 2. Resume. The held tool re-runs (second call → Ok), the
        //    loop continues, and the second brain.stream() call returns
        //    the final assistant text.
        val resumeEvents = mutableListOf<AgentEvent>()
        loop.resumeAfterPermission().collect { resumeEvents += it }

        // 3. The resume flow emitted: ToolExecuting → ToolResult → ...
        val toolExec = resumeEvents.filterIsInstance<AgentEvent.ToolExecuting>().firstOrNull()
        assertNotNull(toolExec)
        assertEquals("permission_test_tool", toolExec!!.name)
        assertEquals("tc1", toolExec.id)

        val toolResult = resumeEvents.filterIsInstance<AgentEvent.ToolResult>().firstOrNull { it.name == "permission_test_tool" }
        assertNotNull(toolResult)
        assertEquals("Events: meeting at 3pm", toolResult!!.result)

        // 4. The continued run emitted the final assistant text.
        val textDeltas = resumeEvents.filterIsInstance<AgentEvent.TextDelta>().map { it.text }
        assertTrue(
            "expected continuation to produce assistant text, got: $textDeltas",
            textDeltas.any { it.contains("3pm") },
        )

        // 5. The held tool ran twice (once in run, once in resume).
        assertEquals(2, calls[0])

        // 6. pendingPermission is cleared.
        assertNull("pendingPermission should be cleared after resume", loop.peekPendingPermission())
    }

    @Test
    fun `resumeAfterPermission with no pending emits no_pending error`() = runBlocking {
        val toolRegistry = ToolRegistry()
        val brain = mockk<Brain>(relaxed = true)
        val memoryStore = mockk<MemoryStore>(relaxed = true)
        val executor = ToolExecutor(toolRegistry, context = mockk(relaxed = true))
        val kgExtractor = mockk<com.aura.kg.ConversationKgExtractor>(relaxed = true)
        val userProfileStore = mockk<com.aura.profile.UserProfileStore>(relaxed = true)
        val handRepository = mockk<com.aura.hands.HandRepository>(relaxed = true)
        every { userProfileStore.getSystemPrompt() } returns ""
        coEvery { handRepository.getEnabled() } returns emptyList()

        val loop = MemoryAugmentedAgenticLoop(
            brain, toolRegistry, executor, memoryStore, kgExtractor,
            userProfileStore, handRepository, mockProviderRegistry(), passthroughCompactor(),
        )

        val events = mutableListOf<AgentEvent>()
        loop.resumeAfterPermission().collect { events += it }

        val err = events.filterIsInstance<AgentEvent.Error>().firstOrNull()
        assertNotNull(err)
        assertEquals("no_pending", err!!.code)
        assertEquals(false, err.retryable)
        assertTrue("expected Done to terminate the flow", events.last() is AgentEvent.Done)
    }

    @Test
    fun `denyPendingPermission clears the held request`() = runBlocking {
        val (permissionTool, calls) = permissionThenOkTool(
            permission = "android.permission.READ_CALENDAR",
            rationale = "Calendar access is needed to read events.",
            successOutput = "Events: meeting at 3pm",
        )
        val toolRegistry = ToolRegistry()
        toolRegistry.register(permissionTool)

        val brain = mockk<Brain>(relaxed = true)
        coEvery { brain.stream(any(), any(), any(), any()) } returns flowOf(
            BrainChunk.ToolCallStart("tc1", "permission_test_tool"),
            BrainChunk.ToolCallDelta("tc1", "{}"),
            BrainChunk.ToolCallEnd("tc1", "permission_test_tool", "{}"),
            BrainChunk.Finished(FinishReason.tool_calls.name),
        )

        val memoryStore = mockk<MemoryStore>(relaxed = true)
        val executor = ToolExecutor(toolRegistry, context = mockk(relaxed = true))
        val kgExtractor = mockk<com.aura.kg.ConversationKgExtractor>(relaxed = true)
        val userProfileStore = mockk<com.aura.profile.UserProfileStore>(relaxed = true)
        val handRepository = mockk<com.aura.hands.HandRepository>(relaxed = true)
        every { userProfileStore.getSystemPrompt() } returns ""
        coEvery { handRepository.getEnabled() } returns emptyList()

        val loop = MemoryAugmentedAgenticLoop(
            brain, toolRegistry, executor, memoryStore, kgExtractor,
            userProfileStore, handRepository, mockProviderRegistry(), passthroughCompactor(),
        )

        val conv = Conversation().addUser("calendar today")
        loop.run(conv, model = "test:model", maxSteps = 5).collect { /* drain */ }
        assertNotNull("expected pause", loop.peekPendingPermission())

        // Deny. The held request is cleared; the tool ran exactly once.
        loop.denyPendingPermission()
        assertNull("deny should clear pendingPermission", loop.peekPendingPermission())
        assertEquals(1, calls[0])

        // Resuming after deny yields no_pending — the tool does NOT
        // re-execute. The user explicitly chose not to grant.
        val resumeEvents = mutableListOf<AgentEvent>()
        loop.resumeAfterPermission().collect { resumeEvents += it }
        val err = resumeEvents.filterIsInstance<AgentEvent.Error>().firstOrNull()
        assertIs<AgentEvent.Error>(err)
        assertEquals("no_pending", err.code)
        assertEquals(1, calls[0]) // no re-execution
    }
}