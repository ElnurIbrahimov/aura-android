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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The stable half of the system prompt must be byte-identical across every step
 * of a run and across turns that differ only in what Aura retrieved.
 *
 * This is the test that makes prompt caching real. Providers cache a
 * byte-identical prefix and nothing else: one character of drift anywhere in
 * the first system message costs the entire cache, and **no error is reported
 * when that happens** — the request simply bills full price. So the failure
 * this guards against is invisible in production and invisible in every other
 * test, which is exactly the kind that survives.
 *
 * Concretely, three per-step reads used to sit inside the stable block:
 * `agentStore.byId()` (a Room query), `brain.resolvedIdentity()` and
 * `userProfileStore.getSystemPrompt()` (DataStore). Any of them returning a
 * slightly different string mid-run — or simply being re-read while the profile
 * extractor wrote — silently ended the cache from that step onward.
 *
 * If you are here because this test failed after adding something to the system
 * prompt: the new content almost certainly belongs in the VOLATILE message, not
 * the stable one. Stable means "fixed for the whole run", not "usually the same".
 */
class SystemPromptStabilityTest {

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

    private fun memory(id: String, content: String, category: String = "fact") = MemoryEntity(
        id = id,
        content = content,
        source = "assistant",
        category = category,
    )

    /**
     * A real [ConsultGate] over a scripted provider, so these tests exercise the
     * actual selection and rendering rather than a stub's idea of them.
     */
    private fun consultGateSelecting(vararg oneBasedIndices: Int): ConsultGate {
        val consultRegistry = mockk<ProviderRegistry>(relaxed = true)
        coEvery { consultRegistry.chat(any(), any(), any()) } returns flowOf(
            com.aura.providers.ProviderChunk(text = """{"applicable":${oneBasedIndices.toList()}}"""),
        )
        return ConsultGate(consultRegistry)
    }

    private fun cheapResolver(model: String? = "test:cheap"): com.aura.providers.CheapModelResolver =
        mockk<com.aura.providers.CheapModelResolver>(relaxed = true).also {
            coEvery { it.resolve(any(), any()) } returns model
        }

    /**
     * Drive one turn over [steps] model steps and return the system messages
     * handed to the provider on each step.
     *
     * The scripted Brain emits a tool call on every step but the last, which is
     * what makes the loop go round again — a single-step run could not show
     * drift between steps at all.
     */
    private fun systemMessagesPerStep(
        steps: Int = 3,
        identity: String = "You are Aura.",
        profilePrompt: String = "## About the user\nName: Elnur",
        recall: List<MemoryEntity> = listOf(memory("m1", "likes strong coffee")),
        consultGate: ConsultGate? = null,
        cheapModelResolver: com.aura.providers.CheapModelResolver? = null,
    ): List<List<String>> {
        val perStep = mutableListOf<List<String>>()

        val brain = mockk<Brain>(relaxed = true)
        var call = 0
        coEvery { brain.resolvedIdentity() } returns identity
        coEvery { brain.stream(any(), any(), any(), any()) } answers {
            @Suppress("UNCHECKED_CAST")
            val messages = secondArg<List<ProviderMessage>>()
            perStep += messages
                .filter { it.role == ProviderMessage.Role.system }
                .map { it.content }
            call++
            if (call < steps) {
                // A tool call keeps the loop running to the next step.
                flowOf(
                    BrainChunk.ToolCallStart("tc$call", "get_current_time"),
                    BrainChunk.ToolCallEnd("tc$call", "get_current_time", "{}"),
                    BrainChunk.Finished(FinishReason.tool_calls.name),
                )
            } else {
                flowOf(BrainChunk.Text("Done."), BrainChunk.Finished(FinishReason.stop.name))
            }
        }

        val memoryStore = mockk<MemoryStore>(relaxed = true)
        coEvery { memoryStore.query(any(), any()) } returns recall

        val userProfileStore = mockk<com.aura.profile.UserProfileStore>(relaxed = true)
        every { userProfileStore.getSystemPrompt() } returns profilePrompt

        val handRepository = mockk<com.aura.hands.HandRepository>(relaxed = true)
        coEvery { handRepository.getEnabled() } returns emptyList()

        // A real registry with one real tool, so the tool call resolves and the
        // loop continues rather than erroring out.
        val registry = ToolRegistry().apply {
            register(
                Tool(
                    name = "get_current_time",
                    description = "Local time.",
                    risk = ToolRisk.READ_ONLY,
                    execute = { _, _ -> ToolResult.Ok("12:00") },
                ),
            )
        }

        val loop = MemoryAugmentedAgenticLoop(
            brain,
            registry,
            ToolExecutor(registry, context = mockk(relaxed = true)),
            memoryStore,
            mockk<com.aura.kg.ConversationKgExtractor>(relaxed = true),
            userProfileStore,
            handRepository,
            mockProviderRegistry(),
            passthroughCompactor(),
            cheapModelResolver = cheapModelResolver,
            consultGate = consultGate,
        )

        runBlocking {
            loop.run(Conversation().addUser("what do I like?"), model = "test:model", maxSteps = steps + 1)
                .collect { /* drain */ }
        }
        return perStep
    }

