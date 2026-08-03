package com.aura.ui.screens.council

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