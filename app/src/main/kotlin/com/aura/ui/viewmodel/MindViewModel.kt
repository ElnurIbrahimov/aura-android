package com.aura.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.changelog.Change
import com.aura.changelog.ChangeLog
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
    private val changeLog: ChangeLog,
    private val projectStore: com.aura.projects.ProjectStore? = null,
    private val calibrationReader: com.aura.calibration.Calibration? = null,
    private val questionScanner: com.aura.curiosity.QuestionScanner? = null,
) : ViewModel() {

    private val _corrections = MutableStateFlow<List<CorrectionEntity>>(emptyList())
    val corrections: StateFlow<List<CorrectionEntity>> = _corrections.asStateFlow()

    private val _questions = MutableStateFlow<List<OpenQuestionEntity>>(emptyList())
    val questions: StateFlow<List<OpenQuestionEntity>> = _questions.asStateFlow()

    /**
     * What Aura does not understand, ranked, below the one thing it chose to ask.
     *
     * Read live rather than stored: `scan()` is DB reads, and only one question is ever open,
     * so the losing candidates exist nowhere else. This list is the ledger — the open
     * question is only its tip.
     */
    private val _candidates = MutableStateFlow<List<com.aura.curiosity.QuestionScanner.Subject>>(emptyList())
    val candidates: StateFlow<List<com.aura.curiosity.QuestionScanner.Subject>> = _candidates.asStateFlow()

    private val _summaries = MutableStateFlow<List<DreamSummaryEntity>>(emptyList())
    val summaries: StateFlow<List<DreamSummaryEntity>> = _summaries.asStateFlow()

    /**
     * What changed in the last week.
     *
     * Everything else on this screen is present tense — what Aura believes now,
     * what it wants to ask now. This is the only part that answers *what moved*,
     * which for a system whose whole premise is accumulation is the question it
     * could least afford not to answer.
     */
    private val _changes = MutableStateFlow<List<Change>>(emptyList())
    val changes: StateFlow<List<Change>> = _changes.asStateFlow()

    /**
     * Active projects with their live ledger rows.
     *
     * Surfaced here rather than behind a new route, for the reason `ChangeLog`
     * was: a route would move the `NAV_DESTINATIONS` and `SECONDARY_ROUTES`
     * counts that `check-version-docs.sh` gates, and Mind exists precisely
     * because these views were scattered.
     *
     * Visible from the first version deliberately. The ledger is written by a
     * model on a background sweep, so a wrong row is the failure that would
     * otherwise accumulate unseen for months — showing it is what makes the
     * write path correctable.
     */
    private val _projects = MutableStateFlow<List<ProjectLedger>>(emptyList())
    val projects: StateFlow<List<ProjectLedger>> = _projects.asStateFlow()

    /**
     * How often Aura's own confidence was earned.
     *
     * Nullable rather than a default empty report: "not measured yet" and "zero
     * out of zero" have to look different on screen, and a default-constructed
     * report would render as the second.
     */
    private val _calibration = MutableStateFlow<com.aura.calibration.Calibration.Report?>(null)
    val calibration: StateFlow<com.aura.calibration.Calibration.Report?> = _calibration.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _corrections.value = runCatching { correctionStore.recent(CORRECTIONS) }
                .onFailure { Log.w(TAG, "corrections read failed", it) }
                .getOrDefault(emptyList())
            _questions.value = runCatching {
                openQuestionDao.byStatus(OpenQuestionEntity.STATUS_OPEN, QUESTIONS)
            }.onFailure { Log.w(TAG, "questions read failed", it) }.getOrDefault(emptyList())
            _candidates.value = runCatching { questionScanner?.scan() ?: emptyList() }
                .onFailure { Log.w(TAG, "candidate scan failed", it) }
                .getOrDefault(emptyList())
            _summaries.value = runCatching { dreamDao.recent(SUMMARIES) }
                .onFailure { Log.w(TAG, "summaries read failed", it) }
                .getOrDefault(emptyList())
            _changes.value = runCatching {
                changeLog.since(System.currentTimeMillis() - ChangeLog.WEEK_MS, CHANGES)
            }.onFailure { Log.w(TAG, "change log read failed", it) }.getOrDefault(emptyList())
            _calibration.value = runCatching { calibrationReader?.report() }
                .onFailure { Log.w(TAG, "calibration read failed", it) }
                .getOrNull()
            _projects.value = runCatching {
                projectStore?.active(PROJECTS).orEmpty().map { project ->
                    ProjectLedger(project, projectStore?.activeNotes(project.id, NOTES).orEmpty())
                }
            }.onFailure { Log.w(TAG, "project ledger read failed", it) }.getOrDefault(emptyList())
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
        const val PROJECTS = 8
        const val NOTES = 12
        const val SUMMARIES = 5
        const val CHANGES = 25
    }
}

/** One project and what is currently true about it. */
data class ProjectLedger(
    val project: com.aura.projects.ProjectEntity,
    val notes: List<com.aura.projects.ProjectNoteEntity>,
)
