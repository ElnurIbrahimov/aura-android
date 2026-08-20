package com.aura.ui.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.tasks.ReminderEntity
import com.aura.tasks.ReminderStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
data class RemindersUiState(
    val upcoming: List<ReminderEntity> = emptyList(),
    val history: List<ReminderEntity> = emptyList(),
    val loading: Boolean = true,
    /**
     * Free-text search query. Applied client-side by the screen
     * (no Room round-trip) — case-insensitive substring match
     * against message and recurrence ("daily", "weekly", etc.).
     */
    val searchQuery: String = "",
)

@HiltViewModel
class RemindersViewModel @Inject constructor(
    private val store: ReminderStore,
) : ViewModel() {
    private val _state = MutableStateFlow(RemindersUiState())
    val state: StateFlow<RemindersUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(store.observeUpcoming(), store.observeHistory()) { upcoming, history ->
                RemindersUiState(upcoming = upcoming, history = history, loading = false)
            }.collect { snapshot -> _state.value = snapshot }
        }
    }

    fun create(message: String, triggerAt: Long, recurrence: String) {
        if (message.isBlank()) return
        viewModelScope.launch { store.create(message, triggerAt, recurrence) }
    }

    fun update(id: String, message: String, triggerAt: Long, recurrence: String) {
        if (message.isBlank()) return
        viewModelScope.launch { store.update(id, message, triggerAt, recurrence) }
    }

    fun cancel(id: String) {
        viewModelScope.launch { store.cancel(id) }
    }

    fun delete(id: String) {
        viewModelScope.launch { store.delete(id) }
    }

    fun clearHistory() {
        viewModelScope.launch { store.clearHistory() }
    }

    /**
     * Update the search query. The screen applies the filter
     * client-side so this is a cheap state update — the
     * upcoming/history lists stay in memory and the UI re-renders
     * with the new query without touching Room.
     */
    fun setSearchQuery(query: String) {
        _state.value = _state.value.copy(searchQuery = query)
    }
}
