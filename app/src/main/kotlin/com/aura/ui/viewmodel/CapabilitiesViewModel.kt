package com.aura.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aura.capabilities.CapabilityKind
import com.aura.capabilities.CapabilityRegistry
import com.aura.providers.providerLabel
import com.aura.ui.util.description
import com.aura.ui.util.displayLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * User-facing snapshot of every [CapabilityKind], whether it is configured,
 * and the specific provider that will be used for it.
 */
data class CapabilityCardState(
    val kind: CapabilityKind,
    val label: String,
    val description: String,
    val isConfigured: Boolean,
    val providerLabel: String?,
)

@HiltViewModel
class CapabilitiesViewModel @Inject constructor(
    application: Application,
    private val capabilityRegistry: CapabilityRegistry,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(
        CapabilityKind.entries.map { kind ->
            CapabilityCardState(
                kind = kind,
                label = kind.displayLabel(),
                description = kind.description(),
                isConfigured = false,
                providerLabel = null,
            )
        }
    )
    val state: StateFlow<List<CapabilityCardState>> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            val cards = CapabilityKind.entries.map { kind ->
                val provider = capabilityRegistry.configuredForKind(kind).firstOrNull()
                CapabilityCardState(
                    kind = kind,
                    label = kind.displayLabel(),
                    description = kind.description(),
                    isConfigured = provider != null,
                    providerLabel = provider?.prefix?.let { providerLabel(it) },
                )
            }
            _state.value = cards
        }
    }
}
