package com.aura.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.search.GlobalSearchRepository
import com.aura.search.GlobalSearchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GlobalSearchUiState(
    val query: String = "",
    val results: List<GlobalSearchResult> = emptyList(),
    val searching: Boolean = false,
)

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
            _state.update { it.copy(results = results, searching = false) }
        }
    }

    fun clear() {
        searchJob?.cancel()
        _state.update { GlobalSearchUiState() }
    }
}