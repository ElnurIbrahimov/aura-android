package com.aura.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.agent.runtime.AgentTraceEvent
import com.aura.agent.runtime.TraceSink
import com.aura.core.error.CrashLogEntry
import com.aura.core.error.CrashLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.util.Log

data class DiagnosticsUiState(
    val entries: List<CrashLogEntry> = emptyList(),
    val traceEvents: List<AgentTraceEvent> = emptyList(),
    val traceCount: Int = 0,
    val loading: Boolean = true,
    val exporting: Boolean = false,
    val exportFile: File? = null,
    val error: String? = null,
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val crashLogger: CrashLogger,
    private val traceSink: TraceSink,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(DiagnosticsUiState())
    val state: StateFlow<DiagnosticsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            runCatching { crashLogger.entries() }.onFailure { Log.w("DiagnosticsViewModel", "runCatching failed: ${it.message}", it) }
                .onSuccess { entries ->
                    _state.update {
                        it.copy(
                            entries = entries,
                            traceEvents = traceSink.recent(100),
                            traceCount = traceSink.count(),
                            loading = false,
                            error = null,
                        )
                    }
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(
                            loading = false,
                            error = failure.message ?: failure.javaClass.simpleName,
                        )
                    }
                }
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            runCatching {
                crashLogger.clear()
                traceSink.clear()
                crashLogger.entries()
            }.onFailure { Log.w("DiagVM", "op failed: ${it.message}", it) }.onSuccess { entries ->
                _state.update {
                    it.copy(
                        entries = entries,
                        traceEvents = emptyList(),
                        traceCount = 0,
                        error = null,
                    )
                }
            }.onFailure { failure ->
                _state.update { it.copy(error = failure.message ?: failure.javaClass.simpleName) }
            }
        }
    }

    fun prepareExport() {
        if (_state.value.exporting) return
        _state.update { it.copy(exporting = true) }
        viewModelScope.launch {
            runCatching { crashLogger.exportTo(context.cacheDir) }
                .onSuccess { file ->
                    _state.update { it.copy(exporting = false, exportFile = file, error = null) }
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(
                            exporting = false,
                            error = failure.message ?: failure.javaClass.simpleName,
                        )
                    }
                }
        }
    }

    fun consumeExport() {
        _state.update { it.copy(exportFile = null) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
}