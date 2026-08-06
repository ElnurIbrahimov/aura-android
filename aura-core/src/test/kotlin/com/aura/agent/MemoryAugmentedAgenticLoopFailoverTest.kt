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

    /**
     * Registry whose [ProviderRegistry.configured] reports the given
     * prefixes as configured. Failover only targets configured providers,
     * so tests must declare which backup providers hold a valid key.
     */
    private fun mockProviderRegistry(configuredPrefixes: List<String> = listOf("primary", "backup")): ProviderRegistry {
        val provider = mockk<Provider>(relaxed = true)
        every { provider.prefix } returns "primary"
        every { provider.isConfigured() } returns true
        val registry = mockk<ProviderRegistry>(relaxed = true)
        coEvery { registry.parse(any<String>()) } returns (provider to "test-model")
        every { registry.configured() } returns configuredPrefixes.map { prefix ->
            mockk<Provider>(relaxed = true).also {
                every { it.prefix } returns prefix
                every { it.isConfigured() } returns true
            }
        }
        return registry
    }

    /** Catalog offering one model from a *different* provider, so failover has a target. */
    private fun catalogWithBackup(): ModelCatalogRepository = catalogOf(
        ModelDescriptor(id = "backup:model-b", name = "model-b", providerPrefix = "backup"),
    )

    private fun catalogOf(vararg models: ModelDescriptor): ModelCatalogRepository {
        val repo = mockk<ModelCatalogRepository>(relaxed = true)
        every { repo.catalog } returns MutableStateFlow(
            ModelCatalog(providers = emptyMap(), allModels = models.toList()),
        )
        return repo
    }

    private fun loopWith(
        brain: Brain,
        catalog: ModelCatalogRepository?,
        configuredPrefixes: List<String> = listOf("primary", "backup"),
    ): MemoryAugmentedAgenticLoop {
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
            mockProviderRegistry(configuredPrefixes),
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

    @Test
    fun `429 with Retry-After retries the SAME model once before failing over`() = runBlocking {
        // A rate limit is transient; the server told us exactly how long
        // to wait. The loop must delay and retry the user's chosen model
        // once — no provider switch, no Warning — before any failover.
        val brain = mockk<Brain>(relaxed = true)
        val modelsCalled = mutableListOf<String>()
        coEvery { brain.stream(any(), any(), any(), any()) } answers {
            modelsCalled += firstArg<String>()
            if (modelsCalled.size == 1) {
                flowOf(
                    BrainChunk.Error(
                        code = "http_error",
                        message = "HTTP 429",
                        retryable = true,
                        error = com.aura.providers.ProviderError(
                            code = "http_error",
                            message = "HTTP 429",
                            retryable = true,
                            retryAfterMs = 10L,
                        ),
                    ),
                )
            } else {
                flowOf(
                    BrainChunk.Text("answer after backoff"),
                    BrainChunk.Finished(FinishReason.stop.name),
                )
            }
        }

        val events = mutableListOf<AgentEvent>()
        loopWith(brain, catalogWithBackup())
            .run(Conversation().addUser("hello there"), model = "primary:model-a", maxSteps = 1)
            .collect { events += it }

        assertEquals(listOf("primary:model-a", "primary:model-a"), modelsCalled)
        val text = events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text }
        assertTrue(text.contains("after backoff"), "same-model retry must produce the answer; got '$text'")
        assertTrue(
            events.filterIsInstance<AgentEvent.Warning>().isEmpty(),
            "same-model retry is not a provider switch — no Warning expected",
        )
    }

    @Test
    fun `429 with Retry-After falls over normally when the retry also fails`() = runBlocking {
        // One honored backoff, then the regular failover path.
        val brain = mockk<Brain>(relaxed = true)
        val modelsCalled = mutableListOf<String>()
        coEvery { brain.stream(any(), any(), any(), any()) } answers {
            modelsCalled += firstArg<String>()
            flowOf(
                BrainChunk.Error(
                    code = "http_error",
                    message = "HTTP 429",
                    retryable = true,
                    error = com.aura.providers.ProviderError(
                        code = "http_error", message = "HTTP 429",
                        retryable = true, retryAfterMs = 10L,
                    ),
                ),
            )
        }

        loopWith(brain, catalogWithBackup())
            .run(Conversation().addUser("hello there"), model = "primary:model-a", maxSteps = 1)
            .collect { }

        assertEquals(
            listOf("primary:model-a", "primary:model-a", "backup:model-b"),
            modelsCalled,
            "one same-model backoff retry, then one failover, then stop",
        )
    }

    @Test
    fun `failover never targets a provider that is not configured`() = runBlocking {
        // The catalog can carry hydrated cache entries for providers whose
        // key has since been removed. Failing over to one guarantees a
        // second failure and burns the single failover slot.
        val brain = mockk<Brain>(relaxed = true)
        var call = 0
        coEvery { brain.stream(any(), any(), any(), any()) } answers {
            call += 1
            flowOf(BrainChunk.Error(code = "http_503", message = "unavailable", retryable = true))
        }

        val events = mutableListOf<AgentEvent>()
        loopWith(
            brain,
            catalogOf(ModelDescriptor(id = "stale:model-s", name = "model-s", providerPrefix = "stale")),
            configuredPrefixes = listOf("primary"), // "stale" has no key
        )
            .run(Conversation().addUser("hello there"), model = "primary:model-a", maxSteps = 5)
            .collect { events += it }

        assertEquals(1, call, "no configured alternate exists — must not fail over to an unconfigured one")
        assertTrue(events.filterIsInstance<AgentEvent.Error>().any { it.code == "http_503" })
    }

    @Test
    fun `failover prefers a configured model of the same family`() = runBlocking {
        // "primary:llama-3.3-70b" fails; the catalog offers a mistral and a
        // llama on other configured providers. The llama must win even
        // though the mistral comes first in catalog order.
        val brain = mockk<Brain>(relaxed = true)
        coEvery { brain.stream(any(), any(), any(), any()) } answers {
            flowOf(BrainChunk.Error(code = "http_503", message = "unavailable", retryable = true))
        }

        val loop = loopWith(
            brain,
            catalogOf(
                ModelDescriptor(id = "other:mistral-large", name = "mistral-large", providerPrefix = "other"),
                ModelDescriptor(id = "backup:llama-3.1-8b", name = "llama-3.1-8b", providerPrefix = "backup"),
            ),
            configuredPrefixes = listOf("primary", "other", "backup"),
        )

        assertEquals(
            "backup:llama-3.1-8b",
            loop.selectFailoverModel("primary:llama-3.3-70b", setOf("primary:llama-3.3-70b")),
        )
        // No same-family candidate → first eligible in catalog order.
        assertEquals(
            "other:mistral-large",
            loop.selectFailoverModel("primary:gpt-4o", setOf("primary:gpt-4o")),
        )
    }
}