    // ---- the invariant ---------------------------------------------------

    @Test
    fun `the stable system message is byte-identical across every step`() {
        val perStep = systemMessagesPerStep(steps = 3)

        assertTrue(perStep.size >= 2, "need at least two steps to compare, got ${perStep.size}")
        val stableFirst = perStep.first().firstOrNull()
        assertNotNull(stableFirst, "no system message on the first step")

        perStep.forEachIndexed { i, messages ->
            assertEquals(
                stableFirst,
                messages.firstOrNull(),
                "stable system message drifted on step ${i + 1} — the prompt cache is lost from here on",
            )
        }
    }

    @Test
    fun `every step sends two distinct, non-empty system messages`() {
        // The counterweight to the test above, which would pass just as happily
        // if the split had collapsed and there were only one message, or if the
        // stable one were empty.
        //
        // Deliberately NOT asserting that the volatile message differs BETWEEN
        // steps: with the consciousness components unset (they are nullable
        // constructor args this harness does not pass) there is nothing
        // step-1-only left in it, so step 1 and step 2 legitimately match. A
        // test named for a difference it cannot produce is worse than no test.
        val perStep = systemMessagesPerStep(steps = 3)

        assertTrue(perStep.size >= 2, "need at least two steps, got ${perStep.size}")
        perStep.forEachIndexed { i, messages ->
            assertEquals(
                2,
                messages.size,
                "step ${i + 1} sent ${messages.size} system messages, expected stable + volatile",
            )
            assertTrue(messages[0].isNotBlank(), "stable message blank on step ${i + 1}")
            assertTrue(messages[1].isNotBlank(), "volatile message blank on step ${i + 1}")
            assertTrue(
                messages[0] != messages[1],
                "the two system messages are identical on step ${i + 1} — the split is not doing anything",
            )
        }
    }

    @Test
    fun `retrieved memories never touch the stable message`() {
        val withRecall = systemMessagesPerStep(
            steps = 2,
            recall = listOf(memory("m1", "MARKER_ALPHA")),
        )
        val withOtherRecall = systemMessagesPerStep(
            steps = 2,
            recall = listOf(memory("m2", "MARKER_BETA")),
        )

        assertEquals(
            withRecall.first().first(),
            withOtherRecall.first().first(),
            "recall content leaked into the stable message",
        )
        assertTrue(
            withRecall.first().drop(1).any { "MARKER_ALPHA" in it },
            "recall content did not reach the volatile message at all",
        )
    }

    @Test
    fun `the user profile lives in the volatile message`() {
        // The profile extractor rewrites this between turns, so it is precisely
        // the thing that changes while identity does not. It sat in the stable
        // block until 2026-08-10.
        val a = systemMessagesPerStep(steps = 2, profilePrompt = "## About the user\nName: A")
        val b = systemMessagesPerStep(steps = 2, profilePrompt = "## About the user\nName: B")

        assertEquals(
            a.first().first(),
            b.first().first(),
            "a profile change moved the stable message",
        )
        assertTrue(a.first().drop(1).any { "Name: A" in it }, "profile missing from the volatile message")
        assertTrue(b.first().drop(1).any { "Name: B" in it }, "profile missing from the volatile message")
    }

    @Test
    fun `identity does reach the stable message`() {
        // Guards the opposite failure: a "stable" message that is stable because
        // it is empty would pass every test above.
        val messages = systemMessagesPerStep(steps = 2, identity = "You are Aura, MARKER_IDENTITY.")
        assertTrue(
            "MARKER_IDENTITY" in messages.first().first(),
            "identity did not reach the stable message: ${messages.first().first()}",
        )
    }

    // ---- the consult reminder --------------------------------------------

    @Test
    fun `the consult reminder lands in the volatile message, never the stable one`() {
        val messages = systemMessagesPerStep(
            steps = 2,
            recall = listOf(memory("m1", "MARKER_PREFERENCE", category = "preference")),
            consultGate = consultGateSelecting(1),
            cheapModelResolver = cheapResolver(),
        ).first()

        assertTrue(
            "Before you answer" !in messages.first(),
            "the consult reminder reached the stable message — every install's prompt cache is lost",
        )
        assertTrue(
            messages.drop(1).any { "Before you answer" in it && "MARKER_PREFERENCE" in it },
            "the consult reminder did not reach the volatile message: ${messages.drop(1)}",
        )
    }

