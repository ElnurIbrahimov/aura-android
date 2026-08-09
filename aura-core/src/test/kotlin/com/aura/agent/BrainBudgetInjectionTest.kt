package com.aura.agent

import com.aura.data.UserPreferences
import com.aura.providers.ChatOptions
import com.aura.providers.ProviderChunk
import com.aura.providers.ProviderRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins what [Brain.stream] actually hands to the provider.
 *
 * [TokenBudgetPolicyTest] covers the arithmetic; this covers the wiring — that
 * the right inputs reach the policy and its output reaches `ChatOptions`. Both
 * are needed: the original defect was not bad arithmetic, it was the budget
 * block being skipped entirely for callers that set a thinking budget.
 */
class BrainBudgetInjectionTest {

    private val providerRegistry = mockk<ProviderRegistry>()
    private val identityStore = mockk<IdentityStore>(relaxed = true)
    private val contextBudgetResolver = mockk<ContextBudgetResolver>()
    private val userPreferences = mockk<UserPreferences>()

    private fun brain() = Brain(providerRegistry, identityStore, contextBudgetResolver, userPreferences)

    /** Captures the [ChatOptions] that reach the provider, then drives the stream to completion. */
    private suspend fun capture(
        options: ChatOptions,
        resolverValue: Int? = 100_800,
        reasoningEnabled: Boolean = true,
        reasoningBudget: Int = 32_000,
    ): ChatOptions {
        every { userPreferences.reasoningEnabled } returns flowOf(reasoningEnabled)
        every { userPreferences.reasoningBudget } returns flowOf(reasoningBudget)
        coEvery { contextBudgetResolver.maxTokensFor(any()) } returns resolverValue
        val captured = slot<ChatOptions>()
        coEvery {
            providerRegistry.chat(any(), any(), capture(captured), any())
        } returns emptyFlow<ProviderChunk>()

        brain().stream("anthropic:test", emptyList(), emptyList(), options).toList()
        return captured.captured
    }

    @Test
    fun `a creative draft keeps its full output budget and gets thinking on top`() = runTest {
        val sent = capture(ChatOptions(maxTokens = 28_672))
        assertEquals(57_344, sent.maxTokens)
        assertEquals(28_672, sent.thinkingBudget)
    }

    @Test
    fun `a small auxiliary call is left exactly as the caller wrote it`() = runTest {
        val sent = capture(ChatOptions(maxTokens = 150, temperature = 0.0))
        assertEquals(150, sent.maxTokens)
        assertNull(sent.thinkingBudget)
        assertEquals(0.0, sent.temperature, "unrelated options must survive untouched")
    }

    /**
     * The `TensionAnalyzer` shape. Before this change `Brain` skipped its whole
     * budget block whenever the caller supplied a thinking budget, so this pair
     * went out as 6,000/16,384 and Anthropic rejected it.
     */
    @Test
    fun `a caller-set thinking budget is normalised below max_tokens`() = runTest {
        val sent = capture(ChatOptions(maxTokens = 6_000, thinkingBudget = 16_384))
        val max = assertNotNull(sent.maxTokens)
        val thinking = assertNotNull(sent.thinkingBudget)
        assertTrue(max > thinking, "max_tokens ($max) must exceed thinking ($thinking)")
        assertEquals(16_384, thinking)
    }

    @Test
    fun `disabling reasoning strips thinking from an explicit caller budget`() = runTest {
        val sent = capture(ChatOptions(maxTokens = 6_000, thinkingBudget = 16_384), reasoningEnabled = false)
        assertNull(sent.thinkingBudget)
        assertEquals(6_000, sent.maxTokens)
    }

    @Test
    fun `the resolver supplies the budget when the caller does not`() = runTest {
        val sent = capture(ChatOptions(), resolverValue = 100_800)
        assertEquals(100_800, sent.maxTokens)
        assertEquals(32_000, sent.thinkingBudget)
    }

    /**
     * The resolver is a live catalog probe — for OllamaCloud an /api/show
     * fan-out per model — and this runs on every step of every agentic turn.
     * Keeping it behind the caller check is load-bearing, so pin it.
     */
    @Test
    fun `the resolver is not consulted when the caller set maxTokens`() = runTest {
        capture(ChatOptions(maxTokens = 8_192))
        coVerify(exactly = 0) { contextBudgetResolver.maxTokensFor(any()) }
    }

    @Test
    fun `the resolver is consulted exactly once when the caller did not`() = runTest {
        capture(ChatOptions())
        coVerify(exactly = 1) { contextBudgetResolver.maxTokensFor(any()) }
    }

    /** Pins the existing `runCatching { … }.getOrDefault(…)` contract on both preference reads. */
    @Test
    fun `a failing preference read falls back to reasoning enabled at 32k`() = runTest {
        every { userPreferences.reasoningEnabled } returns kotlinx.coroutines.flow.flow { error("datastore down") }
        every { userPreferences.reasoningBudget } returns kotlinx.coroutines.flow.flow { error("datastore down") }
        coEvery { contextBudgetResolver.maxTokensFor(any()) } returns 100_800
        val captured = slot<ChatOptions>()
        coEvery {
            providerRegistry.chat(any(), any(), capture(captured), any())
        } returns emptyFlow<ProviderChunk>()

        brain().stream("anthropic:test", emptyList(), emptyList(), ChatOptions()).toList()

        assertEquals(32_000, captured.captured.thinkingBudget)
        assertEquals(100_800, captured.captured.maxTokens)
    }

    @Test
    fun `an unresolvable model still gets a total large enough to hold its thinking`() = runTest {
        val sent = capture(ChatOptions(), resolverValue = null)
        val max = assertNotNull(sent.maxTokens)
        val thinking = assertNotNull(sent.thinkingBudget)
        assertTrue(max > thinking, "a thinking budget must never be sent without room to answer")
    }
}
