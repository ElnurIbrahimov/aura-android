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
    private val providerKeys: com.aura.providers.ProviderKeys,
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
                val backend = resolveBackend(kind)
                CapabilityCardState(
                    kind = kind,
                    label = kind.displayLabel(),
                    description = kind.description(),
                    isConfigured = backend != null,
                    providerLabel = backend,
                )
            }
            _state.value = cards
        }
    }

    /**
     * Name the backend that will actually serve [kind], or null when
     * nothing can.
     *
     * This screen used to report purely on [CapabilityRegistry], but the
     * registry is not what runs. Only a handful of kinds have a provider
     * bound in CapabilityModule; the real work is done by tools that read
     * their own keys. The two systems disagreed on four of the six rows:
     *
     *  - Web search: the registry checks Exa/Jina, while WebSearchTool goes
     *    Tavily → Brave → DuckDuckGo. A user with a Tavily key was told
     *    "Not configured" while search worked in the same session.
     *  - Transcription: no provider is bound for it at all, so the row read
     *    "Not configured" permanently, whatever TranscriptionTool could do
     *    with an OpenAI or Groq key.
     *  - Image generation: ImageGenTool falls back to Pollinations, which
     *    needs no key.
     *  - Text to speech: the device's own engine always exists.
     *
     * Three of those have keyless fallbacks and so can never truly be
     * unconfigured. The honest answer is which backend is in play, not
     * whether some key happens to exist.
     */
    private suspend fun resolveBackend(kind: CapabilityKind): String? {
        // A bound registry provider holding a key always wins.
        capabilityRegistry.configuredForKind(kind).firstOrNull()?.let {
            return providerLabel(it.prefix)
        }
        suspend fun keyed(prefix: String, label: String): String? =
            label.takeIf { !providerKeys.keyForAwaiting(prefix).isNullOrBlank() }

        return when (kind) {
            // Mirrors WebSearchTool.search()'s backend order exactly.
            CapabilityKind.WebSearch ->
                keyed("tavily", "Tavily") ?: keyed("brave", "Brave") ?: "DuckDuckGo"
            // Mirrors TranscriptionTool.
            CapabilityKind.Transcription ->
                keyed("openai", "OpenAI Whisper") ?: keyed("groq", "Groq Whisper")
            // Mirrors ImageGenTool: DALL·E when OpenAI is keyed, otherwise
            // the keyless Pollinations endpoint.
            CapabilityKind.ImageGeneration ->
                keyed("openai", "DALL·E") ?: "Pollinations"
            // ElevenLabs is caught by the registry above; the device engine
            // is the floor beneath it.
            CapabilityKind.TextToSpeech -> "On-device"
            // These genuinely need a key — no fallback exists.
            CapabilityKind.VideoGeneration, CapabilityKind.World3DGeneration -> null
        }
    }
}