    @Test
    fun `the reminder sits last, closest to the conversation`() {
        // The entire measured effect is proximity: a constraint present anywhere
        // in context is followed about 7% of the time, one restated next to the
        // question about 91%. If something else appends after this, the reminder
        // keeps its cost and loses its reason.
        val volatileMsg = systemMessagesPerStep(
            steps = 2,
            recall = listOf(memory("m1", "MARKER_PREFERENCE", category = "preference")),
            consultGate = consultGateSelecting(1),
            cheapModelResolver = cheapResolver(),
        ).first().drop(1).single { "Before you answer" in it }

        assertTrue(
            volatileMsg.indexOf("# Before you answer") > volatileMsg.indexOf("Retrieved context"),
            "the reminder must come after the retrieved block it is reminding about",
        )
        assertTrue(
            volatileMsg.trimEnd().endsWith("MARKER_PREFERENCE"),
            "something was appended after the reminder: ...${volatileMsg.takeLast(120)}",
        )
    }

    @Test
    fun `a turn with nothing consultable does not consult`() {
        // The cost argument rests on this. Default recall here is a plain fact,
        // which is context rather than a standing instruction, so the pass must
        // not fire and the prompt must not grow.
        val consultRegistry = mockk<ProviderRegistry>(relaxed = true)
        coEvery { consultRegistry.chat(any(), any(), any()) } returns flowOf(
            com.aura.providers.ProviderChunk(text = """{"applicable":[1]}"""),
        )

        val messages = systemMessagesPerStep(
            steps = 2,
            recall = listOf(memory("m1", "the office wifi password is on the fridge")),
            consultGate = ConsultGate(consultRegistry),
            cheapModelResolver = cheapResolver(),
        ).first()

        assertTrue(messages.none { "Before you answer" in it }, "consulted on a turn with no standing instructions")
        io.mockk.coVerify(exactly = 0) { consultRegistry.chat(any(), any(), any()) }
    }

    @Test
    fun `the reminder is resolved once per run, not once per step`() {
        // Steps 2..N are tool round-trips on the same user message and read the
        // same cached recall, so a per-step consult would bill an identical call
        // each time to reach an identical answer.
        val consultRegistry = mockk<ProviderRegistry>(relaxed = true)
        coEvery { consultRegistry.chat(any(), any(), any()) } returns flowOf(
            com.aura.providers.ProviderChunk(text = """{"applicable":[1]}"""),
        )

        val perStep = systemMessagesPerStep(
            steps = 3,
            recall = listOf(memory("m1", "MARKER_PREFERENCE", category = "preference")),
            consultGate = ConsultGate(consultRegistry),
            cheapModelResolver = cheapResolver(),
        )

        assertTrue(perStep.size >= 2, "need at least two steps, got ${perStep.size}")
        io.mockk.coVerify(exactly = 1) { consultRegistry.chat(any(), any(), any()) }
        val reminders = perStep.map { step -> step.first { "Before you answer" in it }.substringAfter("# Before you answer") }
        assertEquals(1, reminders.distinct().size, "the reminder changed between steps of one turn")
    }

    @Test
    fun `a consult that fails leaves the turn otherwise untouched`() {
        val exploding = mockk<ProviderRegistry>(relaxed = true)
        coEvery { exploding.chat(any(), any(), any()) } throws RuntimeException("provider down")

        val withFailure = systemMessagesPerStep(
            steps = 2,
            recall = listOf(memory("m1", "MARKER_PREFERENCE", category = "preference")),
            consultGate = ConsultGate(exploding),
            cheapModelResolver = cheapResolver(),
        ).first()
        val withoutGate = systemMessagesPerStep(
            steps = 2,
            recall = listOf(memory("m1", "MARKER_PREFERENCE", category = "preference")),
        ).first()

        // Byte-identical, not merely "still works": a best-effort addition that
        // perturbs the prompt when it fails is not best-effort.
        assertEquals(withoutGate, withFailure, "a failed consult changed the prompt")
    }

    @Test
    fun `the volatile message carries no leading blank line`() {
        // Each contributing block carries its own leading "\n\n" — that was the
        // separator when they were concatenated onto one string. Providers that
        // re-join system messages add their own, so leaving it would insert a
        // blank line into every prompt.
        val volatiles = systemMessagesPerStep(steps = 2).first().drop(1)
        volatiles.forEach {
            assertTrue(
                it == it.trimStart(),
                "volatile message starts with whitespace: ${it.take(20).replace("\n", "\\n")}",
            )
        }
    }
}
