package com.aura.ui.settings

import com.aura.agent.IdentityStore
import com.aura.data.UserPreferences
import com.aura.evolution.EvolutionDomain
import com.aura.evolution.EvolutionSafetyGuard
import com.aura.evolution.EvolutionSettingsStore
import com.aura.mcp.McpClientManager
import com.aura.providers.ModelCatalog
import com.aura.providers.ModelCatalogRepository
import com.aura.providers.ProviderCredentialState
import com.aura.providers.ProviderKeys
import com.aura.providers.ProviderRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * D4: [SettingsViewModel.setEvolutionAutoApply] persists `true` only for
 * domains the safety guard allows — SKILL can never be flagged for
 * auto-apply, no matter what the user toggles.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelEvolutionGuardTest {

    private val providerRegistry = mockk<ProviderRegistry>(relaxed = true)
    private val providerKeys = mockk<ProviderKeys>(relaxed = true)
    private val userPreferences = mockk<UserPreferences>(relaxed = true)
    private val identityStore = mockk<IdentityStore>(relaxed = true)
    private val modelCatalogRepository = mockk<ModelCatalogRepository>(relaxed = true)
    private val mcpClientManager = mockk<McpClientManager>(relaxed = true)
    private val evolutionSettingsStore = mockk<EvolutionSettingsStore>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { providerRegistry.configured() } returns emptyList()
        every { providerKeys.keyFor(any()) } returns null
        every { providerKeys.loaded } returns MutableStateFlow(true)
        every { providerKeys.credentialStates } returns MutableStateFlow(
            ProviderKeys.PREFIXES.associateWith { ProviderCredentialState.NotConfigured },
        )
        every { providerKeys.embeddingModel } returns ""
        every { modelCatalogRepository.catalog } returns MutableStateFlow(ModelCatalog(emptyMap(), emptyList()))
        // reload() reads all of these; stub them like SettingsViewModelAppLockTest does.
        every { userPreferences.defaultModel } returns flowOf("test:chat-model")
        every { userPreferences.visionModel } returns flowOf("")
        every { userPreferences.backgroundModel } returns flowOf("")
        every { userPreferences.deepModeModel } returns flowOf("")
        every { userPreferences.moaReferenceModels } returns flowOf(emptyList())
        every { userPreferences.moaAggregatorModel } returns flowOf("")
        every { userPreferences.firstRunComplete } returns flowOf(true)
        every { userPreferences.appLockEnabled } returns flowOf(false)
        every { userPreferences.morningBriefEnabled } returns flowOf(true)
        every { userPreferences.calendarMonitorEnabled } returns flowOf(true)
        every { userPreferences.themeMode } returns flowOf("system")
        every { userPreferences.customIdentity } returns flowOf("")
        every { userPreferences.specialistOverrides } returns flowOf("{}")
        every { userPreferences.ttsEnabled } returns flowOf(true)
        every { userPreferences.incognitoDefault } returns flowOf(false)
        every { userPreferences.lastSeenProactiveAt } returns flowOf(0L)
        every { userPreferences.morningBriefHour } returns flowOf(7)
        every { userPreferences.smtpHost } returns flowOf("")
        every { userPreferences.smtpPort } returns flowOf(587)
        every { userPreferences.smtpUsername } returns flowOf("")
        every { userPreferences.smtpPassword } returns flowOf("")
        every { userPreferences.smtpFrom } returns flowOf("")
        every { userPreferences.googleClientId } returns flowOf("")
        every { userPreferences.microsoftClientId } returns flowOf("")
        every { userPreferences.reasoningEnabled } returns flowOf(true)
        every { userPreferences.reasoningBudget } returns flowOf(32000)
        every { userPreferences.evolutionEnabled } returns flowOf(false)
        every { userPreferences.evolutionIntervalHours } returns flowOf(24)
        every { userPreferences.imageModel } returns flowOf("")
        every { userPreferences.videoModel } returns flowOf("")
        every { userPreferences.voiceModel } returns flowOf("")
        every { userPreferences.daemonEnabled } returns flowOf(false)
        // The Council section is wired into Settings now, so reload() reads these.
        // A relaxed MockK returns an EMPTY Flow, and first() on an empty flow throws
        // NoSuchElementException — which fails every test in the class, not just the
        // ones about councils.
        every { userPreferences.councilEnabled } returns flowOf(false)
        every { userPreferences.councilActivityLevel } returns flowOf(3)
        every { userPreferences.daemonIntervalMinutes } returns flowOf(UserPreferences.DEFAULT_DAEMON_INTERVAL_MINUTES)
        every { userPreferences.dreamEnabled } returns flowOf(false)
        every { userPreferences.decayEnabled } returns flowOf(true)
        every { userPreferences.smarterMemoryEnabled } returns flowOf(false)
        every { userPreferences.planningEnabled } returns flowOf(false)
        every { userPreferences.promptCachingEnabled } returns flowOf(true)
        every { userPreferences.screenControlEnabled } returns flowOf(false)
        every { userPreferences.appAwarenessEnabled } returns flowOf(false)
        every { userPreferences.placeLogEnabled } returns flowOf(false)
        every { userPreferences.projectLedgerEnabled } returns flowOf(true)
        every { userPreferences.dreamLastRunAt } returns flowOf(0L)
        every { userPreferences.dreamLastRunStats } returns flowOf("")
        every { userPreferences.mcpServersJson } returns flowOf("")
        every { userPreferences.triggersEnabled } returns flowOf(false)
        every { userPreferences.triggers } returns flowOf(emptyList())
        coEvery { identityStore.readCurrent() } returns ""
        coEvery { identityStore.hasOverride() } returns false
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(): SettingsViewModel {
        val customState = mockk<com.aura.providers.CustomEndpointState>(relaxed = true)
        every { customState.state } returns MutableStateFlow(Triple("", "", emptyList<String>()))
        every { customState.baseUrlFlow } returns MutableStateFlow("")
        val toolRegistry = mockk<com.aura.agent.ToolRegistry>(relaxed = true)
        every { toolRegistry.all() } returns emptyList()
        val toolPolicyStore = mockk<com.aura.agent.policy.ToolPolicyStore>(relaxed = true)
        every { toolPolicyStore.allPolicies } returns flowOf(emptyMap())
        return SettingsViewModel(
            providerRegistry,
            providerKeys,
            userPreferences,
            identityStore,
            modelCatalogRepository,
            customState,
            toolRegistry,
            toolPolicyStore,
            mockk<com.aura.providers.ModelRoleRouter>(relaxed = true),
            mcpClientManager,
            mockk<com.aura.mcp.McpToolBridge>(relaxed = true),
            mockk<com.aura.security.SecureDataStore>(relaxed = true),
            mockk<com.aura.dream.DreamConsolidationDao>(relaxed = true),
            mockk<com.aura.emotion.EmotionEngine>(relaxed = true),
            evolutionSettingsStore,
            EvolutionSafetyGuard(),
            mockk<com.aura.proactive.ProactiveEventDao>(relaxed = true),
            mockk<com.aura.integrations.OAuthFlow>(relaxed = true),
            mockk<com.aura.integrations.IntegrationTokenStore>(relaxed = true),
            mockk<android.content.Context>(relaxed = true),
        )
    }

    @Test
    fun `enabling auto-apply never persists true for the SKILL domain`() = runTest {
        val vm = newViewModel()

        vm.setEvolutionAutoApply(true)

        // SKILL is forced to false even though the user enabled the toggle…
        coVerify { evolutionSettingsStore.setAutoApplyApproved(EvolutionDomain.SKILL, false) }
        coVerify(exactly = 0) { evolutionSettingsStore.setAutoApplyApproved(EvolutionDomain.SKILL, true) }
        // …while guard-passing domains get true.
        coVerify { evolutionSettingsStore.setAutoApplyApproved(EvolutionDomain.MEMORY, true) }
        coVerify { evolutionSettingsStore.setAutoApplyApproved(EvolutionDomain.PROACTIVE, true) }
    }

    @Test
    fun `disabling auto-apply persists false for every domain`() = runTest {
        val vm = newViewModel()

        vm.setEvolutionAutoApply(false)

        for (domain in EvolutionDomain.entries) {
            coVerify { evolutionSettingsStore.setAutoApplyApproved(domain, false) }
        }
        coVerify(exactly = 0) { evolutionSettingsStore.setAutoApplyApproved(any(), true) }
    }
}
