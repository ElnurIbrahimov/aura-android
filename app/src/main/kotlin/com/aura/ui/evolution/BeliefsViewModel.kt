package com.aura.ui.evolution

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

data class BeliefsUiState(
    val beliefs: List<BeliefEntity> = emptyList(),
    /** Evidence supporting each belief, keyed by belief id. */
    val evidence: Map<String, List<EvidenceEntity>> = emptyMap(),
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
            belief.id to runCatching { evidenceDao.forBelief(belief.id) }.getOrDefault(emptyList())
        }
        _state.value = BeliefsUiState(beliefs = loaded, evidence = evidenceByBelief)
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
}
