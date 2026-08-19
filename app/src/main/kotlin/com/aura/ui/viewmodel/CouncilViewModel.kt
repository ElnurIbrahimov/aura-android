package com.aura.ui.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.agent.AgentCouncil
import com.aura.agent.AgentEntity
import com.aura.agent.AgentStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.update
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
data class CouncilUiState(
    val task: String = "",
    val availableAgents: List<AgentEntity> = emptyList(),
    val selectedAgentIds: List<String> = emptyList(),
    val progress: List<AgentCouncil.Progress> = emptyList(),
    val result: AgentCouncil.CouncilResult? = null,
    val running: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class CouncilViewModel @Inject constructor(
    private val agentStore: AgentStore,
    private val agentCouncil: AgentCouncil,
) : ViewModel() {

    private val _state = MutableStateFlow(CouncilUiState())
    val state: StateFlow<CouncilUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            agentStore.all().collect { agents ->
                _state.update { old ->
                    val seed = old.selectedAgentIds.isEmpty()
                    val defaultSelection = agents.filter { !it.isDefault }.map { it.id }
                        .ifEmpty { agents.map { it.id } }
                    old.copy(
                        availableAgents = agents,
                        selectedAgentIds = if (seed) defaultSelection else old.selectedAgentIds,
                    )
                }
            }
        }
    }

    fun setTask(task: String) {
        _state.update { it.copy(task = task) }
    }

    fun toggleAgent(agentId: String) {
        _state.update { old ->
            val selected = if (agentId in old.selectedAgentIds) {
                old.selectedAgentIds - agentId
            } else {
                old.selectedAgentIds + agentId
            }
            old.copy(selectedAgentIds = selected)
        }
    }

    fun runCouncil() {
        val current = _state.value
        if (current.task.isBlank() || current.running) return
        _state.update { it.copy(running = true, progress = emptyList(), result = null, error = null) }
        viewModelScope.launch {
            try {
                val result = agentCouncil.run(
                    agentIds = current.selectedAgentIds,
                    task = current.task,
                    onProgress = { progress ->
                        _state.update { it.copy(progress = it.progress + progress) }
                    },
                )
                _state.update {
                    it.copy(
                        result = result,
                        running = false,
                        error = if (!result.success) result.error else null,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        running = false,
                        error = e.message ?: "Council failed",
                        progress = it.progress + AgentCouncil.Progress.Error(e.message ?: "Council failed"),
                    )
                }
            }
        }
    }

    fun clearResult() {
        _state.update { it.copy(result = null, progress = emptyList(), error = null) }
    }
}
