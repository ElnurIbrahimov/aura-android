package com.aura.ui.viewmodel

import com.aura.providers.ModelCatalog
import com.aura.providers.ModelCatalogRepository
import com.aura.providers.ProviderStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Controller for model catalog management. Owns the model list refresh,
 * catalog application, and model selection state resolution.
 *
 * Extracted from ChatViewModel to reduce its line count and isolate
 * the model catalog logic (which is independent of the send pipeline,
 * conversation management, and media handling).
 */
internal class ChatModelController(
    private val state: MutableStateFlow<ChatUiState>,
    private val modelCatalogRepository: ModelCatalogRepository?,
) {

    /**
     * Re-fetch the model list from every configured provider. Safe
     * to call repeatedly — concurrent calls are no-ops (we check
     * [ChatUiState.modelsLoading] first). Sets [ChatUiState.modelsLoading]
     * while in flight, surfaces the last error on failure so the
     * picker can show "tap to retry" instead of a silent empty list.
     */
    fun refreshModels() {
        val repository = modelCatalogRepository
        if (repository == null) {
            state.update {
                it.copy(
                    modelSelection = ModelSelectionState.Failed(
                        activeModel = it.activeModel.takeIf(String::isNotBlank),
                        models = it.availableModels,
                        message = "Model catalog is unavailable.",
                    ),
                    modelsError = "Model catalog is unavailable.",
                )
            }
            return
        }
        val current = state.value
        state.update {
            it.copy(
                modelsLoading = true,
                modelsError = null,
                modelSelection = ModelSelectionState.Loading(
                    current.activeModel.takeIf(String::isNotBlank),
                    current.availableModels,
                ),
            )
        }
        repository.refresh(force = true)
    }

    /**
     * Apply a fresh catalog snapshot to UI state. Called from the
     * catalog collector in the ViewModel's init block and from the
     * defaultModel collector.
     */
    fun applyModelCatalog(catalog: ModelCatalog?) {
        val current = state.value
        // effectiveModel, not activeModel: an open conversation's own model
        // is what the header shows and what the send path uses. Reading
        // activeModel here made the banner claim no model was chosen while
        // the header displayed one and sending worked fine.
        val resolved = current.effectiveModel
        if (catalog == null) {
            val selection = if (resolved.isBlank()) {
                ModelSelectionState.Missing
            } else {
                ModelSelectionState.Failed(
                    resolved,
                    current.availableModels,
                    "Model catalog is unavailable.",
                )
            }
            state.update { it.copy(modelSelection = selection) }
            return
        }

        // Chat picker: chat-usable models only. The catalog now also carries
        // image, video and speech models so capability backends can be
        // discovered from it — offering one here is the HTTP 400 that started
        // all this ("Model agnes-image-2.1-flash is an image model").
        val models = catalog.allModels.filter { it.capability.isChatUsable }
            .map { it.id }.distinct().sorted()
        val selection = resolveModelSelection(resolved, catalog)
        state.update {
            it.copy(
                availableModels = models,
                modelsLoading = selection is ModelSelectionState.Loading,
                modelsError = (selection as? ModelSelectionState.Failed)?.message,
                modelSelection = selection,
            )
        }
    }
}