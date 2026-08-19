package com.aura.ui.screens.council

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.agent.council.Intervention
import com.aura.agent.forum.ForumEngine
import com.aura.agent.forum.ForumPostEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
data class CouncilUiState(
    val interventions: List<ForumPostEntity> = emptyList(),
    val selectedThread: List<ForumPostEntity> = emptyList(),
    val isLoading: Boolean = false,
)

@HiltViewModel
class CouncilViewModel @Inject constructor(
    private val forumEngine: ForumEngine,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CouncilUiState())
    val uiState: StateFlow<CouncilUiState> = _uiState.asStateFlow()

    init {
        loadInterventions()
    }

    fun loadInterventions() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val proposals = forumEngine.openProposals()
            val interventions = forumEngine.openInterventions()
            _uiState.value = CouncilUiState(
                interventions = interventions + proposals,
                isLoading = false,
            )
        }
    }

    fun openThread(threadId: kotlin.String) {
        viewModelScope.launch {
            val thread = forumEngine.getThread(threadId)
            _uiState.value = _uiState.value.copy(selectedThread = thread)
        }
    }

    fun closeThread() {
        _uiState.value = _uiState.value.copy(selectedThread = emptyList())
    }

    fun approveIntervention(postId: Long) {
        viewModelScope.launch {
            forumEngine.setStatus(postId, "approved")
            loadInterventions()
        }
    }

    fun rejectIntervention(postId: Long) {
        viewModelScope.launch {
            forumEngine.setStatus(postId, "rejected")
            loadInterventions()
        }
    }

    fun callEmergencyCouncil(topic: kotlin.String) {
        // Phase 5 — emergency council uses RunLifeCouncilTool from chat
        // For now, just navigate to chat with a pre-filled message
    }
}