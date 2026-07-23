package com.aura.ui.evolution

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.data.UserPreferences
import com.aura.evolution.EvolutionApplySaga
import com.aura.evolution.EvolutionDomain
import com.aura.evolution.EvolutionProposalDao
import com.aura.evolution.EvolutionProposalEntity
import com.aura.evolution.EvolutionProposalStore
import com.aura.evolution.EvolutionRollbackManager
import com.aura.evolution.EvolutionSettingsDao
import com.aura.evolution.EvolutionSettingsEntity
import com.aura.evolution.ProposalStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the evolution proposal inbox. Lists open proposals,
 * allows approve/reject/rollback, and exposes domain settings toggles.
 */
@HiltViewModel
class EvolutionInboxViewModel @Inject constructor(
    private val proposalDao: EvolutionProposalDao,
    private val proposalStore: EvolutionProposalStore,
    private val settingsDao: EvolutionSettingsDao,
    private val rollbackManager: EvolutionRollbackManager,
    private val userPreferences: UserPreferences,
    private val applySaga: EvolutionApplySaga,
) : ViewModel() {

    private val _proposals = MutableStateFlow<List<EvolutionProposalEntity>>(emptyList())
    val proposals: StateFlow<List<EvolutionProposalEntity>> = _proposals.asStateFlow()

    private val _settings = MutableStateFlow<List<EvolutionSettingsEntity>>(emptyList())
    val settings: StateFlow<List<EvolutionSettingsEntity>> = _settings.asStateFlow()

    private val _showOnboarding = MutableStateFlow(false)
    val showOnboarding: StateFlow<Boolean> = _showOnboarding.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _proposals.value = proposalDao.open()
            _settings.value = EvolutionDomain.entries.map { settingsDao.get(it.name) ?: EvolutionSettingsEntity(it.name) }
            _showOnboarding.value = !userPreferences.evolutionOnboardingShown.first() && _proposals.value.isEmpty()
        }
    }

    fun dismissOnboarding() {
        viewModelScope.launch {
            userPreferences.setEvolutionOnboardingShown(true)
            _showOnboarding.value = false
        }
    }

    fun rollback(proposalId: String) {
        viewModelScope.launch {
            rollbackManager.rollback(proposalId)
            load()
        }
    }

    fun approve(id: kotlin.String) {
        viewModelScope.launch {
            proposalStore.approve(id)
            // Actually apply the proposal — without this the proposal
            // sat in APPROVED status forever and nothing happened.
            val proposal = proposalStore.getById(id)
            if (proposal != null) {
                when (val result = applySaga.apply(proposal)) {
                    is EvolutionApplySaga.ApplyResult.Ok -> {
                        // applySaga already calls proposalStore.markApplied
                    }
                    is EvolutionApplySaga.ApplyResult.Error -> {
                        proposalStore.markApplyFailed(id, result.message)
                    }
                    is EvolutionApplySaga.ApplyResult.NotYetImplemented -> {
                        proposalStore.markApplyFailed(id, "action not implemented: ${result.action}")
                    }
                }
            }
            load()
        }
    }

    fun reject(id: kotlin.String, reason: kotlin.String = "") {
        viewModelScope.launch {
            proposalStore.reject(id, reason)
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
