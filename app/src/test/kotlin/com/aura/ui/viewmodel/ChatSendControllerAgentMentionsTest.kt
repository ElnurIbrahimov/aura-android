package com.aura.ui.viewmodel

import com.aura.agent.AgentEntity
import com.aura.agent.Conversation
import com.aura.agent.ToolContext
import com.aura.agent.ToolExecutor
import com.aura.tools.DelegateToAgentTool
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSendControllerAgentMentionsTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private fun createController(
        agents: List<AgentEntity> = emptyList(),
        toolExecutor: ToolExecutor = mockk(relaxed = true),
    ): Pair<ChatSendController, MutableStateFlow<ChatUiState>> {
        val state = MutableStateFlow(
            ChatUiState(
                activeModel = "ollama:test",
                availableAgents = agents,
            )
        )
        val controller = ChatSendController(
            application = mockk(relaxed = true),
            state = state,
            loop = mockk(relaxed = true),
            userPreferences = mockk(relaxed = true),
            textToSpeech = mockk(relaxed = true),
            knowledgeGraphRepository = mockk(relaxed = true),
            toolExecutor = toolExecutor,
            delegateToAgentTool = mockk(relaxed = true),
            onSaveConversation = {},
            onKgNodeCountChanged = {},
            onFirstConversationComplete = {},
            extractCitations = { _, _ -> emptyList() },
            setErrorWithAutoDismiss = { _, _, _ -> },
            generateTitle = { it.take(20) },
            onError = {},
            onRunComplete = {},
        )
        return controller to state
    }

    @Test
    fun `extractAgentMentions finds known agents case-insensitively`() {
        val agents = listOf(
            AgentEntity(id = "agent_researcher", name = "researcher", icon = "", description = "", identity = "", toolsAllowed = "", memoryScope = "", personalityJson = ""),
            AgentEntity(id = "agent_coder", name = "coder", icon = "", description = "", identity = "", toolsAllowed = "", memoryScope = "", personalityJson = ""),
        )
        val text = "@Researcher find me papers. @coder write a helper."
        val mentions = text.extractAgentMentions(agents)
        assertEquals(2, mentions.size)
        assertEquals("agent_researcher", mentions[0].first)
        assertEquals("find me papers.", mentions[0].second)
        assertEquals("agent_coder", mentions[1].first)
        assertEquals("write a helper.", mentions[1].second)
    }

    @Test
    fun `extractAgentMentions ignores email addresses`() {
        val agents = listOf(
            AgentEntity(id = "agent_researcher", name = "researcher", icon = "", description = "", identity = "", toolsAllowed = "", memoryScope = "", personalityJson = ""),
        )
        val text = "Email me at foo@researcher.com about @researcher papers"
        val mentions = text.extractAgentMentions(agents)
        assertEquals(1, mentions.size)
        assertEquals("papers", mentions[0].second)
    }

    @Test
    fun `send with mention routes through ToolExecutor and inserts agent turn`() = runTest(testDispatcher) {
        val agents = listOf(
            AgentEntity(id = "agent_researcher", name = "researcher", icon = "", description = "", identity = "", toolsAllowed = "", memoryScope = "", personalityJson = ""),
        )
        val executor = mockk<ToolExecutor>()
        val slot = slot<String>()
        coEvery { executor.execute(capture(slot), any(), any()) } returns com.aura.agent.ToolResult.Ok("Here are 3 papers.")

        val (controller, state) = createController(agents, executor)
        state.value = state.value.copy(draft = "@researcher find papers on LLM agents")

        controller.runSend(this)

        assertEquals(1, state.value.conversation.turns.size)
        val turn = state.value.conversation.turns.first()
        assertEquals("@researcher find papers on LLM agents", turn.user)
        assertEquals("Here are 3 papers.", turn.assistant)
        assertEquals("agent_researcher", turn.agentId)
        assertEquals(false, state.value.streaming)
        assertEquals("delegate_to_agent", slot.captured)
        coVerify { executor.execute(any(), any(), any()) }
    }

    @Test
    fun `send without mention falls back to normal loop`() = runTest(testDispatcher) {
        val loop = mockk<com.aura.agent.MemoryAugmentedAgenticLoop>(relaxed = true)
        val state = MutableStateFlow(ChatUiState(activeModel = "ollama:test"))
        val controller = ChatSendController(
            application = mockk(relaxed = true),
            state = state,
            loop = loop,
            userPreferences = mockk(relaxed = true),
            textToSpeech = mockk(relaxed = true),
            knowledgeGraphRepository = mockk(relaxed = true),
            toolExecutor = mockk(relaxed = true),
            delegateToAgentTool = mockk(relaxed = true),
            onSaveConversation = {},
            onKgNodeCountChanged = {},
            onFirstConversationComplete = {},
            extractCitations = { _, _ -> emptyList() },
            setErrorWithAutoDismiss = { _, _, _ -> },
            generateTitle = { it.take(20) },
            onError = {},
            onRunComplete = {},
        )
        state.value = state.value.copy(draft = "hello")

        controller.runSend(this)

        assertEquals("hello", state.value.conversation.turns.first().user)
        assertNull(state.value.conversation.turns.first().agentId)
    }
}
