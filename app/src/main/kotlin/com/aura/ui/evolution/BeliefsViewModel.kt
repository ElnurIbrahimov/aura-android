package com.aura.ui.evolution

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.world.BeliefDao
import com.aura.world.BeliefEntity
import com.aura.world.EvidenceDao
import com.aura.world.EvidenceEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.util.Log

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
data class BeliefsUiState(
    val beliefs: List<BeliefEntity> = emptyList(),
    /** Evidence supporting each belief, keyed by belief id. */
    val evidence: Map<String, List<EvidenceEntity>> = emptyMap(),
    /**
     * Superseded predecessors of each active belief, keyed by belief id,
     * newest-discarded-first. `BeliefDao.history` itself is ORDER BY
     * createdAt ASC and includes the active belief, but "I used to think X"
     * means the value held most recently before now — so this map is built by
     * filtering to `status == "superseded"` and reversing to newest-first,
     * making `take(n)` at the render site return the most relevant entries.
     */
    val history: Map<String, List<BeliefEntity>> = emptyMap(),
)

@HiltViewModel
class BeliefsViewModel @Inject constructor(
    private val beliefDao: BeliefDao,
    private val evidenceDao: EvidenceDao,
) : ViewModel() {

    private val _state = MutableStateFlow(BeliefsUiState())
    val state: StateFlow<BeliefsUiState> = _state.asStateFlow()

    /** Retained for existing callers that observe the list directly. */
    val beliefs: StateFlow<List<BeliefEntity>> =
        _state.map { it.beliefs }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init { load() }

    private fun load() = viewModelScope.launch {
        val loaded = beliefDao.allActive(200)
        val evidenceByBelief = loaded.associate { belief ->
            belief.id to runCatching { evidenceDao.forBelief(belief.id) }.onFailure { Log.w("BeliefsViewModel", "runCatching failed: ${it.message}", it) }.getOrDefault(emptyList())
        }
        // BeliefDao.history is ORDER BY createdAt ASC. "I used to think X"
        // means the value held most recently before now, so the chain is
        // presented newest-discarded-first; take(n) at the render site then
        // shows the most relevant entries rather than the oldest ones.
        val historyByBelief = loaded.associate { belief ->
            belief.id to runCatching {
                beliefDao.history(belief.subject, belief.predicate)
                    .filter { it.status == "superseded" }
                    .sortedByDescending { it.createdAt }
            }.onFailure { Log.w("BeliefsVM", "op failed: ${it.message}", it) }.getOrDefault(emptyList())
        }
        _state.value = BeliefsUiState(
            beliefs = loaded,
            evidence = evidenceByBelief,
            history = historyByBelief,
        )
    }

    private val _selected = MutableStateFlow<BeliefEntity?>(null)
    val selected: StateFlow<BeliefEntity?> = _selected

    fun select(id: String) {
        viewModelScope.launch {
            _selected.value = beliefDao.getById(id)
        }
    }

    fun clearSelection() {
        _selected.value = null
    }

    /** Mark a belief as retired — no longer active, not superseded. */
    fun retire(id: kotlin.String) {
        viewModelScope.launch {
            runCatching {
                val now = System.currentTimeMillis()
                beliefDao.supersede(id, "retired", "", now)
            }.onFailure { android.util.Log.w("BeliefsVM", "retire failed: ${it.message}", it) }
            _selected.value = beliefDao.getById(id)
            load()
        }
    }

    /** Verify a belief — bump lastVerifiedAt and confidence. */
    fun verify(id: kotlin.String, confidence: Float? = null) {
        viewModelScope.launch {
            runCatching {
                val belief = beliefDao.getById(id) ?: return@launch
                val now = System.currentTimeMillis()
                beliefDao.verify(id, confidence ?: belief.confidence, now)
            }.onFailure { android.util.Log.w("BeliefsVM", "verify failed: ${it.message}", it) }
            _selected.value = beliefDao.getById(id)
            load()
        }
    }
}
