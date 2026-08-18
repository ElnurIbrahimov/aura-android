package com.aura.ui.viewmodel

import com.aura.agent.AgentEvent
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
 * Throttling the streaming UI must never drop a character.
 *
 * `AgentEvent.TextDelta` carries an *increment*, not a snapshot, so the event
 * stream itself cannot be conflated — a dropped delta is text the user never
 * sees and that never reaches TTS or the saved conversation. What is throttled
 * is publication: the buffer takes every delta, and the UI is refreshed at most
 * every [ChatSendController.STREAM_PUBLISH_INTERVAL_MS].
 *
 * That distinction is the whole risk of this change, so it is what these
 * assert. Each publication re-parses the entire message so far (see
 * `StreamingText`), which is why the number of publications — not the number of
 * tokens — is the term that decides whether a long answer stays responsive.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamingThrottleTest {

    private fun controllerOver(
        events: List<AgentEvent>,
        state: MutableStateFlow<ChatUiState>,
    ): ChatSendController {
        val strategyBandit = mockk<StrategyBandit>(relaxed = true)
        coEvery { strategyBandit.selectStrategy(any()) } returns ReasoningStrategy.SINGLE_PASS

        val loop = mockk<com.aura.agent.MemoryAugmentedAgenticLoop>(relaxed = true)
        every {
            loop.run(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns flowOf(*events.toTypedArray())

        val userPreferences = mockk<com.aura.data.UserPreferences>(relaxed = true)
        every { userPreferences.defaultModel } returns MutableStateFlow("ollama:test")
        every { userPreferences.planningEnabled } returns MutableStateFlow(false)

        return ChatSendController(
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
    }

    private fun assistantText(state: MutableStateFlow<ChatUiState>): String =
        state.value.conversation.turns.lastOrNull()?.assistant.orEmpty()

    @Test
    fun `every delta survives the throttle`() = runTest(UnconfinedTestDispatcher()) {
        // 400 deltas emitted with no delay between them, so nearly all of them
        // fall inside one throttle window. Under a naive conflate() this would
        // return a fraction of the text and look like a truncated answer.
        val deltas = (1..400).map { AgentEvent.TextDelta("tok$it ") }
        val expected = deltas.joinToString("") { (it as AgentEvent.TextDelta).text }

        val state = MutableStateFlow(ChatUiState(activeModel = "ollama:test"))
        val controller = controllerOver(deltas + AgentEvent.Done, state)
        state.value = state.value.copy(draft = "write me something long")
        controller.runSend(this)

        assertEquals(
            expected,
            assistantText(state),
            "throttling publications must not lose deltas — these are increments, not snapshots",
        )
    }

    @Test
    fun `a single delta appears without waiting out an interval`() =
        runTest(UnconfinedTestDispatcher()) {
            // The first token of a run must publish immediately. If the
            // throttle clock carried over from the previous run, a short answer
            // could sit invisible for up to one interval — or, for a one-token
            // reply, until the final flush.
            val state = MutableStateFlow(ChatUiState(activeModel = "ollama:test"))
            val controller = controllerOver(
                listOf(AgentEvent.TextDelta("Yes."), AgentEvent.Done),
                state,
            )
            state.value = state.value.copy(draft = "is it on?")
            controller.runSend(this)

            assertEquals("Yes.", assistantText(state))
        }

    @Test
    fun `a second run does not inherit the first run's text`() =
        runTest(UnconfinedTestDispatcher()) {
            // `responseBuffer` is the source of truth for publication now,
            // rather than the delta being appended to whatever the turn already
            // held. It is rebuilt per run; this pins that, because a buffer that
            // leaked across runs would prepend the previous answer to this one.
            val state = MutableStateFlow(ChatUiState(activeModel = "ollama:test"))

            val first = controllerOver(
                listOf(AgentEvent.TextDelta("first answer"), AgentEvent.Done),
                state,
            )
            state.value = state.value.copy(draft = "one")
            first.runSend(this)
            assertEquals("first answer", assistantText(state))

            val second = controllerOver(
                listOf(AgentEvent.TextDelta("second answer"), AgentEvent.Done),
                state,
            )
            state.value = state.value.copy(draft = "two")
            second.runSend(this)
            assertEquals("second answer", assistantText(state))
        }
}
