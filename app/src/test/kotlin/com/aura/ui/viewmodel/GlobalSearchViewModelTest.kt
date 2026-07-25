package com.aura.ui.viewmodel

import com.aura.search.GlobalSearchRepository
import com.aura.search.GlobalSearchResult
import com.aura.search.SearchCategory
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers the category-filter behaviour added when global search moved off the
 * app-wide FAB and into Home. The filter lives in the ViewModel rather than
 * the repository so toggling a chip does not re-run six data-source queries.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GlobalSearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: GlobalSearchRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = mockk()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun result(id: String, category: SearchCategory) = GlobalSearchResult(
        id = id,
        title = "title-$id",
        subtitle = "sub-$id",
        category = category,
        route = "route/$id",
    )

    @Test
    fun `no filter shows every result`() = runTest(dispatcher) {
        coEvery { repository.search(any()) } returns listOf(
            result("a", SearchCategory.CONVERSATION),
            result("b", SearchCategory.MEMORY),
        )
        val vm = GlobalSearchViewModel(repository)

        vm.onQueryChange("kotlin")
        advanceUntilIdle()

        assertNull(vm.state.value.categoryFilter)
        assertEquals(2, vm.state.value.visibleResults.size)
    }

    @Test
    fun `filter narrows visible results without refetching`() = runTest(dispatcher) {
        coEvery { repository.search(any()) } returns listOf(
            result("a", SearchCategory.CONVERSATION),
            result("b", SearchCategory.MEMORY),
            result("c", SearchCategory.MEMORY),
        )
        val vm = GlobalSearchViewModel(repository)
        vm.onQueryChange("kotlin")
        advanceUntilIdle()

        vm.onCategoryFilterChange(SearchCategory.MEMORY)

        val visible = vm.state.value.visibleResults
        assertEquals(2, visible.size)
        assertEquals(listOf("b", "c"), visible.map { it.id })
        // The full result set is retained so clearing the chip is instant.
        assertEquals(3, vm.state.value.results.size)
    }

    @Test
    fun `available categories only lists ones present in results`() = runTest(dispatcher) {
        coEvery { repository.search(any()) } returns listOf(
            result("a", SearchCategory.CONVERSATION),
            result("b", SearchCategory.TASK),
        )
        val vm = GlobalSearchViewModel(repository)
        vm.onQueryChange("kotlin")
        advanceUntilIdle()

        assertEquals(
            listOf(SearchCategory.CONVERSATION, SearchCategory.TASK),
            vm.state.value.availableCategories,
        )
    }

    @Test
    fun `filter is dropped when a new query cannot satisfy it`() = runTest(dispatcher) {
        coEvery { repository.search("first") } returns listOf(result("a", SearchCategory.MEMORY))
        val vm = GlobalSearchViewModel(repository)
        vm.onQueryChange("first")
        advanceUntilIdle()
        vm.onCategoryFilterChange(SearchCategory.MEMORY)
        assertEquals(SearchCategory.MEMORY, vm.state.value.categoryFilter)

        // New query returns no memories. Keeping the stale chip would show an
        // empty list over a non-empty result set.
        coEvery { repository.search("second") } returns listOf(result("b", SearchCategory.TASK))
        vm.onQueryChange("second")
        advanceUntilIdle()

        assertNull(vm.state.value.categoryFilter)
        assertEquals(1, vm.state.value.visibleResults.size)
    }

    @Test
    fun `filter survives a new query that still has the category`() = runTest(dispatcher) {
        coEvery { repository.search("first") } returns listOf(result("a", SearchCategory.MEMORY))
        val vm = GlobalSearchViewModel(repository)
        vm.onQueryChange("first")
        advanceUntilIdle()
        vm.onCategoryFilterChange(SearchCategory.MEMORY)

        coEvery { repository.search("second") } returns listOf(
            result("b", SearchCategory.MEMORY),
            result("c", SearchCategory.TASK),
        )
        vm.onQueryChange("second")
        advanceUntilIdle()

        assertEquals(SearchCategory.MEMORY, vm.state.value.categoryFilter)
        assertEquals(listOf("b"), vm.state.value.visibleResults.map { it.id })
    }

    @Test
    fun `blank query clears results`() = runTest(dispatcher) {
        coEvery { repository.search(any()) } returns listOf(result("a", SearchCategory.MEMORY))
        val vm = GlobalSearchViewModel(repository)
        vm.onQueryChange("kotlin")
        advanceUntilIdle()

        vm.onQueryChange("")
        advanceUntilIdle()

        assertEquals(0, vm.state.value.results.size)
        assertEquals(false, vm.state.value.searching)
    }
}
