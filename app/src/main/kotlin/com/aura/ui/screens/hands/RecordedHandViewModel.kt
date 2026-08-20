package com.aura.ui.screens.hands

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.a11y.ElementSelector
import com.aura.hands.Hand
import com.aura.hands.HandRepository
import com.aura.hands.record.HandRecorder
import com.aura.hands.record.RecordedHandCompiler
import com.aura.hands.record.RecordedHandDraft
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * Drives one demonstration from Record to a saved Hand.
 *
 * Deliberately thin. Everything with a decision in it — what the diff means, which targets
 * are choosable, whether a draft may be saved, what the steps compile to — lives in
 * `com.aura.hands.record` where it runs in CI without a device. This holds the draft and
 * talks to the recorder and the repository.
 */
@HiltViewModel
class RecordedHandViewModel @Inject constructor(
    private val recorder: HandRecorder,
    private val repository: HandRepository,
) : ViewModel() {

    data class UiState(
        val recording: Boolean = false,
        val boundPackage: String = "",
        val liveStepCount: Int = 0,
        val draft: RecordedHandDraft = RecordedHandDraft(),
        val reviewing: Boolean = false,
        val saved: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            recorder.state.collect { recording ->
                _state.value = _state.value.copy(
                    recording = recording.recording,
                    boundPackage = recording.boundPackage,
                    liveStepCount = recording.steps.size,
                )
            }
        }
    }

    /** Bound lazily: when this is tapped the foreground app is still Aura. */
    fun startRecording() {
        _state.value = UiState(recording = true)
        recorder.start()
    }

    fun stopRecording() {
        val steps = recorder.stop()
        _state.value = _state.value.copy(
            recording = false,
            reviewing = true,
            draft = _state.value.draft.copy(steps = steps),
        )
    }

    fun setName(name: String) = update { it.copy(draft = it.draft.copy(name = name)) }

    fun resolve(index: Int, choice: ElementSelector) = update { it.copy(draft = it.draft.resolve(index, choice)) }

    fun remove(index: Int) = update { it.copy(draft = it.draft.remove(index)) }

    fun makeVariable(index: Int, variable: String) =
        update { it.copy(draft = it.draft.makeVariable(index, variable)) }

    fun clearError() = update { it.copy(error = null) }

    /**
     * Compile and store. The compiler refuses a draft with an unanswered question, and that
     * refusal is surfaced rather than worked around: it is the whole reason this screen exists.
     */
    fun save() {
        val draft = _state.value.draft
        when (val compiled = RecordedHandCompiler.compile(draft.steps)) {
            is RecordedHandCompiler.Result.Unresolved -> update {
                it.copy(
                    error = "Choose what step ${compiled.positions.first() + 1} should tap before saving.",
                )
            }

            is RecordedHandCompiler.Result.Compiled -> viewModelScope.launch {
                runCatching {
                    repository.insert(
                        Hand(
                            id = UUID.randomUUID().toString(),
                            name = draft.name.trim(),
                            steps = repository.stepsToJson(compiled.steps),
                            variables = draft.variables.entries
                                .joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"$v\"" },
                            // Never scheduled. screen_act needs a session a person opens, so an
                            // unattended run would stop at step one forever — HandScheduler
                            // refuses these anyway, and this says so at the point of saving.
                            scheduleType = "none",
                        ),
                    )
                }.onSuccess { update { it.copy(saved = true) } }
                    .onFailure { e -> update { it.copy(error = "Could not save: ${e.message ?: "unknown error"}") } }
            }
        }
    }

    private inline fun update(block: (UiState) -> UiState) {
        _state.value = block(_state.value)
    }
}
