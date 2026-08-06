package com.aura.ui.viewmodel

import com.aura.agent.AgentStore
import com.aura.agent.Conversation
import com.aura.agent.ConversationStore
import com.aura.data.UserPreferences
import com.aura.proactive.BriefContext
import com.aura.proactive.ProactiveEventDao
import com.aura.proactive.ProactiveEventEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Consume-once semantics for the nav-argument driven chat entry points.
 *
 * ChatRoute's LaunchedEffects re-fire on back-navigation because the
 * back stack entry keeps its nav arguments. The ViewModel must
 * therefore guarantee:
 *  - a morning brief is auto-sent at most ONCE per event id (a re-fire
 *    must not trigger another LLM call), and
 *  - the `draft` nav argument is applied at most once, so it can never
 *    clobber text the user typed after the first application.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelBriefConsumeOnceTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var proactiveEventDao: ProactiveEventDao

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        proactiveEventDao = mockk()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `sendMorningBrief loads the body by id and drafts it`() = runTest(dispatcher) {
        coEvery { proactiveEventDao.byId(42L) } returns briefEntity(42L, "☀️ 2 tasks due today")
        val viewModel = makeViewModel()
        advanceUntilIdle()

        viewModel.sendMorningBrief(42L)
        advanceUntilIdle()

        // No model is configured in this test, so the send pipeline
        // stops after staging the draft — which is exactly what we
        // want to observe: the brief body came from the DAO, not from
        // a nav argument.
        assertEquals("☀️ 2 tasks due today", viewModel.state.value.draft)
        coVerify(exactly = 1) { proactiveEventDao.byId(42L) }
    }

    @Test
    fun `sendMorningBrief is consume-once per event id`() = runTest(dispatcher) {
        coEvery { proactiveEventDao.byId(42L) } returns briefEntity(42L, "brief body")
        val viewModel = makeViewModel()
        advanceUntilIdle()

        viewModel.sendMorningBrief(42L)
        advanceUntilIdle()
        // Simulates the LaunchedEffect re-firing on back-navigation.
        viewModel.sendMorningBrief(42L)
        viewModel.sendMorningBrief(42L)
        advanceUntilIdle()

        coVerify(exactly = 1) { proactiveEventDao.byId(42L) }
    }

    @Test
    fun `a different brief id is sent independently`() = runTest(dispatcher) {
        coEvery { proactiveEventDao.byId(1L) } returns briefEntity(1L, "monday brief")
        coEvery { proactiveEventDao.byId(2L) } returns briefEntity(2L, "tuesday brief")
        val viewModel = makeViewModel()
        advanceUntilIdle()

        viewModel.sendMorningBrief(1L)
        advanceUntilIdle()
        viewModel.sendMorningBrief(2L)
        advanceUntilIdle()

        coVerify(exactly = 1) { proactiveEventDao.byId(1L) }
        coVerify(exactly = 1) { proactiveEventDao.byId(2L) }
    }

    @Test
    fun `non-positive or unknown ids are ignored`() = runTest(dispatcher) {
        coEvery { proactiveEventDao.byId(any()) } returns null
        val viewModel = makeViewModel()
        advanceUntilIdle()

        viewModel.sendMorningBrief(0L)
        viewModel.sendMorningBrief(-5L)
        viewModel.sendMorningBrief(99L) // row deleted (30-day retention)
        advanceUntilIdle()

        assertEquals("", viewModel.state.value.draft)
        coVerify(exactly = 0) { proactiveEventDao.byId(0L) }
        coVerify(exactly = 0) { proactiveEventDao.byId(-5L) }
        coVerify(exactly = 1) { proactiveEventDao.byId(99L) }
    }

    @Test
    fun `structured brief row is rendered via toSummary`() = runTest(dispatcher) {
        val context = BriefContext(calendarToday = listOf("9am standup"))
        val body = Json.encodeToString(BriefContext.serializer(), context)
        coEvery { proactiveEventDao.byId(7L) } returns ProactiveEventEntity(
            id = 7L,
            eventType = "MorningBriefStructured",
            title = "Morning brief",
            body = body,
            timestamp = 1_000L,
        )
        val viewModel = makeViewModel()
        advanceUntilIdle()

        viewModel.sendMorningBrief(7L)
        advanceUntilIdle()

        assertTrue(
            "expected summary line, got '${viewModel.state.value.draft}'",
            viewModel.state.value.draft.contains("9am standup"),
        )
    }

    @Test
    fun `applyInitialDraft never clobbers the user's typed draft on re-fire`() = runTest(dispatcher) {
        val viewModel = makeViewModel()
        advanceUntilIdle()

        viewModel.applyInitialDraft("remind me to ")
        assertEquals("remind me to ", viewModel.state.value.draft)

        // The user types over the prefill…
        viewModel.setDraft("remind me to buy milk")
        // …then navigates away and back: the nav effect re-fires.
        viewModel.applyInitialDraft("remind me to ")

        assertEquals("remind me to buy milk", viewModel.state.value.draft)
    }

    private fun briefEntity(id: Long, body: String) = ProactiveEventEntity(
        id = id,
        eventType = "MorningBriefReady",
        title = "☀️ Good morning",
        body = body,
        timestamp = 1_000L,
    )

    private fun makeViewModel(): ChatViewModel {
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
            agentStore = mockk<AgentStore>(relaxed = true).also { store ->
                every { store.all() } returns flowOf(emptyList())
            },
            strategyBandit = mockk(relaxed = true),
            proactiveEventDao = proactiveEventDao,
        )
    }
}
