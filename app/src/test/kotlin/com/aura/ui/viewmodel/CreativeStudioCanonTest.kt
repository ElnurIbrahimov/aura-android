package com.aura.ui.viewmodel

import com.aura.creative.CanonFactDao
import com.aura.creative.CanonFactEntity
import com.aura.creative.ContinuityIssueDao
import com.aura.creative.ContinuityIssueEntity
import com.aura.creative.CreativeBranchStore
import com.aura.creative.CreativeProject
import com.aura.creative.CreativeProjectStore
import com.aura.creative.WorldBible
import io.mockk.coEvery
import io.mockk.coVerify
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The card exists so a contradiction is something the author sees rather than
 * something canon absorbed. A flag that never reaches the UI state is the same
 * as no flag.
 *
 * Constructed with **named** arguments, unlike its two neighbours. They pass 21
 * positional mocks, which means every future parameter is a counting exercise
 * with a silent-rebinding failure mode at the end of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreativeStudioCanonTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = mockk<CreativeProjectStore>(relaxed = true)
    private val branchStore = mockk<CreativeBranchStore>(relaxed = true)
    private val canonFactDao = mockk<CanonFactDao>(relaxed = true)
    private val continuityIssueDao = mockk<ContinuityIssueDao>(relaxed = true)

    private val project = CreativeProject(
        "p1", "Glass City", "", "fantasy", "haunting", WorldBible(overview = "Glass remembers"),
        "novel", 0, 1L, 1L,
    )

    private fun fact(id: String) = CanonFactEntity(
        id = id, projectId = "p1", branchId = "main",
        subjectType = "character", subjectId = "Mira",
        predicate = "location", valueJson = "\"Varn\"",
    )

    private fun issue(id: String) = ContinuityIssueEntity(
        id = id, projectId = "p1", branchId = "main",
        artifactId = "art9", category = "location", severity = "warning",
        message = "Mira: location was \"Varn\" and this scene says \"Kesh\".",
        status = "open",
    )

    private fun newViewModel() = CreativeStudioViewModel(
        store = store,
        engine = mockk(relaxed = true),
        council = mockk(relaxed = true),
        providerRegistry = mockk(relaxed = true),
        capabilityRouter = mockk(relaxed = true),
        modelRoleRouter = mockk(relaxed = true),
        proseCraftTools = mockk(relaxed = true),
        voiceCalibration = mockk(relaxed = true),
        tensionAnalyzer = mockk(relaxed = true),
        progressionTracker = mockk(relaxed = true),
        artifactStore = mockk(relaxed = true),
        branchStore = branchStore,
        brain = mockk(relaxed = true),
        longformRunStore = mockk(relaxed = true),
        longformProgressBus = com.aura.creative.longform.LongformProgressBus(),
        livingWorldStore = mockk(relaxed = true),
        worldSeeder = com.aura.creative.livingworld.WorldSeeder(),
        worldTickBus = com.aura.creative.livingworld.WorldTickBus(),
        worldNarrator = mockk(relaxed = true),
        appContext = mockk(relaxed = true),
        creativeAnalysisStore = mockk(relaxed = true),
        canonFactDao = canonFactDao,
        continuityIssueDao = continuityIssueDao,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { store.observeAll() } returns flowOf(listOf(project))
        coEvery { store.get("p1") } returns project
        coEvery { branchStore.createMainBranch("p1") } returns
            com.aura.creative.CreativeBranchEntity(id = "main", projectId = "p1", name = "main")
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `open conflicts and the fact count reach the ui state`() = runTest {
        coEvery { canonFactDao.activeForBranch("p1", "main") } returns listOf(fact("f1"), fact("f2"))
        every { continuityIssueDao.observeOpen("p1", "main") } returns flowOf(listOf(issue("i1")))

        val vm = newViewModel()
        vm.loadProject("p1")
        advanceUntilIdle()

        assertEquals(2, vm.state.value.canonFactCount)
        assertEquals(1, vm.state.value.openConflicts.size)
        assertTrue(vm.state.value.openConflicts.first().message.contains("Mira"))
    }

    /**
     * `intentional_exception`, not `dismissed`. The schema distinguishes them,
     * and "the author meant this" is a different fact from "the author is not
     * interested" the next time the same pair is compared.
     */
    @Test
    fun `dismissing a conflict resolves it as intentional`() = runTest {
        coEvery { canonFactDao.activeForBranch(any(), any()) } returns emptyList()
        every { continuityIssueDao.observeOpen(any(), any()) } returns flowOf(emptyList())

        val vm = newViewModel()
        vm.loadProject("p1")
        advanceUntilIdle()
        vm.dismissConflict("i1")
        advanceUntilIdle()

        coVerify { continuityIssueDao.resolve("i1", "intentional_exception", any(), "user") }
    }

    /**
     * `observeLongform`/`observeLivingWorld` are wired into two places:
     * `loadProject`, and a fallback inside `init`'s `store.observeAll()`
     * collector for when a project becomes selected some other way
     * (`createProject`, which sets `selectedProject` directly and never calls
     * `loadProject`). Canon has to start observing from both places too — a
     * project reached via the fallback path would otherwise show an empty
     * card not because there is nothing to show, but because nobody asked.
     */
    @Test
    fun `a project selected via the init fallback path also gets its canon observed`() = runTest {
        // flowOf can't model a second emission arriving after createProject
        // sets selectedProject, so a MutableStateFlow stands in for the
        // Room-backed Flow that would genuinely re-emit once the insert
        // createProject just made becomes visible to its own query.
        val projects = MutableStateFlow<List<CreativeProject>>(emptyList())
        every { store.observeAll() } returns projects
        coEvery { store.create(any(), any(), any(), any(), any()) } returns project
        coEvery { canonFactDao.activeForBranch("p1", "main") } returns listOf(fact("f1"), fact("f2"))
        every { continuityIssueDao.observeOpen("p1", "main") } returns flowOf(listOf(issue("i1")))

        val vm = newViewModel()
        // Selects the project the way createProject does — never loadProject.
        vm.createProject("Glass City", "Memory city", "fantasy", "haunting", "novel")
        advanceUntilIdle()
        projects.value = listOf(project)
        advanceUntilIdle()

        assertEquals(2, vm.state.value.canonFactCount)
        assertEquals(1, vm.state.value.openConflicts.size)
    }
}
