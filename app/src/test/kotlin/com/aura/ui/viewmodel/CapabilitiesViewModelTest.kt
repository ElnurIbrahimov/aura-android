package com.aura.ui.viewmodel

import android.app.Application
import com.aura.capabilities.CapabilityKind
import com.aura.capabilities.CapabilityProvider
import com.aura.capabilities.CapabilityRegistry
import com.aura.hands.HandDao
import com.aura.kg.KnowledgeGraphRepository
import com.aura.memory.MemoryStore
import com.aura.proactive.ProactiveEventBus
import com.aura.proactive.ProactiveEvents
import com.aura.skills.SkillsStore
import com.aura.tasks.ReminderDao
import com.aura.tasks.TaskDao
import com.aura.tools.CalendarReadTool
import com.aura.agent.ToolRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CapabilitiesViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val capabilityRegistry = mockk<CapabilityRegistry>(relaxed = true)
    private val providerKeys = mockk<com.aura.providers.ProviderKeys>(relaxed = true)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        // No third-party keys unless a test says otherwise.
        coEvery { providerKeys.keyForAwaiting(any()) } returns null
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): CapabilitiesViewModel {
        return CapabilitiesViewModel(
            application = mockk(relaxed = true),
            capabilityRegistry = capabilityRegistry,
            providerKeys = providerKeys,
        )
    }

    @Test
    fun `shows all capability kinds`() = runTest(dispatcher) {
        every { capabilityRegistry.configuredForKind(any()) } returns emptyList()
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(CapabilityKind.entries.size, vm.state.value.size)
    }

    @Test
    fun `marks configured kinds active with provider label`() = runTest(dispatcher) {
        val mockProvider = mockk<CapabilityProvider>()
        every { mockProvider.prefix } returns "stability"
        every { capabilityRegistry.configuredForKind(any()) } returns emptyList()
        every { capabilityRegistry.configuredForKind(CapabilityKind.ImageGeneration) } returns listOf(mockProvider)

        val vm = viewModel()
        advanceUntilIdle()

        val imageCard = vm.state.value.first { it.kind == CapabilityKind.ImageGeneration }
        assertTrue(imageCard.isConfigured)
        assertEquals("Stability AI", imageCard.providerLabel)
    }

    // ── Backends the CapabilityRegistry does not know about ─────────────
    //
    // The registry only sees providers bound in CapabilityModule. The tools
    // that actually run read their own keys, and the screen used to report
    // the registry's view, so it contradicted what the app could really do.

    @Test
    fun `web search reports Tavily when only its key is set`() = runTest(dispatcher) {
        // The reported bug exactly: Tavily configured and working in chat,
        // while the Capabilities screen said "Not configured" because the
        // registry only knows Exa and Jina.
        every { capabilityRegistry.configuredForKind(any()) } returns emptyList()
        coEvery { providerKeys.keyForAwaiting("tavily") } returns "tvly-abc"

        val vm = viewModel()
        advanceUntilIdle()

        val search = vm.state.value.first { it.kind == CapabilityKind.WebSearch }
        assertTrue(search.isConfigured)
        assertEquals("Tavily", search.providerLabel)
    }

    @Test
    fun `web search falls back to DuckDuckGo with no keys at all`() = runTest(dispatcher) {
        // WebSearchTool ends at DuckDuckGo, which needs no key, so web
        // search can never actually be unavailable.
        every { capabilityRegistry.configuredForKind(any()) } returns emptyList()

        val vm = viewModel()
        advanceUntilIdle()

        val search = vm.state.value.first { it.kind == CapabilityKind.WebSearch }
        assertTrue(search.isConfigured)
        assertEquals("DuckDuckGo", search.providerLabel)
    }

    @Test
    fun `web search prefers Tavily over Brave, matching the tool order`() = runTest(dispatcher) {
        every { capabilityRegistry.configuredForKind(any()) } returns emptyList()
        coEvery { providerKeys.keyForAwaiting("tavily") } returns "tvly-abc"
        coEvery { providerKeys.keyForAwaiting("brave") } returns "brave-abc"

        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(
            "Tavily",
            vm.state.value.first { it.kind == CapabilityKind.WebSearch }.providerLabel,
        )
    }

    @Test
    fun `transcription reports Whisper when an OpenAI key is set`() = runTest(dispatcher) {
        // No provider is bound for Transcription at all, so this row read
        // "Not configured" permanently regardless of any key.
        every { capabilityRegistry.configuredForKind(any()) } returns emptyList()
        coEvery { providerKeys.keyForAwaiting("openai") } returns "sk-abc"

        val vm = viewModel()
        advanceUntilIdle()

        val transcription = vm.state.value.first { it.kind == CapabilityKind.Transcription }
        assertTrue(transcription.isConfigured)
        assertEquals("OpenAI Whisper", transcription.providerLabel)
    }

    @Test
    fun `transcription stays unconfigured with no OpenAI or Groq key`() = runTest(dispatcher) {
        every { capabilityRegistry.configuredForKind(any()) } returns emptyList()

        val vm = viewModel()
        advanceUntilIdle()

        assertTrue(!vm.state.value.first { it.kind == CapabilityKind.Transcription }.isConfigured)
    }

    @Test
    fun `image generation falls back to the keyless Pollinations endpoint`() = runTest(dispatcher) {
        every { capabilityRegistry.configuredForKind(any()) } returns emptyList()

        val vm = viewModel()
        advanceUntilIdle()

        val image = vm.state.value.first { it.kind == CapabilityKind.ImageGeneration }
        assertTrue(image.isConfigured)
        assertEquals("Pollinations", image.providerLabel)
    }

    @Test
    fun `text to speech always has the device engine`() = runTest(dispatcher) {
        every { capabilityRegistry.configuredForKind(any()) } returns emptyList()

        val vm = viewModel()
        advanceUntilIdle()

        val tts = vm.state.value.first { it.kind == CapabilityKind.TextToSpeech }
        assertTrue(tts.isConfigured)
        assertEquals("On-device", tts.providerLabel)
    }

    @Test
    fun `video and 3D stay unconfigured — they have no fallback`() = runTest(dispatcher) {
        every { capabilityRegistry.configuredForKind(any()) } returns emptyList()

        val vm = viewModel()
        advanceUntilIdle()

        assertTrue(!vm.state.value.first { it.kind == CapabilityKind.VideoGeneration }.isConfigured)
        assertTrue(!vm.state.value.first { it.kind == CapabilityKind.World3DGeneration }.isConfigured)
    }
}
