package com.aura.ui.screens.onboarding

import com.aura.FirstRunGate
import com.aura.data.UserPreferences
import com.aura.providers.ModelCatalog
import com.aura.providers.ModelCatalogRepository
import com.aura.providers.ProviderKeys
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
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
class OnboardingViewModelTest {

    private lateinit var dispatcher: TestDispatcher
    private lateinit var firstRunGate: FirstRunGate
    private lateinit var providerKeys: ProviderKeys
    private lateinit var catalogRepository: ModelCatalogRepository
    private lateinit var userPreferences: UserPreferences

    @Before
    fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)

        firstRunGate = mockk(relaxed = true)
        providerKeys = mockk(relaxed = true)
        catalogRepository = mockk(relaxed = true)
        userPreferences = mockk(relaxed = true)

        coEvery { providerKeys.awaitLoaded() } coAnswers { }
        every { providerKeys.keyFor(any()) } returns ""
        every { catalogRepository.catalog } returns MutableStateFlow(ModelCatalog(emptyMap(), emptyList()))
        every { userPreferences.defaultModel } returns flowOf("")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `starts at Intro step`() = runTest(dispatcher) {
        val vm = OnboardingViewModel(firstRunGate, providerKeys, catalogRepository, userPreferences)
        advanceUntilIdle()
        assertEquals(OnboardingStep.Intro, vm.state.value.step)
    }

    @Test
    fun `next from Intro moves to Provider`() = runTest(dispatcher) {
        val vm = OnboardingViewModel(firstRunGate, providerKeys, catalogRepository, userPreferences)
        vm.next()
        assertEquals(OnboardingStep.Provider, vm.state.value.step)
    }

    @Test
    fun `updateKeyDraft records draft for known provider`() = runTest(dispatcher) {
        val vm = OnboardingViewModel(firstRunGate, providerKeys, catalogRepository, userPreferences)
        vm.updateKeyDraft("openai", "sk-test")
        assertEquals("sk-test", vm.state.value.keyDrafts["openai"])
        assertEquals(OnboardingCredentialStatus.Draft, vm.state.value.credentialStatus["openai"])
    }

    @Test
    fun `saveAndTest requires non-blank draft`() = runTest(dispatcher) {
        val vm = OnboardingViewModel(firstRunGate, providerKeys, catalogRepository, userPreferences)
        vm.saveAndTest("openai")
        assertEquals(OnboardingCredentialStatus.Invalid, vm.state.value.credentialStatus["openai"])
        assertEquals("Enter an API key first.", vm.state.value.providerMessages["openai"])
    }

    @Test
    fun `skip marks first run complete`() = runTest(dispatcher) {
        coEvery { firstRunGate.markComplete() } just runs
        var called = false
        val vm = OnboardingViewModel(firstRunGate, providerKeys, catalogRepository, userPreferences)
        vm.skip { called = true }
        advanceUntilIdle()
        coVerify { firstRunGate.markComplete() }
        assertTrue(called)
    }
}
