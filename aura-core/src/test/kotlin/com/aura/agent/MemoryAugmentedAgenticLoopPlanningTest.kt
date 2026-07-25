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
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the `planningEnabled` contract on [MemoryAugmentedAgenticLoop.run].
 *
 * The planning step makes a *separate* LLM call before step 1 and injects
 * the result as a system prefix. It used to be unconditional, which meant
 * every user message over ~20 chars paid an extra round-trip (up to 15s)
 * and a second billed request before the first token of the real answer.
 * It is now opt-in, defaulting to off.
 *
 * Two things need pinning:
 * 1. **Off by default** — the first `brain.stream()` call is step 1, not a
 *    plan. Regressing this silently doubles per-turn cost and latency.
 * 2. **On when requested** — the feature still works, and the plan text
 *    reaches the step-1 system prompt.
 *
 * Uses `runBlocking` rather than `runTest` for the same reason as
 * [MemoryAugmentedAgenticLoopPermissionTest]: the loop crosses
 * `runInterruptible(Dispatchers.IO)`, which virtual time does not wait for.
 */
class MemoryAugmentedAgenticLoopPlanningTest {

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

    private fun loopWith(brain: Brain): MemoryAugmentedAgenticLoop {
        val toolRegistry = ToolRegistry()
        val executor = ToolExecutor(toolRegistry, context = mockk(relaxed = true))
        val memoryStore = mockk<MemoryStore>(relaxed = true)
        val userProfileStore = mockk<com.aura.profile.UserProfileStore>(relaxed = true)
        every { userProfileStore.getSystemPrompt() } returns ""
        val handRepository = mockk<com.aura.hands.HandRepository>(relaxed = true)
        coEvery { handRepository.getEnabled() } returns emptyList()
        return MemoryAugmentedAgenticLoop(
            brain,
            toolRegistry,
            executor,
            memoryStore,
            mockk<com.aura.kg.ConversationKgExtractor>(relaxed = true),
            userProfileStore,
            handRepository,
            mockProviderRegistry(),
            passthroughCompactor(),
        )
    }

    /** A message long enough to clear the >20 char / >3 word planning threshold. */
    private val longMessage = "what do you remember about my model preferences and settings?"

    @Test
    fun `planning is off by default so the first stream call is step 1`() = runBlocking {
        val brain = mockk<Brain>(relaxed = true)
        val prompts = mutableListOf<List<com.aura.providers.ProviderMessage>>()
        coEvery { brain.stream(any(), any(), any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            prompts += secondArg<List<com.aura.providers.ProviderMessage>>()
            flowOf(
                BrainChunk.Text("Answer without planning."),
                BrainChunk.Finished(FinishReason.stop.name),
            )
        }

        val conv = Conversation().addUser(longMessage)
        val events = mutableListOf<AgentEvent>()
        loopWith(brain).run(conv, model = "test:model", maxSteps = 5).collect { events += it }

        // Exactly one model call: no plan round-trip was made.
        assertEquals(1, prompts.size, "planning must not fire when planningEnabled is false")

        val systemPrompt = prompts.single().firstOrNull { it.role == com.aura.providers.ProviderMessage.Role.system }?.content.orEmpty()
        assertTrue(
            !systemPrompt.contains("## Plan:"),
            "system prompt must carry no plan prefix when planning is off, got: $systemPrompt",
        )
    }

    @Test
    fun `planning fires and reaches the step 1 system prompt when enabled`() = runBlocking {
        val brain = mockk<Brain>(relaxed = true)
        val prompts = mutableListOf<List<com.aura.providers.ProviderMessage>>()
        var call = 0
        coEvery { brain.stream(any(), any(), any(), any()) } answers {
            prompts += secondArg<List<com.aura.providers.ProviderMessage>>()
            call += 1
            if (call == 1) {
                // The planning call.
                flowOf(
                    BrainChunk.Text("I will check memory for preferences."),
                    BrainChunk.Finished(FinishReason.stop.name),
                )
            } else {
                flowOf(
                    BrainChunk.Text("You prefer dark mode."),
                    BrainChunk.Finished(FinishReason.stop.name),
                )
            }
        }

        val conv = Conversation().addUser(longMessage)
        val events = mutableListOf<AgentEvent>()
        loopWith(brain)
            .run(conv, model = "test:model", maxSteps = 5, planningEnabled = true)
            .collect { events += it }

        assertTrue(prompts.size >= 2, "planning should add a model call, saw ${prompts.size}")

        // The step-1 prompt (the call after the plan) must carry the plan text.
        val stepOneSystem = prompts[1]
            .firstOrNull { it.role == com.aura.providers.ProviderMessage.Role.system }
            ?.content.orEmpty()
        assertTrue(
            stepOneSystem.contains("## Plan:") && stepOneSystem.contains("check memory for preferences"),
            "step 1 system prompt should carry the plan prefix, got: $stepOneSystem",
        )
    }

    @Test
    fun `short messages skip planning even when enabled`() = runBlocking {
        val brain = mockk<Brain>(relaxed = true)
        var calls = 0
        coEvery { brain.stream(any(), any(), any(), any()) } answers {
            calls += 1
            flowOf(BrainChunk.Text("hi"), BrainChunk.Finished(FinishReason.stop.name))
        }

        // "thanks" is under the >20 char / >3 word threshold.
        val conv = Conversation().addUser("thanks")
        loopWith(brain)
            .run(conv, model = "test:model", maxSteps = 5, planningEnabled = true)
            .collect { }

        assertEquals(1, calls, "short messages must not pay for a planning round-trip")
    }
}
