package com.aura.ui.evolution

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.world.BeliefDao
import com.aura.world.BeliefEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BeliefsViewModel @Inject constructor(
    private val beliefDao: BeliefDao,
) : ViewModel() {
    private val _beliefs = MutableStateFlow<List<BeliefEntity>>(emptyList())
    val beliefs: StateFlow<List<BeliefEntity>> = _beliefs

    init {
        load()
    }

    private val _selected = MutableStateFlow<BeliefEntity?>(null)
    val selected: StateFlow<BeliefEntity?> = _selected

    private fun load() = viewModelScope.launch {
        _beliefs.value = beliefDao.allActive(200)
    }

    fun select(id: String) {
        viewModelScope.launch {
            _selected.value = beliefDao.getById(id)
        }
    }

    fun clearSelection() {
        _selected.value = null
    }
}
