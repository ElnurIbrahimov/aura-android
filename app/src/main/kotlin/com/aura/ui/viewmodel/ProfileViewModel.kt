package com.aura.ui.viewmodel

import androidx.compose.runtime.Immutable
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aura.profile.UserProfile
import com.aura.profile.UserProfileStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
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
data class ProfileUiState(
    val name: String = "",
    val traits: List<String> = emptyList(),
    val facts: List<String> = emptyList(),
)

/**
 * One-shot feedback events surfaced to the screen as snackbars. Each
 * mutating action emits exactly one event so the user sees confirmation
 * (or a duplicate-guard message) instead of a silent UI update.
 */
sealed interface ProfileEvent {
    data class Saved(val message: String) : ProfileEvent
    data class Removed(val message: String) : ProfileEvent
    data object Cleared : ProfileEvent
    data object Duplicate : ProfileEvent
}

@HiltViewModel
class ProfileViewModel @Inject constructor(
    app: Application,
    private val store: UserProfileStore,
) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    private val _events = Channel<ProfileEvent>(capacity = Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            store.profile.collect { profile ->
                _state.value = profile.toUiState()
            }
        }
    }

    fun setName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val current = store.profile.value
            store.update(name = trimmed)
            _events.send(ProfileEvent.Saved("Name updated"))
        }
    }

    fun addTrait(trait: String) {
        val trimmed = trait.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val current = store.profile.value
            if (trimmed in current.traits) {
                _events.send(ProfileEvent.Duplicate)
                return@launch
            }
            store.update(traits = current.traits + trimmed)
            _events.send(ProfileEvent.Saved("Trait added"))
        }
    }

    fun removeTrait(trait: String) {
        viewModelScope.launch {
            val current = store.profile.value
            store.update(traits = current.traits.filter { it != trait })
            _events.send(ProfileEvent.Removed("Trait removed"))
        }
    }

    fun addFact(fact: String) {
        val trimmed = fact.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val current = store.profile.value
            if (trimmed in current.facts) {
                _events.send(ProfileEvent.Duplicate)
                return@launch
            }
            store.update(facts = current.facts + trimmed)
            _events.send(ProfileEvent.Saved("Fact added"))
        }
    }

    fun removeFact(fact: String) {
        viewModelScope.launch {
            val current = store.profile.value
            store.update(facts = current.facts.filter { it != fact })
            _events.send(ProfileEvent.Removed("Fact removed"))
        }
    }

    fun clear() {
        viewModelScope.launch {
            store.update(
                name = "",
                traits = emptyList(),
                facts = emptyList(),
            )
            _events.send(ProfileEvent.Cleared)
        }
    }
}

private fun UserProfile.toUiState(): ProfileUiState = ProfileUiState(
    name = name ?: "",
    traits = traits,
    facts = facts,
)
