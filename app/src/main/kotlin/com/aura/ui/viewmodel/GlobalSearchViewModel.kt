package com.aura.ui.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.search.GlobalSearchRepository
import com.aura.search.GlobalSearchResult
import com.aura.search.SearchCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Marked [Immutable] so Compose skips on `equals` instead of identity.
 *
 * Every one of these is republished as a fresh object on each change, so under strong
 * skipping an unstable state class meant a screen taking it recomposed on every publish
 * whether or not anything it read had changed. The promise holds: all properties are
 * `val`, and the collections are replaced through `copy()` — there is no `MutableList`
 * property anywhere in main sources and nothing mutates a state collection in place.
 *
 * It is a promise the compiler cannot check. A field that starts being mutated in place
 * will stop recomposing rather than fail to build.
 */
@Immutable
data class GlobalSearchUiState(
    val query: String = "",
    val results: List<GlobalSearchResult> = emptyList(),
    val searching: Boolean = false,
    /**
     * Category filter. Null means "all categories". The repository always
     * searches every source; filtering happens here so toggling a chip is
     * instant and doesn't re-run six queries.
     */
    val categoryFilter: SearchCategory? = null,
) {
    /** Results after applying [categoryFilter]. This is what the UI renders. */
    val visibleResults: List<GlobalSearchResult>
        get() = categoryFilter?.let { f -> results.filter { it.category == f } } ?: results

    /** Categories actually present in [results], for chip rendering. */
    val availableCategories: List<SearchCategory>
        get() = SearchCategory.entries.filter { c -> results.any { it.category == c } }
}

@HiltViewModel
class GlobalSearchViewModel @Inject constructor(
    private val repository: GlobalSearchRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(GlobalSearchUiState())
    val state: StateFlow<GlobalSearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(results = emptyList(), searching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(200) // debounce
            _state.update { it.copy(searching = true) }
            val results = repository.search(query)
            // Drop a filter that the new result set can't satisfy, so the
            // user isn't left staring at an empty list because a chip from
            // the previous query is still selected.
            _state.update { old ->
                val stillPresent = old.categoryFilter?.takeIf { f -> results.any { it.category == f } }
                old.copy(results = results, searching = false, categoryFilter = stillPresent)
            }
        }
    }

    fun onCategoryFilterChange(category: SearchCategory?) {
        _state.update { it.copy(categoryFilter = category) }
    }

    fun clear() {
        searchJob?.cancel()
        _state.update { GlobalSearchUiState() }
    }
}