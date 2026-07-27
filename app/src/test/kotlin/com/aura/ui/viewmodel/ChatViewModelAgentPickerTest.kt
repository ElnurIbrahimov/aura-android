package com.aura.ui.viewmodel

import com.aura.agent.AgentEntity
import com.aura.agent.AgentStore
import com.aura.agent.Specialist
import com.aura.agent.Conversation
import com.aura.agent.ConversationStore
import com.aura.data.UserPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelAgentPickerTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var agentStore: AgentStore

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        agentStore = mockk(relaxed = true)
        val builtin = Specialist.ALL.map { s ->
            AgentEntity(
                id = "agent_${s.name}",
                name = s.name,
                icon = s.icon,
                description = "Agent ${s.name}",
                identity = s.systemPrompt,
                toolsAllowed = s.toolsAllowed.joinToString(","),
                preferredModel = s.suggestedModel,
                memoryScope = "agent:${s.name}",
                isBuiltin = true,
            )
        }
        every { agentStore.all() } returns flowOf(builtin)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `setActiveAgent updates agentId and system prompt`() = runTest(dispatcher) {
        val customAgent = AgentEntity(
            id = "custom_abc_123",
            name = "MyAgent",
            icon = "🤖",
            description = "My custom agent",
            identity = "You are MyAgent. Be terse.",
            toolsAllowed = "web_search",
            preferredModel = "ollama:qwen2:1.5b:cloud",
            memoryScope = "agent:myagent",
        )
        val viewModel = makeViewModel()
        advanceUntilIdle()

        viewModel.setActiveAgent(customAgent)
        val state = viewModel.state.value

        assertEquals(customAgent.id, state.activeAgentId)
        assertEquals(customAgent, state.activeAgent)
        assertEquals(customAgent.preferredModel, state.activeModel)
        assertTrue("got ${state.conversation.systemPrompt}", state.conversation.systemPrompt?.contains("MyAgent") == true)
    }

    @Test
    fun `setActiveAgent uses real agent id not synthetic name id`() = runTest(dispatcher) {
        // This catches the bug where id was synthesized as agent_${name}
        val agent = AgentEntity(
            id = "user_created_42",
            name = "user_created_42",
            icon = "🤖",
            description = "d",
            identity = "id",
            toolsAllowed = "",
        )
        val viewModel = makeViewModel()
        advanceUntilIdle()

        viewModel.setActiveAgent(agent)

        assertEquals("user_created_42", viewModel.state.value.activeAgentId)
    }

    @Test
    fun `setActiveAgent selects matching builtin agent`() = runTest(dispatcher) {
        val viewModel = makeViewModel()
        advanceUntilIdle()

        val researcher = viewModel.state.value.availableAgents.find { it.id == "agent_researcher" }!!
        viewModel.setActiveAgent(researcher)

        val state = viewModel.state.value
        assertEquals("agent_researcher", state.activeAgentId)
        assertNotNull(state.activeAgent)
        assertEquals("agent_researcher", state.activeAgent?.id)
    }

    @Test
    fun `newConversation clears active agent and deep mode active`() = runTest(dispatcher) {
        val viewModel = makeViewModel()
        advanceUntilIdle()
        viewModel.setActiveAgent(AgentEntity(
            id = "a1",
            name = "Temp",
            icon = "🤖",
            description = "d",
            identity = "id",
            toolsAllowed = "",
        ))
        viewModel.toggleDeepMode() // sets deepModeActive on next send, but also enabled flag

        viewModel.newConversation()
        val state = viewModel.state.value

        assertNull(state.activeAgent)
        assertNull(state.activeAgentId)
        assertEquals(false, state.deepModeActive)
    }

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
            agentStore = agentStore,
        )
    }
}
