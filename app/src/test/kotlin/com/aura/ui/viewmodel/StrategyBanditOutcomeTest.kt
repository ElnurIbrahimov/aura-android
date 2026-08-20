package com.aura.ui.viewmodel

import com.aura.agent.AgentEvent
import com.aura.agent.ProblemCategory
import com.aura.agent.ReasoningStrategy
import com.aura.agent.StrategyBandit
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The bandit must record exactly one outcome per run, and it must be the right one.
 *
 * Every one of these three cases was wrong before, and all three were wrong in
 * the same way: `AgentEvent.Error` does not end the stream. The loop emits it
 * and falls through to `Result` and then the unconditional `Done`, so a
 * controller that recorded from both branches recorded twice.
 *
 *  - `max_steps_exceeded` recorded a failure *and* a success. Alpha and beta
 *    both incremented, which leaves the Beta mean untouched but halves its
 *    variance — so a failed run made the arm more confident, not less.
 *  - A provider error sets `finished = true` inside the loop, so the old
 *    failure branch's `code == "max_steps_exceeded"` test missed it entirely
 *    and the run was recorded as a clean success.
 *  - `empty_response` did the same.
 *
 * `StrategyBanditStore.recordOutcome` is unconditionally additive with no
 * idempotency key, so "recorded twice" is never harmless. Thompson sampling
 * over a corrupted posterior is the failure mode this whole subsystem exists
 * to avoid, and nothing would have surfaced it: the arms still moved, the
 * numbers still looked plausible, and the suite stayed green.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StrategyBanditOutcomeTest {

    private data class Recorded(val category: ProblemCategory, val strategy: ReasoningStrategy, val success: Boolean)

    private suspend fun runWith(
        events: List<AgentEvent>,
        recorded: MutableList<Recorded>,
        scope: kotlinx.coroutines.CoroutineScope,
        pending: MutableList<Recorded> = mutableListOf(),
    ) {
        val strategyBandit = mockk<StrategyBandit>(relaxed = true)
        coEvery { strategyBandit.selectStrategy(any()) } returns ReasoningStrategy.SINGLE_PASS
        coEvery { strategyBandit.recordOutcome(any(), any(), any()) } coAnswers {
            recorded += Recorded(firstArg(), secondArg(), thirdArg())
        }
        // A completed run no longer records an outcome; it leaves one pending for whatever
        // verdict the user gives. Captured here so "exactly one per run" can still be
        // asserted across both paths.
        every { strategyBandit.notePending(any(), any(), any()) } answers {
            pending += Recorded(secondArg(), thirdArg(), success = true)
        }

        val loop = mockk<com.aura.agent.MemoryAugmentedAgenticLoop>(relaxed = true)
        every {
            loop.run(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns flowOf(*events.toTypedArray())

        val userPreferences = mockk<com.aura.data.UserPreferences>(relaxed = true)
        every { userPreferences.defaultModel } returns MutableStateFlow("ollama:test")
        every { userPreferences.planningEnabled } returns MutableStateFlow(false)

        val state = MutableStateFlow(ChatUiState(activeModel = "ollama:test"))
        val controller = ChatSendController(
            application = mockk(relaxed = true),
            state = state,
            loop = loop,
            userPreferences = userPreferences,
            textToSpeech = mockk(relaxed = true),
            knowledgeGraphRepository = mockk(relaxed = true),
            toolExecutor = mockk(relaxed = true),
            delegateToAgentTool = mockk(relaxed = true),
            strategyBandit = strategyBandit,
            onSaveConversation = {},
            onKgNodeCountChanged = {},
            onFirstConversationComplete = {},
            extractCitations = { _, _ -> emptyList() },
            setErrorWithAutoDismiss = { _, _, _ -> },
            generateTitle = { it.take(20) },
            onError = {},
            onRunComplete = {},
        )
        state.value = state.value.copy(draft = "a question worth asking")
        controller.runSend(scope)
    }

    @Test
    fun `max_steps_exceeded records exactly one failure, not a failure and a success`() =
        runTest(UnconfinedTestDispatcher()) {
            val recorded = mutableListOf<Recorded>()
            runWith(
                listOf(
                    AgentEvent.Error("max_steps_exceeded", "Hit max steps (10) without finishing.", retryable = false),
                    AgentEvent.Done,
                ),
                recorded,
                this,
            )
            assertEquals(1, recorded.size, "expected exactly one outcome per run, got $recorded")
            assertEquals(false, recorded.single().success, "a run that hit max steps is not a success")
        }

    @Test
    fun `a provider error records a failure, not a success`() = runTest(UnconfinedTestDispatcher()) {
        // The loop sets finished = true on a provider error, so the old
        // `code == "max_steps_exceeded"` guard never matched and Done recorded
        // this as a clean win for whichever strategy happened to be sampled.
        val recorded = mutableListOf<Recorded>()
        runWith(
            listOf(
                AgentEvent.Error("provider_error", "upstream 500", retryable = true),
                AgentEvent.Done,
            ),
            recorded,
            this,
        )
        assertEquals(1, recorded.size, "expected exactly one outcome per run, got $recorded")
        assertEquals(false, recorded.single().success, "a provider failure is not a success")
    }

    @Test
    fun `a clean run records nothing yet, and leaves exactly one turn pending`() =
        runTest(UnconfinedTestDispatcher()) {
            // This used to assert one success. "The run finished" is not evidence the answer
            // was good, and passing it as success drove every arm to Beta(large, ~0) — the
            // same corrupted posterior this file was written to protect, arrived at from the
            // other direction. A completed run now waits for a verdict.
            //
            // The invariant is unchanged and still asserted: exactly one observation per
            // run. It is simply deferred rather than assumed.
            val recorded = mutableListOf<Recorded>()
            val pending = mutableListOf<Recorded>()

            runWith(listOf(AgentEvent.Done), recorded, this, pending)

            assertEquals(0, recorded.size, "a completed run must not count itself a success: $recorded")
            assertEquals(1, pending.size, "expected exactly one pending turn per run, got $pending")
        }
}
