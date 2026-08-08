package com.aura.agent

import com.aura.consciousness.DriveSignals
import com.aura.consciousness.IntrinsicMotivation
import com.aura.memory.MemoryStore
import com.aura.providers.FinishReason
import com.aura.providers.Provider
import com.aura.providers.ProviderRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Intrinsic-motivation wiring in the agentic loop:
 *
 * 1. `assess` receives REAL drive inputs from [DriveSignals] (pre-fix the
 *    loop hardcoded kgGapCount/lowConfidenceSkillCount/contradictionCount
 *    to 0, so only SOCIAL could ever rise).
 * 2. CURIOSITY is satisfied only by genuinely information-seeking tools
 *    (CURIOSITY_TOOLS membership), not by "any tool ran".
 * 3. SOCIAL is satisfied on every completed turn.
 *
 * Uses `runBlocking` (real time) because tool execution crosses
 * `runInterruptible(Dispatchers.IO)`, same as the other loop tests.
 */
class MemoryAugmentedAgenticLoopMotivationTest {

    /** Context whose filesDir is a real temp dir — see ConsciousnessLayerTest.ctx(). */
    private fun ctx(): android.content.Context {
        val dir = kotlin.io.path.createTempDirectory("aura-motivation-test").toFile().also { it.deleteOnExit() }
        return mockk<android.content.Context>(relaxed = true).also { every { it.filesDir } returns dir }
    }

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

    private fun okTool(name: String): Tool = Tool(
        name = name,
        description = "Test tool $name",
        risk = ToolRisk.READ_ONLY,
        parameters = com.aura.providers.ToolParameters(),
        execute = { _, _ -> ToolResult.Ok("result of $name") },
    )

    /** Brain scripted to call [toolName] once, then answer with final text. */
    private fun scriptedBrain(toolName: String): Brain {
        val brain = mockk<Brain>(relaxed = true)
        coEvery { brain.stream(any(), any(), any(), any()) } returnsMany listOf(
            flowOf(
                BrainChunk.ToolCallStart("tc1", toolName),
                BrainChunk.ToolCallDelta("tc1", "{}"),
                BrainChunk.ToolCallEnd("tc1", toolName, "{}"),
                BrainChunk.Finished(FinishReason.tool_calls.name),
            ),
            flowOf(
                BrainChunk.Text("Done."),
                BrainChunk.Finished(FinishReason.stop.name),
            ),
        )
        return brain
    }

    /** Canned drive-signal snapshot: 20 KG gaps, 2 contradictions, 1 weak skill. */
    private fun cannedSignals(): DriveSignals = mockk<DriveSignals>().also { ds ->
        coEvery { ds.get(any()) } returns DriveSignals.Snapshot(
            kgGapCount = 20,
            contradictionCount = 2,
            lowConfidenceSkillCount = 1,
            refreshedAt = System.currentTimeMillis(),
        )
    }

    private fun emotionEngineStub(): com.aura.emotion.EmotionEngine =
        mockk<com.aura.emotion.EmotionEngine>(relaxed = true).also { engine ->
            every { engine.moodString() } returns "neutral"
            every { engine.profile() } returns com.aura.emotion.ResponseProfile.NEUTRAL
            every { engine.applySampling(any()) } answers { firstArg() }
        }

    private fun buildLoop(
        brain: Brain,
        tools: List<Tool>,
        intrinsicMotivation: IntrinsicMotivation,
        driveSignals: DriveSignals,
    ): MemoryAugmentedAgenticLoop {
        val toolRegistry = ToolRegistry()
        tools.forEach { toolRegistry.register(it) }
        val executor = ToolExecutor(toolRegistry, context = mockk(relaxed = true))
        val memoryStore = mockk<MemoryStore>(relaxed = true)
        val kgExtractor = mockk<com.aura.kg.ConversationKgExtractor>(relaxed = true)
        val userProfileStore = mockk<com.aura.profile.UserProfileStore>(relaxed = true)
        val handRepository = mockk<com.aura.hands.HandRepository>(relaxed = true)
        every { userProfileStore.getSystemPrompt() } returns ""
        coEvery { handRepository.getEnabled() } returns emptyList()

        return MemoryAugmentedAgenticLoop(
            brain, toolRegistry, executor, memoryStore, kgExtractor,
            userProfileStore, handRepository, mockProviderRegistry(), passthroughCompactor(),
            emotionEngine = emotionEngineStub(),
            intrinsicMotivation = intrinsicMotivation,
            driveSignals = driveSignals,
        )
    }

