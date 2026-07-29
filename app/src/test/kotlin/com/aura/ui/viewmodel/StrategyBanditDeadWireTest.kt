package com.aura.ui.viewmodel


import com.aura.agent.StrategyBandit



import com.aura.tools.DelegateToAgentTool
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * P0 regression: ChatSendController must receive StrategyBandit and actually
 * call selectStrategy when sending a message.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StrategyBanditDeadWireTest {

    @Test
    fun `ChatSendController calls strategyBandit selectStrategy on send`() = runTest(UnconfinedTestDispatcher()) {
        val strategyBandit = mockk<StrategyBandit>()
        val loop = mockk<com.aura.agent.MemoryAugmentedAgenticLoop>(relaxed = true)
        val userPreferences = mockk<com.aura.data.UserPreferences>(relaxed = true)
        every { userPreferences.defaultModel } returns MutableStateFlow("ollama:test")

        var called = false
        coEvery { strategyBandit.selectStrategy(any()) } coAnswers {
            called = true
            com.aura.agent.ReasoningStrategy.SINGLE_PASS
        }

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
        state.value = state.value.copy(draft = "hello world")
        controller.runSend(this)

        assertEquals(true, called, "strategyBandit.selectStrategy must be invoked for every send")
    }
}
