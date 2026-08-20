package com.aura.ui.viewmodel

import androidx.compose.runtime.Immutable
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.documents.DocumentEntity
import com.aura.documents.DocumentRepository
import com.aura.documents.DocumentTextExtractor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.util.Log

/**
 * Marked [Immutable] so Compose skips on `equals` instead of identity.
 *
 * Every one of these is republished as a fresh object on each change, so under strong
 * skipping an unstable state class meant a screen taking it recomposed on every publish
 * whether or not anything it read had changed. The promise holds: all properties are
 * `val`, and the collections are replaced through `copy()` — there is no `MutableList`
 * property anywhere in main sources and nothing mutates a state collection in place.
 *
 * It is a promise the compiler cannot check. A field that starts being mutated in place
 * will stop recomposing rather than fail to build.
 */
@Immutable
data class DocumentImportUiState(
    val documents: List<DocumentEntity> = emptyList(),
    /**
     * Documents the library lists but document search cannot find, because
     * they were imported before chunks had a writer. Shown as a per-row notice
     * so the count and the search result stop contradicting each other.
     */
    val unindexedDocumentIds: Set<String> = emptySet(),
    val importing: Boolean = false,
    val stage: String? = null,
    val message: String? = null,
    val error: String? = null,
)

@HiltViewModel
class DocumentImportViewModel @Inject constructor(
    private val extractor: DocumentTextExtractor,
    private val repository: DocumentRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(DocumentImportUiState())
    val state: StateFlow<DocumentImportUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeAll().collect { documents ->
                // Recomputed per emission rather than once: an import adds
                // chunks, and a document that has just been re-imported must
                // stop carrying the notice immediately.
                val missing = repository.idsMissingChunks()
                _state.update { it.copy(documents = documents, unindexedDocumentIds = missing) }
            }
        }
    }

    fun import(uri: Uri) {
        if (_state.value.importing) return
        viewModelScope.launch {
            _state.update {
                it.copy(importing = true, stage = "Reading document…", message = null, error = null)
            }
            runCatching {
                val extracted = extractor.extract(uri)
                _state.update { it.copy(stage = "Creating searchable memory…") }
                repository.import(
                    id = extracted.id,
                    name = extracted.name,
                    mimeType = extracted.mimeType,
                    sourceUri = extracted.sourceUri,
                    text = extracted.text,
                )
            }.onFailure { Log.w("DocImportVM", "op failed: ${it.message}", it) }.onSuccess { result ->
                _state.update {
                    it.copy(
                        importing = false,
                        stage = null,
                        message = "Imported ${result.document.name} · ${result.chunkCount} searchable chunks",
                    )
                }
            }.onFailure { failure ->
                _state.update {
                    it.copy(
                        importing = false,
                        stage = null,
                        error = failure.message ?: "Document import failed.",
                    )
                }
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            runCatching { repository.delete(id) }
                .onSuccess {
                    _state.update { it.copy(message = "Document and its memory chunks deleted.", error = null) }
                }
                .onFailure { failure ->
                    _state.update { it.copy(error = failure.message ?: "Could not delete the document.") }
                }
        }
    }

    fun clearNotice() {
        _state.update { it.copy(message = null, error = null) }
    }
}