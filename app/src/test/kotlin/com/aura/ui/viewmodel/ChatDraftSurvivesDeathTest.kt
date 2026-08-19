package com.aura.ui.viewmodel

import androidx.lifecycle.SavedStateHandle
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
 * A half-typed message, after Android kills the app in the background.
 *
 * `SavedStateHandle` appeared zero times in `app/src/main` and `rememberSaveable` twice
 * against 233 `remember`, so nothing in this app survived process death. Most of that
 * genuinely does not matter — a collapsed card reopening collapsed is not a bug worth
 * fixing. The draft is the exception: it is the one piece of state the user *authored*,
 * it is not in Room because it has not been sent, and Android kills backgrounded apps for
 * routine reasons. Look something up mid-message, come back, and it is gone.
 *
 * Deliberately scoped to the draft rather than to all 233 `remember` calls. The rest are
 * ephemeral view flags, and making every one of them saveable would be a large change for
 * something nobody would notice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatDraftSurvivesDeathTest {

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `a draft is written where it can outlive the process`() = runTest {
        val handle = SavedStateHandle()

        makeViewModel(handle).setDraft("the thing I was halfway through typing")

        assertEquals(
            "the thing I was halfway through typing",
            handle.get<String>("chat_draft"),
            "the draft never reached SavedStateHandle, so the OS has nothing to restore",
        )
    }

    @Test
    fun `a rebuilt ViewModel comes back with the draft still in the box`() = runTest {
        // What process death actually looks like: same SavedStateHandle contents, brand new
        // ViewModel.
        val handle = SavedStateHandle(mapOf("chat_draft" to "half a question about kotlin"))

        val restored = makeViewModel(handle)

        assertEquals("half a question about kotlin", restored.state.value.draft)
    }

    @Test
    fun `sending clears the saved draft too`() = runTest {
        // Otherwise the box outlives the message: send, background the app, come back and
        // find the sent text typed into the field again.
        val handle = SavedStateHandle()
        val vm = makeViewModel(handle)
        vm.setDraft("something")

        vm.setDraft("")

        assertEquals("", handle.get<String>("chat_draft"))
    }

    private fun makeViewModel(handle: SavedStateHandle): ChatViewModel {
        val userPreferences = mockk<UserPreferences>(relaxed = true)
        every { userPreferences.defaultModel } returns flowOf("")
        every { userPreferences.ttsEnabled } returns flowOf(false)
        every { userPreferences.planningEnabled } returns flowOf(false)
        return ChatViewModel(
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
                coEvery { store.mostRecent() } returns Conversation()
            },
            knowledgeGraphRepository = mockk(relaxed = true),
            crashLogger = mockk(relaxed = true),
            documentTextExtractor = null,
            modelCatalogRepository = null,
            skillsStore = null,
            tasteEngine = mockk(relaxed = true),
            agentStore = mockk(relaxed = true),
            strategyBandit = mockk(relaxed = true),
            savedStateHandle = handle,
        )
    }
}
