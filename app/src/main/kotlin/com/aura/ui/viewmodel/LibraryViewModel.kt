package com.aura.ui.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.library.LibraryItem
import com.aura.library.LibraryKind
import com.aura.library.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Marked [Immutable] so Compose skips on `equals` instead of identity.
 *
 * Same reasoning as every other state class here: this is republished as a fresh object on
 * each change, and under strong skipping an unstable one recomposes the screen whether or
 * not anything it reads has changed. All properties are `val` and the list is replaced
 * through `copy()`, never mutated.
 */
@Immutable
data class LibraryUiState(
    val items: List<LibraryItem> = emptyList(),
    val filter: LibraryKind? = null,
    val loading: Boolean = true,
) {
    /** Only the categories actually present, so the row never offers an empty filter. */
    val availableKinds: List<LibraryKind>
        get() = items.map { it.kind }.distinct().sortedBy { it.ordinal }
}

/**
 * Everything Aura has made, in one place.
 *
 * Holds the unfiltered list and filters in memory rather than re-querying per chip: the
 * three sources are read once, the whole set is small enough to hold, and a chip that
 * triggers three database reads feels like a chip that does not work.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: LibraryRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(LibraryUiState())
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    private var everything: List<LibraryItem> = emptyList()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            everything = repository.all()
            _state.update { it.copy(items = applyFilter(it.filter), loading = false) }
        }
    }

    fun setFilter(kind: LibraryKind?) {
        _state.update { it.copy(filter = kind, items = applyFilter(kind)) }
    }

    private fun applyFilter(kind: LibraryKind?): List<LibraryItem> =
        if (kind == null) everything else everything.filter { it.kind == kind }
}
