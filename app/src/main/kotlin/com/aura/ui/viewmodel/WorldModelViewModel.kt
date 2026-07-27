package com.aura.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.dream.ContradictionEntity
import com.aura.dream.ContradictionDao
import com.aura.world.BeliefDao
import com.aura.world.BeliefEntity
import com.aura.world.EvidenceDao
import com.aura.world.EvidenceEntity
import com.aura.world.OpportunityDao
import com.aura.world.OpportunityEntity
import com.aura.world.WorldEventDao
import com.aura.world.WorldEventEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class WorldModelViewModel @Inject constructor(
    private val beliefDao: BeliefDao,
    private val evidenceDao: EvidenceDao,
    private val worldEventDao: WorldEventDao,
    private val opportunityDao: OpportunityDao,
    private val contradictionDao: ContradictionDao,
) : ViewModel() {

    val beliefs: StateFlow<List<BeliefEntity>> =
        kotlinx.coroutines.flow.flow { emit(beliefDao.allActiveInScopes(listOf("general"), 200)) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val worldEvents: StateFlow<List<WorldEventEntity>> = worldEventDao.observeRecent(50)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val opportunities: StateFlow<List<OpportunityEntity>> = opportunityDao.observeProposed(50)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val contradictions: StateFlow<List<ContradictionEntity>> = contradictionDao.observeByStatus("UNRESOLVED")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    fun resolveOpportunity(id: String, approve: Boolean) {
        viewModelScope.launch {
            opportunityDao.resolve(id, if (approve) "approved" else "dismissed", System.currentTimeMillis())
        }
    }
}
