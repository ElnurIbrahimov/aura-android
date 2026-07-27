package com.aura.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.taste.PreferenceSignalDao
import com.aura.taste.PreferenceSignalEntity
import com.aura.taste.StyleProfileDao
import com.aura.taste.StyleProfileEntity
import com.aura.taste.TasteEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class TasteProfileViewModel @Inject constructor(
    private val signalDao: PreferenceSignalDao,
    private val profileDao: StyleProfileDao,
    private val tasteEngine: TasteEngine,
) : ViewModel() {

    val signals: StateFlow<List<PreferenceSignalEntity>> =
        kotlinx.coroutines.flow.flow { emit(signalDao.forScopes(listOf("general"), 500)) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val profile: StateFlow<StyleProfileEntity?> =
        kotlinx.coroutines.flow.flow { emit(profileDao.forScopes(listOf("general"))) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    fun recompute() {
        viewModelScope.launch { tasteEngine.recomputeProfile("") }
    }

    fun deleteSignal(id: String) {
        viewModelScope.launch { tasteEngine.deleteSignal(id) }
    }

    fun clearAllSignals() {
        viewModelScope.launch { tasteEngine.clearSignals("") }
    }
}
