package com.aura.agent

import com.aura.memory.MemoryEntity
import com.aura.memory.MemoryStore
import com.aura.providers.FinishReason
import com.aura.providers.Provider
import com.aura.providers.ProviderMessage
import com.aura.providers.ProviderRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Everything the loop retrieves from Aura's own stores must reach the model
 * framed as data, not as instructions.
 *
 * Recalled memories are attacker-reachable in one hop: the model reads a page
 * with `read_url`, judges a line memorable, calls `remember`, and that line is
 * recalled into a later prompt. Beliefs and the taste profile are LLM-derived
 * from the same material. Before this, all of it was appended bare to the
 * **system** message — the highest-trust region of the prompt, sitting
 * alongside Aura's identity and the specialist's instructions, with nothing to
 * tell the model where trusted instructions ended and retrieved text began.
 *
 * [Conversation.toMessages] and both summarisation prompts already framed their
 * content this way. This test pins the one place that did not.
 */
class MemoryAugmentedAgenticLoopRetrievedContextTest {

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

    private fun memory(content: String) = MemoryEntity(
        id = "m1",
        content = content,
        source = "assistant",
        category = "fact",
    )

    /**
     * Runs one turn with a recall hit and returns the system prompt the loop
     * actually handed the provider.
     */
    private fun systemPromptFor(recallHits: List<MemoryEntity>): String {
        val captured = slot<List<ProviderMessage>>()
        val brain = mockk<Brain>(relaxed = true)
        coEvery { brain.stream(any(), capture(captured), any(), any()) } returns flowOf(
            BrainChunk.Text("Noted."),
            BrainChunk.Finished(FinishReason.stop.name),
        )

        val memoryStore = mockk<MemoryStore>(relaxed = true)
        coEvery { memoryStore.query(any(), any()) } returns recallHits

        val userProfileStore = mockk<com.aura.profile.UserProfileStore>(relaxed = true)
        every { userProfileStore.getSystemPrompt() } returns ""
        val handRepository = mockk<com.aura.hands.HandRepository>(relaxed = true)
        coEvery { handRepository.getEnabled() } returns emptyList()

        val loop = MemoryAugmentedAgenticLoop(
            brain,
            ToolRegistry(),
            mockk(relaxed = true),
            memoryStore,
            mockk<com.aura.kg.ConversationKgExtractor>(relaxed = true),
            userProfileStore,
            handRepository,
            mockProviderRegistry(),
            passthroughCompactor(),
        )

        runBlocking {
            loop.run(Conversation().addUser("what do I like?"), model = "test:model", maxSteps = 2)
                .collect { /* drain */ }
        }

        // The loop skips the system message entirely when every contributing
        // block is blank, which is exactly the no-recall case below — so treat
        // "no system message" as an empty prompt rather than an error.
        return captured.captured.firstOrNull { it.role == ProviderMessage.Role.system }?.content.orEmpty()
    }

    @Test
    fun `recalled memories are preceded by the untrusted-data preamble`() {
        val prompt = systemPromptFor(listOf(memory("Elnur prefers Kotlin.")))

        val preambleAt = prompt.indexOf(PromptFraming.UNTRUSTED_CONTEXT_PREAMBLE)
        val memoryAt = prompt.indexOf("Elnur prefers Kotlin.")

        assertTrue("preamble missing from the system prompt:\n$prompt", preambleAt >= 0)
        assertTrue("recalled memory missing from the system prompt:\n$prompt", memoryAt >= 0)
        assertTrue(
            "the preamble must come BEFORE the retrieved content, got preamble=$preambleAt memory=$memoryAt",
            preambleAt < memoryAt,
        )
    }

    @Test
    fun `retrieved content sits under its own section heading`() {
        val prompt = systemPromptFor(listOf(memory("Elnur prefers Kotlin.")))

        val sectionAt = prompt.indexOf("# Retrieved context")
        val memoryAt = prompt.indexOf("Elnur prefers Kotlin.")
        assertTrue("no '# Retrieved context' section:\n$prompt", sectionAt >= 0)
        assertTrue("memories must be inside the section", sectionAt < memoryAt)
        // Sub-blocks must be `##` so they nest under the section rather than
        // reading as siblings of it.
        assertTrue("memories should be a '## ' sub-heading:\n$prompt", "## Relevant memories" in prompt)
    }

    @Test
    fun `no retrieved content means no empty framed section`() {
        val prompt = systemPromptFor(emptyList())

        assertTrue(
            "an empty section wastes tokens on every turn with no recall:\n$prompt",
            "# Retrieved context" !in prompt,
        )
        assertTrue(
            "the preamble should not appear with nothing to frame:\n$prompt",
            PromptFraming.UNTRUSTED_CONTEXT_PREAMBLE !in prompt,
        )
    }
}
