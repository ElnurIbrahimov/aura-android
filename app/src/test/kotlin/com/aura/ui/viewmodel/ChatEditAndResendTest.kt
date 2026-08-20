package com.aura.ui.viewmodel

import com.aura.agent.Conversation
import com.aura.agent.ConversationStore
import com.aura.data.UserPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Editing a message you already sent.
 *
 * `editAndResend` truncated with `subList(0, turnIndex)`, which is exclusive — it removed
 * the turn being edited along with everything after it. It then called
 * `runSend(retryUserText = newText)`, and that path documents its own contract: *"the
 * caller has already rewound the existing turn and this method must not append another
 * user row."* Neither side added the edited message, so it went nowhere. Editing the very
 * first message left an empty conversation.
 *
 * `retryLast` gets this right by going through `prepareConversationForRetry`, which keeps
 * the user turn. This is the same rewind, one turn further back plus the new text.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatEditAndResendTest {

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `the edited message replaces the one it was edited from`() = runTest {
        val vm = makeViewModel(
            Conversation()
                .addUser("waht is kotlin")
                .addAssistant("Did you mean Kotlin?")
                .addUser("second question")
                .addAssistant("second answer"),
        )

        vm.editAndResend(turnIndex = 0, newText = "what is kotlin")

        val turns = vm.state.value.conversation.turns
        assertEquals(1, turns.size, "everything after the edited turn goes; the edit itself stays")
        assertEquals("what is kotlin", turns[0].user)
    }

    @Test
    fun `editing the first message does not empty the conversation`() = runTest {
        // The worst shape of the bug: one message in, edit it, and the screen goes blank
        // with nothing sent.
        val vm = makeViewModel(Conversation().addUser("hello"))

        vm.editAndResend(turnIndex = 0, newText = "hello there")

        assertEquals(listOf("hello there"), vm.state.value.conversation.turns.map { it.user })
    }

    @Test
    fun `a later turn keeps the history before it`() = runTest {
        val vm = makeViewModel(
            Conversation()
                .addUser("first")
                .addAssistant("first reply")
                .addUser("scond")
                .addAssistant("second reply"),
        )

        // A Turn holds the user message and its reply together, so the second exchange
        // is index 1, not 2.
        vm.editAndResend(turnIndex = 1, newText = "second")

        val turns = vm.state.value.conversation.turns
        assertEquals(listOf("first", "second"), turns.map { it.user })
        assertEquals("first reply", turns[0].assistant, "history before the edit is untouched")
    }

    private fun makeViewModel(conversation: Conversation): ChatViewModel {
        val userPreferences = mockk<UserPreferences>(relaxed = true)
        every { userPreferences.defaultModel } returns flowOf("")
        every { userPreferences.ttsEnabled } returns flowOf(false)
        every { userPreferences.planningEnabled } returns flowOf(false)
        val vm = ChatViewModel(
            application = mockk(relaxed = true),
            loop = mockk(relaxed = true),
            providerKeys = mockk(relaxed = true),
            providerRegistry = mockk(relaxed = true),
            toolRegistry = mockk(relaxed = true),
            toolExecutor = mockk(relaxed = true),
            delegateToAgentTool = mockk(relaxed = true),
            textToSpeech = mockk(relaxed = true),
            userPreferences = userPreferences,
            memoryStore = mockk(relaxed = true),
            conversationStore = mockk<ConversationStore>(relaxed = true).also { store ->
                coEvery { store.mostRecent() } returns conversation
            },
            knowledgeGraphRepository = mockk(relaxed = true),
            crashLogger = mockk(relaxed = true),
            documentTextExtractor = null,
            modelCatalogRepository = null,
            skillsStore = null,
            tasteEngine = mockk(relaxed = true),
            agentStore = mockk(relaxed = true),
            strategyBandit = mockk<com.aura.agent.StrategyBandit>(relaxed = true),
        )
        return vm
    }
}
