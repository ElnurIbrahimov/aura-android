package com.aura.ui.evolution

import com.aura.data.UserPreferences
import com.aura.evolution.EvolutionApplySaga
import com.aura.evolution.EvolutionDomain
import com.aura.evolution.EvolutionProposalDao
import com.aura.evolution.EvolutionProposalEntity
import com.aura.evolution.EvolutionProposalStore
import com.aura.evolution.EvolutionRollbackManager
import com.aura.evolution.EvolutionSettingsDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvolutionInboxViewModelTest {

    private val proposalDao = mockk<EvolutionProposalDao>(relaxed = true)
    private val proposalStore = mockk<EvolutionProposalStore>(relaxed = true)
    private val settingsDao = mockk<EvolutionSettingsDao>(relaxed = true)
    private val rollbackManager = mockk<EvolutionRollbackManager>(relaxed = true)
    private val userPreferences = mockk<UserPreferences>(relaxed = true)
    private val applySaga = mockk<EvolutionApplySaga>(relaxed = true)

    @Before
    fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After
    fun tearDown() { Dispatchers.resetMain() }

    private fun makeVm(): EvolutionInboxViewModel {
        coEvery { proposalDao.open() } returns emptyList()
        coEvery { settingsDao.get(any()) } returns null
        every { userPreferences.evolutionOnboardingShown } returns flowOf(true)
        return EvolutionInboxViewModel(
            proposalDao, proposalStore, settingsDao, rollbackManager,
            userPreferences, applySaga,
        )
    }

    private fun makeProposal(id: String = "p1") = EvolutionProposalEntity(
        id = id,
        domain = "memory",
        action = "CREATE_SKILL",
        targetId = "",
        title = "Test",
    )

    @Test
    fun `load populates proposals from DAO`() = runTest {
        coEvery { proposalDao.open() } returns listOf(makeProposal())
        every { userPreferences.evolutionOnboardingShown } returns flowOf(true)
        coEvery { settingsDao.get(any()) } returns null
        val vm = EvolutionInboxViewModel(
            proposalDao, proposalStore, settingsDao, rollbackManager,
            userPreferences, applySaga,
        )

        vm.load()

        assertEquals(1, vm.proposals.value.size)
        assertEquals("p1", vm.proposals.value[0].id)
    }

    @Test
    fun `load populates settings for all domains`() = runTest {
        val vm = makeVm()

        vm.load()

        assertEquals(EvolutionDomain.entries.size, vm.settings.value.size)
    }

    @Test
    fun `load shows onboarding when not shown and no proposals`() = runTest {
        every { userPreferences.evolutionOnboardingShown } returns flowOf(false)
        coEvery { proposalDao.open() } returns emptyList()
        coEvery { settingsDao.get(any()) } returns null
        val vm = EvolutionInboxViewModel(
            proposalDao, proposalStore, settingsDao, rollbackManager,
            userPreferences, applySaga,
        )

        vm.load()

        assertTrue(vm.showOnboarding.value)
    }

    @Test
    fun `load hides onboarding when already shown`() = runTest {
        every { userPreferences.evolutionOnboardingShown } returns flowOf(true)
        val vm = makeVm()

        vm.load()

        assertFalse(vm.showOnboarding.value)
    }

    @Test
    fun `dismissOnboarding sets preference and hides`() = runTest {
        val vm = makeVm()
        vm.load()

        vm.dismissOnboarding()

        coVerify { userPreferences.setEvolutionOnboardingShown(true) }
        assertFalse(vm.showOnboarding.value)
    }

    @Test
    fun `rollback calls rollbackManager and reloads`() = runTest {
        val vm = makeVm()
        vm.load()

        vm.rollback("p1")

        coVerify { rollbackManager.rollback("p1") }
    }

    @Test
    fun `reject calls proposalStore reject and reloads`() = runTest {
        val vm = makeVm()
        vm.load()

        vm.reject("p1", "not needed")

        coVerify { proposalStore.reject("p1", "not needed") }
    }

    @Test
    fun `approve calls proposalStore approve and applySaga`() = runTest {
        val proposal = makeProposal()
        coEvery { proposalStore.getById("p1") } returns proposal
        coEvery { applySaga.apply(proposal) } returns EvolutionApplySaga.ApplyResult.Ok("p1", "done")
        val vm = makeVm()
        vm.load()

        vm.approve("p1")

        coVerify { proposalStore.approve("p1") }
        coVerify { applySaga.apply(proposal) }
    }

    @Test
    fun `approve with apply failure marks apply failed`() = runTest {
        val proposal = makeProposal()
        coEvery { proposalStore.getById("p1") } returns proposal
        coEvery { applySaga.apply(proposal) } returns EvolutionApplySaga.ApplyResult.Error("p1", "bad JSON")
        val vm = makeVm()
        vm.load()

        vm.approve("p1")

        coVerify { proposalStore.markApplyFailed("p1", "bad JSON") }
    }

    @Test
    fun `setDomainEnabled upserts setting`() = runTest {
        val vm = makeVm()
        vm.load()

        vm.setDomainEnabled(EvolutionDomain.MEMORY, false)

        coVerify { settingsDao.upsert(match { it.enabled == false && it.domain == "MEMORY" }) }
    }
}