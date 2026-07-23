package com.aura.ui.evolution

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.evolution.EvolutionProposalDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Global evolution-inbox badge state.
 *
 * Exposed as a hot [StateFlow] so the bottom-nav [AuraBottomNavigation]
 * badge updates immediately when a new proposal lands (or when the user
 * approves/rejects). Scoped to [TopLevelRoute.Evolution] semantically,
 * but the flow lives at app scope so any composable can read it.
 *
 * Why not just use the inbox ViewModel? The inbox is instantiated only
 * when the user navigates to the Evolve tab, so its flow would emit a
 * stale value (or never) on other tabs. A separate ViewModel at the
 * scaffold root keeps the count live across the whole app.
 */
@HiltViewModel
class EvolutionBadgeViewModel @Inject constructor(
    proposalDao: EvolutionProposalDao,
) : ViewModel() {
    val pendingCount: StateFlow<Int> = proposalDao.observePendingCount()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = 0,
        )
}
