package com.aura.ui.viewmodel

import com.aura.capabilities.CapabilityRouter
import com.aura.creative.CreativeCouncil
import com.aura.creative.CreativeEngine
import com.aura.creative.CreativeMode
import com.aura.creative.CreativeProject
import com.aura.creative.CreativeProjectStore
import com.aura.creative.WorldBible
import com.aura.providers.ProviderRegistry
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
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CreativeStudioViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = mockk<CreativeProjectStore>(relaxed = true)
    private val engine = mockk<CreativeEngine>()
    private val council: CreativeCouncil = mockk(relaxed = true)
    private val providerRegistry: ProviderRegistry = mockk(relaxed = true)
    private val capabilityRouter: CapabilityRouter = mockk(relaxed = true)
    private val modelRoleRouter: com.aura.providers.ModelRoleRouter = mockk(relaxed = true)
    private val project = CreativeProject(
        "p1", "Glass City", "", "fantasy", "haunting", WorldBible(overview = "Glass remembers"),
        "novel", 0, 1L, 1L,
    )

    private val longformRunStore: com.aura.creative.longform.LongformRunStore = mockk(relaxed = true)

    private fun newViewModel() = CreativeStudioViewModel(
        store, engine, council, providerRegistry, capabilityRouter, modelRoleRouter,
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true),
        longformRunStore,
        // A real progress bus: it is a plain StateFlow holder with no Android
        // dependency, and a relaxed mock would hand back a null Flow that the
        // long-form observer combines against.
        com.aura.creative.longform.LongformProgressBus(),
        mockk(relaxed = true),
        // A real seeder for the same reason as the progress bus: it is pure,
        // has no dependencies, and a mock would only hide what it produces.
        com.aura.creative.livingworld.WorldSeeder(),
        // A real bus for the same reason as the progress bus above: it is a
        // plain StateFlow holder, and a relaxed mock hands back a null Flow
        // that the living-world observer combines against.
        com.aura.creative.livingworld.WorldTickBus(),
        mockk(relaxed = true),
        mockk(relaxed = true),
        // creativeAnalysisStore — tension results are keyed to a revision, and
        // these tests drive the state machine rather than the storing.
        mockk(relaxed = true),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { store.observeAll() } returns flowOf(listOf(project))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `load selects project and exposes its world`() = runTest {
        coEvery { store.get("p1") } returns project
        val vm = newViewModel()
        vm.loadProject("p1")
        advanceUntilIdle()
        assertEquals("Glass City", vm.state.value.selectedProject?.name)
        assertEquals("Glass remembers", vm.state.value.selectedProject?.world?.overview)
    }

    @Test
    fun `create delegates project metadata and selects result`() = runTest {
        coEvery { store.create(any(), any(), any(), any(), any()) } returns project
        val vm = newViewModel()
        vm.createProject("Glass City", "Memory city", "fantasy", "haunting", "novel")
        advanceUntilIdle()
        coVerify { store.create("Glass City", "Memory city", "fantasy", "haunting", "novel") }
        assertEquals("p1", vm.state.value.createdProjectId)
    }

    @Test
    fun `generate streams output and clears busy state`() = runTest {
        coEvery { store.get("p1") } returns project
        every { engine.generate("p1", CreativeMode.DRAFT, "Opening", "") } returns flowOf("Glass ", "sang.")
        val vm = newViewModel()
        vm.loadProject("p1")
        advanceUntilIdle()
        vm.generate(CreativeMode.DRAFT, "Opening")
        advanceUntilIdle()
        assertEquals("Glass sang.", vm.state.value.output)
        assertFalse(vm.state.value.generating)
    }
}
