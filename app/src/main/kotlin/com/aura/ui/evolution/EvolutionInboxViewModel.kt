package com.aura.ui.evolution

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.evolution.EvolutionDomain
import com.aura.evolution.EvolutionProposalDao
import com.aura.evolution.EvolutionProposalEntity
import com.aura.evolution.EvolutionProposalStore
import com.aura.evolution.EvolutionSettingsDao
import com.aura.evolution.EvolutionSettingsEntity
import com.aura.evolution.ProposalStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the evolution proposal inbox. Lists open proposals,
 * allows approve/reject, and exposes domain settings toggles.
 */
@HiltViewModel
class EvolutionInboxViewModel @Inject constructor(
    private val proposalDao: EvolutionProposalDao,
    private val proposalStore: EvolutionProposalStore,
    private val settingsDao: EvolutionSettingsDao,
) : ViewModel() {

    private val _proposals = MutableStateFlow<List<EvolutionProposalEntity>>(emptyList())
    val proposals: StateFlow<List<EvolutionProposalEntity>> = _proposals.asStateFlow()

    private val _settings = MutableStateFlow<List<EvolutionSettingsEntity>>(emptyList())
    val settings: StateFlow<List<EvolutionSettingsEntity>> = _settings.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _proposals.value = proposalDao.open()
            _settings.value = EvolutionDomain.entries.map { settingsDao.get(it.name) ?: EvolutionSettingsEntity(it.name) }
        }
    }

    fun approve(id: kotlin.String) {
        viewModelScope.launch {
            proposalStore.approve(id)
            load()
        }
    }

    fun reject(id: kotlin.String) {
        viewModelScope.launch {
            proposalStore.reject(id)
            load()
        }
    }

    fun setDomainEnabled(domain: EvolutionDomain, enabled: kotlin.Boolean) {
        viewModelScope.launch {
            val current = settingsDao.get(domain.name) ?: EvolutionSettingsEntity(domain.name)
            settingsDao.upsert(current.copy(enabled = enabled, updatedAt = System.currentTimeMillis()))
            load()
        }
    }
}
