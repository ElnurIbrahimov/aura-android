package com.aura.ui.settings

import androidx.lifecycle.ViewModel
import com.aura.usage.UsageSnapshot
import com.aura.usage.UsageTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class UsageViewModel @Inject constructor(
    private val tracker: UsageTracker,
    private val budget: com.aura.usage.BackgroundBudget,
) : ViewModel() {
    val usage: StateFlow<UsageSnapshot> = tracker.snapshot

    /**
     * Today's unattended spend against its ceiling.
     *
     * Deliberately separate from [usage], which is a cumulative total over all
     * time and every caller. This answers a different question — *is Aura
     * spending money while I am not looking, and how much* — which had no answer
     * at all before there was a cap to answer it against.
     */
    val backgroundSpend: StateFlow<com.aura.usage.BackgroundSpend> = budget.spend

    fun reset() = tracker.reset()
}
