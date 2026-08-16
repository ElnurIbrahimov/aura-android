package com.aura.ui.viewmodel

import com.aura.creative.CreativeArtifactEntity
import com.aura.creative.CreativeArtifactStore
import com.aura.creative.CreativeProject
import com.aura.creative.CreativeProjectStore
import com.aura.creative.CreativeRevisionEntity
import com.aura.creative.StoryBeat
import com.aura.creative.WorldBible
import io.mockk.coEvery
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * That the prose actually reaches the document.
 *
 * Named to echo `CraftWiringTest`, which records why this class exists:
 * `SceneContextBuilder` documented eight context sections while its only caller
 * supplied six, for months, because the tests proved the *format* and nobody
 * tested the *wire*. `ManuscriptCompilerTest` covers the format thoroughly and
 * would pass just as happily against a reader that returned an empty map.
 *
 * So these assert on the scene text itself. They fail if the reader resolves the
 * wrong id, calls the wrong store method, or hands the compiler nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManuscriptExportWiringTest {

    private val dispatcher = StandardTestDispatcher()
    private val store = mockk<CreativeProjectStore>(relaxed = true)
    private val artifactStore = mockk<CreativeArtifactStore>(relaxed = true)

    private fun beats() = listOf(
        StoryBeat(id = "b1", title = "Arrival", status = "drafted", artifactId = "art1", revisionId = "rev1"),
        StoryBeat(id = "b2", title = "The lantern room", status = "drafted", artifactId = "art2", revisionId = "rev2"),
    )

    private val project = CreativeProject(
        "p1", "The Lighthouse", "A keeper who cannot swim", "literary", "spare",
        WorldBible(outline = beats()), "novel", 0, 1L, 1L,
    )

    private fun revision(id: String, text: String) = CreativeRevisionEntity(
        id = id,
        artifactId = "art-$id",
        branchId = "main",
        contentText = text,
    )

    private fun newViewModel() = CreativeStudioViewModel(
        store, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        artifactStore, mockk(relaxed = true), mockk(relaxed = true),
        mockk(relaxed = true),
        com.aura.creative.longform.LongformProgressBus(),
        mockk(relaxed = true),
        com.aura.creative.livingworld.WorldSeeder(),
        com.aura.creative.livingworld.WorldTickBus(),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
        mockk(relaxed = true),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { store.observeAll() } returns flowOf(listOf(project))
        coEvery { store.get("p1") } returns project
        coEvery { artifactStore.forProjectByKind("p1", "scene") } returns
            listOf(mockk<CreativeArtifactEntity>(relaxed = true), mockk(relaxed = true))
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    /**
     * The assertion that matters. Not the headings, not the order — the prose.
     */
    @Test
    fun `the exported markdown contains the scene text the store holds`() = runTest {
        coEvery { artifactStore.currentRevision("art1") } returns revision("rev1", "She reached the lighthouse at dusk.")
        coEvery { artifactStore.currentRevision("art2") } returns revision("rev2", "The lantern had not been lit in forty years.")

        val vm = newViewModel()
        vm.loadProject("p1")
        advanceUntilIdle()
        val md = vm.exportManuscript()

        assertTrue(md != null, "a project with two drafted scenes must export something")
        assertTrue(md!!.contains("She reached the lighthouse at dusk."), md)
        assertTrue(md.contains("The lantern had not been lit in forty years."), md)
    }

    /**
     * `currentContent` would answer with a 200-character `previewText` here and
     * the document would carry a stub that reads like a finished scene. Reading
     * through `currentRevision` makes the gap visible instead.
     */
    @Test
    fun `a scene whose revision does not resolve is marked, not silently stubbed`() = runTest {
        coEvery { artifactStore.currentRevision("art1") } returns revision("rev1", "She reached the lighthouse at dusk.")
        coEvery { artifactStore.currentRevision("art2") } returns null

        val vm = newViewModel()
        vm.loadProject("p1")
        advanceUntilIdle()
        val md = vm.exportManuscript()!!

        assertTrue(md.contains("*[scene text unavailable]*"), md)
        assertTrue(md.contains("She reached the lighthouse at dusk."), "the healthy scene still exports")
    }

    /** The reader must never call the fallback path, whatever else it does. */
    @Test
    fun `it never reads through currentContent`() = runTest {
        coEvery { artifactStore.currentRevision(any()) } returns revision("rev1", "prose")

        val vm = newViewModel()
        vm.loadProject("p1")
        advanceUntilIdle()
        vm.exportManuscript()

        io.mockk.coVerify(exactly = 0) { artifactStore.currentContent(any()) }
    }

    @Test
    fun `with no project open it exports nothing`() = runTest {
        val vm = newViewModel()
        advanceUntilIdle()

        assertNull(vm.exportManuscript())
    }
}
