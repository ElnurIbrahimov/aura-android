package com.aura.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.creative.CreativeProjectStore
import com.aura.creative.ProductionPipelineEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProductionPipelineViewModel @Inject constructor(
    private val projectStore: CreativeProjectStore,
    private val engine: ProductionPipelineEngine,
) : ViewModel() {

    data class State(
        val projects: List<com.aura.creative.CreativeProject> = emptyList(),
        val selectedProjectId: String? = null,
        val selectedPipeline: ProductionPipelineEngine.Pipeline? = null,
        val brief: String = "",
        val available: List<ProductionPipelineEngine.Pipeline> = emptyList(),
        val busy: Boolean = false,
        val scheduledRunId: String? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            projectStore.observeAll().collect { projects ->
                _state.update { it.copy(projects = projects, selectedProjectId = it.selectedProjectId ?: projects.firstOrNull()?.id) }
            }
        }
        viewModelScope.launch {
            _state.update { it.copy(available = engine.availablePipelines()) }
        }
    }

    fun selectProject(id: String) {
        _state.update { it.copy(selectedProjectId = id) }
    }

    fun selectPipeline(pipeline: ProductionPipelineEngine.Pipeline) {
        _state.update { it.copy(selectedPipeline = pipeline) }
    }

    fun setBrief(value: String) {
        _state.update { it.copy(brief = value) }
    }

    fun schedule() {
        val projectId = _state.value.selectedProjectId ?: return
        val pipeline = _state.value.selectedPipeline ?: return
        val brief = _state.value.brief.ifBlank { "Production run" }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, scheduledRunId = null) }
            try {
                val runId = engine.schedule(projectId, pipeline, brief)
                _state.update { it.copy(busy = false, scheduledRunId = runId) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = e.message ?: "Pipeline scheduling failed") }
            }
        }
    }

    fun dismissResult() {
        _state.update { it.copy(scheduledRunId = null, error = null) }
    }
}