    @Test
    fun `assess receives real drive-signal counts, not hardcoded zeros`() = runBlocking {
        val im = IntrinsicMotivation(ctx())
        val signals = cannedSignals()
        val loop = buildLoop(
            brain = scriptedBrain("calendar_read"),
            tools = listOf(okTool("calendar_read")),
            intrinsicMotivation = im,
            driveSignals = signals,
        )

        // Short message so the planning heuristic is skipped.
        loop.run(Conversation().addUser("cal now"), model = "test:model", maxSteps = 5)
            .collect { /* drain */ }

        coVerify(atLeast = 1) { signals.get(any()) }
        val drives = im.drives.value
        // CURIOSITY from kgGapCount=20 → intensity 20/20 = 1.0 (calendar_read
        // is not a curiosity tool, so nothing satisfied it back down).
        assertEquals(1.0f, drives[IntrinsicMotivation.DriveType.CURIOSITY]!!.intensity, 0.001f)
        assertTrue(
            "curiosity triggers should carry the canned gap count",
            drives[IntrinsicMotivation.DriveType.CURIOSITY]!!.triggers.any { "20" in it },
        )
        // COHERENCE from contradictionCount=2 → 2/3.
        assertEquals(2f / 3f, drives[IntrinsicMotivation.DriveType.COHERENCE]!!.intensity, 0.001f)
        // COMPETENCE from lowConfidenceSkillCount=1 → 1/5.
        assertEquals(0.2f, drives[IntrinsicMotivation.DriveType.COMPETENCE]!!.intensity, 0.001f)
    }

    @Test
    fun `curiosity tool satisfies CURIOSITY after the turn`() = runBlocking {
        val im = IntrinsicMotivation(ctx())
        val loop = buildLoop(
            brain = scriptedBrain("web_search"),
            tools = listOf(okTool("web_search")),
            intrinsicMotivation = im,
            driveSignals = cannedSignals(),
        )

        loop.run(Conversation().addUser("web now"), model = "test:model", maxSteps = 5)
            .collect { /* drain */ }

        // assess raised CURIOSITY to 1.0 (20 gaps), then the completed
        // web_search satisfied it back down to 0.1.
        val curiosity = im.drives.value[IntrinsicMotivation.DriveType.CURIOSITY]!!
        assertTrue(
            "web_search should satisfy CURIOSITY, intensity=${curiosity.intensity}",
            curiosity.intensity < 0.2f,
        )
    }

    @Test
    fun `non-curiosity tool leaves CURIOSITY untouched`() = runBlocking {
        val im = IntrinsicMotivation(ctx())
        val loop = buildLoop(
            brain = scriptedBrain("calendar_read"),
            tools = listOf(okTool("calendar_read")),
            intrinsicMotivation = im,
            driveSignals = cannedSignals(),
        )

        loop.run(Conversation().addUser("cal now"), model = "test:model", maxSteps = 5)
            .collect { /* drain */ }

        // Pre-fix, ANY completed tool satisfied CURIOSITY. A calendar read
        // is not information-seeking — the drive must stay where assess
        // put it (1.0 from 20 gaps).
        val curiosity = im.drives.value[IntrinsicMotivation.DriveType.CURIOSITY]!!
        assertTrue(
            "calendar_read must NOT satisfy CURIOSITY, intensity=${curiosity.intensity}",
            curiosity.intensity > 0.9f,
        )
    }

    @Test
    fun `every completed turn satisfies SOCIAL`() = runBlocking {
        val im = IntrinsicMotivation(ctx())
        val loop = buildLoop(
            brain = scriptedBrain("calendar_read"),
            tools = listOf(okTool("calendar_read")),
            intrinsicMotivation = im,
            driveSignals = cannedSignals(),
        )

        loop.run(Conversation().addUser("cal now"), model = "test:model", maxSteps = 5)
            .collect { /* drain */ }

        // Talking to the user IS social contact — SOCIAL is satisfied on
        // every completed turn regardless of which tools ran.
        val social = im.drives.value[IntrinsicMotivation.DriveType.SOCIAL]!!
        assertTrue(
            "completed turn should satisfy SOCIAL, intensity=${social.intensity}",
            social.intensity < 0.2f,
        )
    }
}
