package com.aura.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.curiosity.OpenQuestionDao
import com.aura.curiosity.OpenQuestionEntity
import com.aura.dream.DreamConsolidationDao
import com.aura.dream.DreamSummaryEntity
import com.aura.memory.CorrectionEntity
import com.aura.memory.CorrectionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The parts of "what Aura thinks" that had nowhere to be shown.
 *
 * Beliefs and taste already had screens; these three never did. Corrections are
 * the record of every time the user has said Aura was wrong, which is precisely
 * the thing they would want to check and until now could only infer from a
 * memory quietly not appearing. Open questions are what Aura wants to ask.
 * Consolidation is the nightly work, summarised — the full detail keeps its own
 * screen because accepting graph proposals is maintenance, not reflection.
 */
@HiltViewModel
class MindViewModel @Inject constructor(
    private val correctionStore: CorrectionStore,
    private val openQuestionDao: OpenQuestionDao,
    private val dreamDao: DreamConsolidationDao,
) : ViewModel() {

    private val _corrections = MutableStateFlow<List<CorrectionEntity>>(emptyList())
    val corrections: StateFlow<List<CorrectionEntity>> = _corrections.asStateFlow()

    private val _questions = MutableStateFlow<List<OpenQuestionEntity>>(emptyList())
    val questions: StateFlow<List<OpenQuestionEntity>> = _questions.asStateFlow()

    private val _summaries = MutableStateFlow<List<DreamSummaryEntity>>(emptyList())
    val summaries: StateFlow<List<DreamSummaryEntity>> = _summaries.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _corrections.value = runCatching { correctionStore.recent(CORRECTIONS) }
                .onFailure { Log.w(TAG, "corrections read failed", it) }
                .getOrDefault(emptyList())
            _questions.value = runCatching {
                openQuestionDao.byStatus(OpenQuestionEntity.STATUS_OPEN, QUESTIONS)
            }.onFailure { Log.w(TAG, "questions read failed", it) }.getOrDefault(emptyList())
            _summaries.value = runCatching { dreamDao.recent(SUMMARIES) }
                .onFailure { Log.w(TAG, "summaries read failed", it) }
                .getOrDefault(emptyList())
        }
    }

    /** Undo a correction, putting back whatever it retracted. */
    fun undoCorrection(id: String) {
        viewModelScope.launch {
            runCatching { correctionStore.undo(id) }
                .onFailure { Log.w(TAG, "undo failed", it) }
            refresh()
        }
    }

    private companion object {
        const val TAG = "MindViewModel"
        const val CORRECTIONS = 20
        const val QUESTIONS = 5
        const val SUMMARIES = 5
    }
}
