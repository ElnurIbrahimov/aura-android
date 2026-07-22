package com.aura.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.agentrun.AgentRunEntity
import com.aura.agentrun.AgentRunStore
import com.aura.agentrun.ApprovalRequestEntity
import com.aura.agentrun.AgentEventEntity
import com.aura.agentrun.AgentRunExecutorService
import com.aura.agentrun.StepEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the durable agent runs list and detail screen.
 */
data class AgentRunsUiState(
    val runs: List<AgentRunEntity> = emptyList(),
    val selectedRun: AgentRunEntity? = null,
    val steps: List<StepEntity> = emptyList(),
    val events: List<AgentEventEntity> = emptyList(),
    val approvals: List<ApprovalRequestEntity> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class AgentRunsViewModel @Inject constructor(
    private val agentRunStore: AgentRunStore,
    @ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {

    private val _state = MutableStateFlow(AgentRunsUiState())
    val state: StateFlow<AgentRunsUiState> = _state.asStateFlow()

    init {
        loadRuns()
    }

    fun loadRuns() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val runs = agentRunStore.listRecent(limit = 50)
                _state.update { it.copy(runs = runs, loading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    fun selectRun(runId: String) {
        viewModelScope.launch {
            val run = agentRunStore.loadRun(runId) ?: return@launch
            val steps = agentRunStore.stepsForRun(runId)
            val events = agentRunStore.eventsForRun(runId)
            val approvals = agentRunStore.pendingApprovals(runId)
            _state.update {
                it.copy(
                    selectedRun = run,
                    steps = steps,
                    events = events,
                    approvals = approvals,
                )
            }
        }
    }

    fun approve(approvalId: String) {
        viewModelScope.launch {
            // Atomicity: pendingApprovals() only returns PENDING entries,
            // and approve() flips the status atomically. We must capture
            // the approval's stepId BEFORE calling approve() — otherwise
            // a concurrent deny() could land between lookup and step
            // reset, and resetStep() would re-enable a step the user
            // just denied.
            val runId = _state.value.selectedRun?.id ?: ""
            val approval = agentRunStore.pendingApprovals(runId)
                .firstOrNull { it.id == approvalId }
            if (approval == null) {
                // Already approved/denied by another caller. Refresh
                // the detail so the UI reflects the current state and
                // don't touch the step.
                if (runId.isNotBlank()) refreshDetail(runId)
                return@launch
            }
            val stepId = approval.stepId
            agentRunStore.approve(approvalId)
            // Reset the step that was awaiting approval back to PENDING
            // so the executor worker picks it up again. Safe to do
            // unconditionally now that we hold the captured stepId.
            if (stepId.isNotBlank()) {
                agentRunStore.resetStep(stepId)
            }
            if (runId.isNotBlank()) {
                refreshDetail(runId)
                AgentRunExecutorService.enqueue(appContext, runId)
            }
        }
    }

    fun deny(approvalId: String, reason: String = "") {
        viewModelScope.launch {
            agentRunStore.deny(approvalId, reason)
            _state.value.selectedRun?.id?.let { refreshDetail(it) }
        }
    }

    fun resume(runId: String) {
        viewModelScope.launch {
            agentRunStore.updateStatus(runId, "RUNNING")
            agentRunStore.checkpoint(runId)
            loadRuns()
            refreshDetail(runId)
            // Re-enqueue the executor worker so it picks up PENDING steps.
            AgentRunExecutorService.enqueue(appContext, runId)
        }
    }

    fun cancel(runId: String) {
        viewModelScope.launch {
            agentRunStore.finish(runId, "CANCELLED")
            loadRuns()
            refreshDetail(runId)
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selectedRun = null, steps = emptyList(), events = emptyList(), approvals = emptyList()) }
    }

    private fun refreshDetail(runId: String) {
        selectRun(runId)
    }
}
