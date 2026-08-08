package com.aura.agent

import com.aura.hands.HandRepository
import com.aura.kg.ConversationKgExtractor
import com.aura.memory.MemoryStore
import com.aura.profile.UserProfileStore
import com.aura.providers.ChatOptions
import com.aura.providers.ModelCatalogRepository
import com.aura.providers.ProviderMessage
import com.aura.taste.TasteEngine
import com.aura.world.BeliefDao
import com.aura.emotion.EmotionEngine
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryAugmentedAgenticLoopAgentPersonalityTest {
    @Test
    fun `loop with activeAgentId prepends agent identity and personality`() = runTest {
        val brain: Brain = mockk(relaxed = true)
        val toolRegistry: ToolRegistry = mockk(relaxed = true)
        val toolExecutor: ToolExecutor = mockk(relaxed = true)
        val memoryStore: MemoryStore = mockk(relaxed = true)
        val kgExtractor: ConversationKgExtractor = mockk(relaxed = true)
        val userProfileStore: UserProfileStore = mockk(relaxed = true)
        val handRepository: HandRepository = mockk(relaxed = true)
        val providerRegistry = mockk<com.aura.providers.ProviderRegistry>(relaxed = true)
        val conversationCompactor = mockk<com.aura.agent.ConversationCompactor>(relaxed = true)
        val modelCatalogRepository = mockk<ModelCatalogRepository>(relaxed = true)
        val beliefDao = mockk<BeliefDao>(relaxed = true)
        val emotionEngine = mockk<EmotionEngine>(relaxed = true)
        val agentStore = mockk<AgentStore>(relaxed = true)
        val tasteEngine = mockk<TasteEngine>(relaxed = true)
        val traceSink = mockk<com.aura.agent.runtime.TraceSink>(relaxed = true)

        every { toolRegistry.definitions() } returns emptyList()
        every { toolRegistry.get("delegate_to_agent") } returns null
        coEvery { toolExecutor.execute(any(), any(), any()) } returns com.aura.agent.ToolResult.Ok("")
        coEvery { memoryStore.query(any(), any()) } returns emptyList()
        every { userProfileStore.getSystemPrompt() } returns ""
        coEvery { conversationCompactor.compactIfNeeded(any(), any()) } answers { firstArg() }
        every { emotionEngine.moodString() } returns "neutral"
        coEvery { tasteEngine.getTasteContext(any()) } returns ""

        val agent = AgentEntity(
            id = "agent_researcher",
            name = "Researcher",
            icon = "",
            description = "",
            identity = "You are a precise research agent.",
            toolsAllowed = "",
            personalityJson = "{}"
        )
        coEvery { agentStore.byId("agent_researcher") } returns agent

        val capturedSystem = mutableListOf<List<ProviderMessage>>()
        coEvery {
            brain.stream(
                model = any(),
                messages = capture(capturedSystem),
                tools = any(),
                options = any(),
            )
        } returns flowOf(BrainChunk.Text("hello"))

        val loop = MemoryAugmentedAgenticLoop(
            brain = brain,
            toolRegistry = toolRegistry,
            toolExecutor = toolExecutor,
            memoryStore = memoryStore,
            kgExtractor = kgExtractor,
            userProfileStore = userProfileStore,
            handRepository = handRepository,
            providerRegistry = providerRegistry,
            conversationCompactor = conversationCompactor,
            modelCatalogRepository = modelCatalogRepository,
            beliefDao = beliefDao,
            emotionEngine = emotionEngine,
            agentStore = agentStore,
            tasteEngine = tasteEngine,
            traceSink = traceSink,
        )

        val conversation = Conversation().addUser("hi")
        loop.run(
            model = "ollama:qwen2:1.5b",
            conversation = conversation,
            options = ChatOptions(),
            agentId = "agent_researcher",
        ).toList()

        val systemMessages = capturedSystem.mapNotNull { it.firstOrNull { m -> m.role == ProviderMessage.Role.system }?.content }
        println("CAPTURED_SYSTEM=${systemMessages.joinToString(" | ")}")
        assertTrue("identity not in system messages: $systemMessages", systemMessages.any { it.contains("precise research agent") })
    }
}
