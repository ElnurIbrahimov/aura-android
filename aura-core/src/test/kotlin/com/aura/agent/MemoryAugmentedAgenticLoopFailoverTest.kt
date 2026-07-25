package com.aura.agent

import com.aura.memory.MemoryStore
import com.aura.providers.FinishReason
import com.aura.providers.ModelCatalog
import com.aura.providers.ModelCatalogRepository
import com.aura.providers.ModelDescriptor
import com.aura.providers.Provider
import com.aura.providers.ProviderRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the step accounting around provider failover.
 *
 * The 2026-07-18 review recorded: "The loop increments `step` before the
 * failover inner loop. A 2-model failover consumes 2 of 10 steps for 0
 * useful output." That claim does not hold against the current structure —
 * `step += 1` sits at the top of the outer `while (!finished && step <
 * maxSteps)` loop, while failover is an inner `stream@ while (true)` that
 * retries with `continue@stream`. A failover therefore re-runs the model
 * call *within the same step*.
 *
 * These tests exist because that is a structural property nothing was
 * checking: moving the failover retry out to the outer loop, or hoisting
 * the increment, would silently start charging users a step per failed
 * provider. With maxSteps at its default of 10 and a multi-provider setup,
 * that is the difference between a run that finishes and one that stops
 * early with no answer.
 */
class MemoryAugmentedAgenticLoopFailoverTest {

    private fun passthroughCompactor(): ConversationCompactor =
        mockk<ConversationCompactor>().also { compactor ->
            coEvery { compactor.compactIfNeeded(any(), any()) } answers { firstArg() }
        }

    private fun mockProviderRegistry(): ProviderRegistry {
        val provider = mockk<Provider>(relaxed = true)
        every { provider.prefix } returns "primary"
        every { provider.isConfigured() } returns true
        val registry = mockk<ProviderRegistry>(relaxed = true)
        coEvery { registry.parse(any<String>()) } returns (provider to "test-model")
        return registry
    }

    /** Catalog offering one model from a *different* provider, so failover has a target. */
    private fun catalogWithBackup(): ModelCatalogRepository {
        val repo = mockk<ModelCatalogRepository>(relaxed = true)
        every { repo.catalog } returns MutableStateFlow(
            ModelCatalog(
                providers = emptyMap(),
                allModels = listOf(
                    ModelDescriptor(id = "backup:model-b", name = "model-b", providerPrefix = "backup"),
                ),
            ),
        )
        return repo
    }

    private fun loopWith(brain: Brain, catalog: ModelCatalogRepository?): MemoryAugmentedAgenticLoop {
        val toolRegistry = ToolRegistry()
        val executor = ToolExecutor(toolRegistry, context = mockk(relaxed = true))
        val userProfileStore = mockk<com.aura.profile.UserProfileStore>(relaxed = true)
        every { userProfileStore.getSystemPrompt() } returns ""
        val handRepository = mockk<com.aura.hands.HandRepository>(relaxed = true)
        coEvery { handRepository.getEnabled() } returns emptyList()
        return MemoryAugmentedAgenticLoop(
            brain,
            toolRegistry,
            executor,
            mockk<MemoryStore>(relaxed = true),
            mockk<com.aura.kg.ConversationKgExtractor>(relaxed = true),
            userProfileStore,
            handRepository,
            mockProviderRegistry(),
            passthroughCompactor(),
            modelCatalogRepository = catalog,
        )
    }

    @Test
    fun `failover retries within the same step and still produces an answer at maxSteps 1`() = runBlocking {
        // maxSteps = 1 is the sharp version of the question: if failover
        // consumed a step, this run could not possibly produce text.
        val brain = mockk<Brain>(relaxed = true)
        var call = 0
        coEvery { brain.stream(any(), any(), any(), any()) } answers {
            call += 1
            if (call == 1) {
                flowOf(
                    BrainChunk.Error(code = "http_503", message = "unavailable", retryable = true),
                )
            } else {
                flowOf(
                    BrainChunk.Text("answer from the backup provider"),
                    BrainChunk.Finished(FinishReason.stop.name),
                )
            }
        }

        val events = mutableListOf<AgentEvent>()
        loopWith(brain, catalogWithBackup())
            .run(Conversation().addUser("hello there"), model = "primary:model-a", maxSteps = 1)
            .collect { events += it }

        assertEquals(2, call, "expected exactly one failover retry")

        val text = events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text }
        assertTrue(
            text.contains("backup provider"),
            "failover must produce an answer inside the same step; got: '$text'",
        )

        // The user is told the provider changed rather than it happening silently.
        val warning = events.filterIsInstance<AgentEvent.Warning>().firstOrNull()
        assertTrue(warning != null, "failover should emit a Warning naming the swap")
        assertEquals("primary:model-a", warning!!.fromModel)
        assertEquals("backup:model-b", warning.toModel)
    }

    @Test
    fun `a non-retryable error does not trigger failover`() = runBlocking {
        // 401 means a bad key. Retrying against every configured provider
        // burns quota and surfaces the wrong error to the user.
        val brain = mockk<Brain>(relaxed = true)
        var call = 0
        coEvery { brain.stream(any(), any(), any(), any()) } answers {
            call += 1
            flowOf(BrainChunk.Error(code = "http_401", message = "unauthorized", retryable = false))
        }

        val events = mutableListOf<AgentEvent>()
        loopWith(brain, catalogWithBackup())
            .run(Conversation().addUser("hello there"), model = "primary:model-a", maxSteps = 5)
            .collect { events += it }

        assertEquals(1, call, "non-retryable error must not fail over")
        assertTrue(events.filterIsInstance<AgentEvent.Error>().any { it.code == "http_401" })
    }

    @Test
    fun `failover stops after one alternate provider`() = runBlocking {
        // The guard is `triedModels.size < 2`: one alternate, then give up.
        // Without it a retryable outage walks the whole catalog, and every
        // one of those calls is billed.
        val brain = mockk<Brain>(relaxed = true)
        var call = 0
        coEvery { brain.stream(any(), any(), any(), any()) } answers {
            call += 1
            flowOf(BrainChunk.Error(code = "http_503", message = "unavailable", retryable = true))
        }

        loopWith(brain, catalogWithBackup())
            .run(Conversation().addUser("hello there"), model = "primary:model-a", maxSteps = 5)
            .collect { }

        assertEquals(2, call, "should try the primary once and exactly one alternate")
    }
}
