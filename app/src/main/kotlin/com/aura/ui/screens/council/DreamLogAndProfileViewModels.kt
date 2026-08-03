package com.aura.ui.screens.council

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.agent.AgentStore
import com.aura.agent.council.DreamLogGenerator
import com.aura.agent.forum.ForumEngine
import com.aura.agent.state.AgentStateStore
import com.aura.agent.state.AgentStateEntity
import com.aura.agent.state.AgentRelationshipEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DreamLogUiState(
    val logText: kotlin.String = "",
    val summary: kotlin.String = "",
    val isLoading: Boolean = false,
)

data class AgentProfileUiState(
    val agents: List<AgentProfileData> = emptyList(),
    val isLoading: Boolean = false,
)

data class AgentProfileData(
    val id: kotlin.String,
    val name: kotlin.String,
    val icon: kotlin.String,
    val mood: Float,
    val energy: Float,
    val currentGoal: kotlin.String,
    val stanceOnUser: Float,
    val participationCount: Int,
    val relationships: List<AgentRelationshipEntity>,
)

@HiltViewModel
class DreamLogViewModel @Inject constructor(
    private val dreamLogGenerator: DreamLogGenerator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DreamLogUiState(isLoading = true))
    val uiState: StateFlow<DreamLogUiState> = _uiState.asStateFlow()

    init {
        loadDreamLog()
    }

    fun loadDreamLog() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val since = System.currentTimeMillis() - 12 * 3600_000L // last 12h
            val log = dreamLogGenerator.generate(since)
            val summary = dreamLogGenerator.summary(since)
            _uiState.value = DreamLogUiState(logText = log, summary = summary, isLoading = false)
        }
    }
}

@HiltViewModel
class AgentProfileViewModel @Inject constructor(
    private val agentStore: AgentStore,
    private val stateStore: AgentStateStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AgentProfileUiState(isLoading = true))
    val uiState: StateFlow<AgentProfileUiState> = _uiState.asStateFlow()

    init {
        loadProfiles()
    }

    fun loadProfiles() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val agents = agentStore.allOnce()
            val profiles = agents.map { agent ->
                val state = stateStore.getState(agent.id) ?: AgentStateEntity(agentId = agent.id)
                val rels = stateStore.getRelationshipsFor(agent.id)
                AgentProfileData(
                    id = agent.id,
                    name = agent.name.replaceFirstChar { it.uppercase() },
                    icon = agent.icon,
                    mood = state.mood,
                    energy = state.energy,
                    currentGoal = state.currentGoal,
                    stanceOnUser = state.stanceOnUser,
                    participationCount = state.participationCount,
                    relationships = rels,
                )
            }
            _uiState.value = AgentProfileUiState(agents = profiles, isLoading = false)
        }
    }
}