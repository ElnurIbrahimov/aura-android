package com.aura.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.proactive.ProactiveEvents
import com.aura.proactive.ProactiveRunner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProactiveHistoryViewModel @Inject constructor(
    private val proactiveEvents: ProactiveEvents,
    private val runner: ProactiveRunner,
    private val outcomeDao: com.aura.proactive.ProactiveOutcomeDao? = null,
) : ViewModel() {

    val state: StateFlow<ProactiveHistoryUiState> = proactiveEvents.history
        .map { ProactiveHistoryUiState(it) }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            ProactiveHistoryUiState(emptyList()),
        )

    /**
     * What became of each suggestion, keyed by event id, plus a 30-day tally
     * per category.
     *
     * This is the visible half of measuring outcome rather than engagement: the
     * screen can say "you marked that task done five hours later" or "nothing
     * changed in three days" instead of only showing what was said.
     */
    private val _outcomes = MutableStateFlow(ProactiveOutcomeUiState())
    val outcomes: StateFlow<ProactiveOutcomeUiState> = _outcomes.asStateFlow()

    private fun refreshOutcomes() {
        val dao = outcomeDao ?: return
        viewModelScope.launch {
            runCatching {
                val events = proactiveEvents.history.value.mapNotNull { it.id.takeIf { id -> id > 0L } }
                val rows = if (events.isEmpty()) emptyList() else dao.forEvents(events)
                val since = System.currentTimeMillis() - THIRTY_DAYS_MS
                val tally = dao.tallySince(since)
                _outcomes.value = ProactiveOutcomeUiState(
                    byEventId = rows.associateBy({ it.eventId }, { it.toUi() }),
                    summary = tally.toSummaries(),
                )
            }.onFailure {
                android.util.Log.w("ProactiveHistoryVM", "outcome read failed: ${it.message}", it)
            }
        }
    }

    private fun com.aura.proactive.ProactiveOutcomeEntity.toUi() = ProactiveOutcomeUi(
        outcome = outcome,
        reason = outcomeReason.ifBlank {
            when (outcome) {
                com.aura.proactive.ProactiveOutcomeEntity.OUTCOME_PENDING -> "Still watching."
                else -> ""
            }
        },
    )

    private fun List<com.aura.proactive.OutcomeTally>.toSummaries(): List<ProactiveCategorySummary> =
        groupBy { it.findingType }
            .mapNotNull { (type, buckets) ->
                val resolved = buckets.firstOrNull {
                    it.outcome == com.aura.proactive.ProactiveOutcomeEntity.OUTCOME_RESOLVED
                }?.count ?: 0
                val ignored = buckets.firstOrNull {
                    it.outcome == com.aura.proactive.ProactiveOutcomeEntity.OUTCOME_IGNORED
                }?.count ?: 0
                val unobservable = buckets.firstOrNull {
                    it.outcome == com.aura.proactive.ProactiveOutcomeEntity.OUTCOME_UNOBSERVABLE
                }?.count ?: 0
                if (resolved + ignored + unobservable == 0) return@mapNotNull null
                ProactiveCategorySummary(
                    type = type,
                    label = com.aura.proactive.ProactiveFindingType.from(type)?.let { readableName(it) } ?: type,
                    resolved = resolved,
                    closed = resolved + ignored,
                    unobservable = unobservable,
                )
            }
            .sortedByDescending { it.closed }

    private fun readableName(type: com.aura.proactive.ProactiveFindingType): String = when (type) {
        com.aura.proactive.ProactiveFindingType.STALE_MEMORIES -> "Fading memories"
        com.aura.proactive.ProactiveFindingType.STUCK_TASKS -> "Stuck tasks"
        com.aura.proactive.ProactiveFindingType.RELATIONSHIP_GAP -> "Quiet stretches"
        com.aura.proactive.ProactiveFindingType.DEADLINE_APPROACHING -> "Today's events"
        com.aura.proactive.ProactiveFindingType.CONTRADICTION_ALERT -> "Graph conflicts"
        com.aura.proactive.ProactiveFindingType.STRESS_CORRELATION -> "Tension"
        com.aura.proactive.ProactiveFindingType.PATTERN_ALERT -> "Conversation patterns"
        com.aura.proactive.ProactiveFindingType.PRIORITY_SHIFT -> "Priority pile-ups"
        com.aura.proactive.ProactiveFindingType.OPEN_QUESTION -> "Aura's questions"
        com.aura.proactive.ProactiveFindingType.LIVING_WORLD -> "World news"
    }

    /**
     * Status of the most recent "fire now" tap. Renders as a
     * snackbar / inline message on the screen. Cleared on
     * the next [clearStatus] call or after a few seconds.
     */
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    init {
        proactiveEvents.markSeen()
        refreshOutcomes()
    }

    fun fireMorningBrief() = run { _status.value = "Firing morning brief…" }
        .also { viewModelScope.launch {
            val r = runner.fireMorningBrief()
            _status.value = r.toMessage()
        } }

    fun fireDecayPass() = run { _status.value = "Firing decay pass…" }
        .also { viewModelScope.launch {
            val r = runner.fireDecayPass()
            _status.value = r.toMessage()
        } }

    fun fireCalendarCheck() = run { _status.value = "Firing calendar check…" }
        .also { viewModelScope.launch {
            val r = runner.fireCalendarCheck()
            _status.value = r.toMessage()
        } }

    fun onEventAction(eventId: Long, eventType: String, action: String, feedback: String = "") {
        viewModelScope.launch {
            proactiveEvents.recordInteraction(eventId, eventType, action, feedback)
        }
    }

    fun clearStatus() { _status.value = null }

    private fun ProactiveRunner.RunResult.toMessage(): String = when (this) {
        is ProactiveRunner.RunResult.Ok -> "✅ $message"
        is ProactiveRunner.RunResult.Error -> "❌ $message"
    }
}

data class ProactiveHistoryUiState(
    val events: List<com.aura.proactive.ProactiveEventBus.Event> = emptyList(),
)

/** What became of one suggestion. */
data class ProactiveOutcomeUi(val outcome: String, val reason: String)

data class ProactiveCategorySummary(
    val type: String,
    val label: String,
    val resolved: Int,
    val closed: Int,
    /** Suggestions of this kind whose effect Aura genuinely cannot observe. */
    val unobservable: Int,
) {
    val rate: Float get() = if (closed == 0) 0f else resolved.toFloat() / closed
    val neverMeasurable: Boolean get() = closed == 0 && unobservable > 0
}

data class ProactiveOutcomeUiState(
    val byEventId: Map<Long, ProactiveOutcomeUi> = emptyMap(),
    val summary: List<ProactiveCategorySummary> = emptyList(),
)

private const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000
