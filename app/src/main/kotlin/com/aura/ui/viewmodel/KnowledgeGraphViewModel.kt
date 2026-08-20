package com.aura.ui.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.kg.KgEdge
import com.aura.kg.KgNode
import com.aura.kg.KnowledgeGraphRepository
import com.aura.kg.NodeType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import android.util.Log

/** Relation plus the human-readable label of the node at its other end. */
data class ResolvedKgRelation(
    val edge: KgEdge,
    val otherNodeId: String,
    val otherLabel: String,
)

data class SelectedKgNode(
    val node: KgNode,
    val incoming: List<ResolvedKgRelation>,
    val outgoing: List<ResolvedKgRelation>,
)

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
data class KnowledgeGraphUiState(
    val nodes: List<KgNode> = emptyList(),
    /** Complete loaded graph slice; used for relation labels and merge targets. */
    val allNodes: List<KgNode> = emptyList(),
    val stats: KnowledgeGraphRepository.Stats = KnowledgeGraphRepository.Stats(0, 0),
    val query: String = "",
    val typeFilter: NodeType? = null,
    val loading: Boolean = true,
    val mutating: Boolean = false,
    val selected: SelectedKgNode? = null,
    val error: String? = null,
)

@HiltViewModel
class KnowledgeGraphViewModel @Inject constructor(
    private val repository: KnowledgeGraphRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(KnowledgeGraphUiState())
    val state: StateFlow<KnowledgeGraphUiState> = _state.asStateFlow()

    private var allNodes: List<KgNode> = emptyList()
    private var searchJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            runCatching {
                val nodes = repository.recent(500)
                val stats = repository.stats()
                nodes to stats
            }.onFailure { Log.w("KGVM", "op failed: ${it.message}", it) }.onSuccess { (nodes, stats) ->
                allNodes = nodes
                _state.update {
                    it.copy(
                        nodes = filteredNodes(nodes, it.query, it.typeFilter),
                        allNodes = nodes,
                        stats = stats,
                        loading = false,
                        error = null,
                    )
                }
            }.onFailure { failure ->
                _state.update {
                    it.copy(
                        loading = false,
                        error = failure.message ?: failure.javaClass.simpleName,
                    )
                }
            }
        }
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(250)
            applyFilters()
        }
    }

    fun setTypeFilter(type: NodeType?) {
        _state.update { it.copy(typeFilter = type) }
        applyFilters()
    }

    fun selectNode(node: KgNode) {
        viewModelScope.launch {
            runCatching { repository.getNeighbors(node.id) }.onFailure { Log.w("KnowledgeGraphViewModel", "runCatching failed: ${it.message}", it) }
                .onSuccess { neighbors ->
                    val labels = allNodes.associate { it.id to it.label }
                    _state.update {
                        it.copy(
                            selected = SelectedKgNode(
                                node = node,
                                incoming = neighbors.incoming.map { edge ->
                                    ResolvedKgRelation(
                                        edge = edge,
                                        otherNodeId = edge.sourceId,
                                        otherLabel = labels[edge.sourceId] ?: edge.sourceId,
                                    )
                                },
                                outgoing = neighbors.outgoing.map { edge ->
                                    ResolvedKgRelation(
                                        edge = edge,
                                        otherNodeId = edge.targetId,
                                        otherLabel = labels[edge.targetId] ?: edge.targetId,
                                    )
                                },
                            ),
                            error = null,
                        )
                    }
                }
                .onFailure(::surfaceFailure)
        }
    }

    fun dismissNode() {
        _state.update { it.copy(selected = null) }
    }

    fun updateNode(id: String, label: String, type: NodeType, properties: JsonObject) {
        mutate {
            repository.updateNode(id, label, type, properties)
        }
    }

    fun mergeNode(sourceId: String, targetId: String) {
        mutate {
            repository.mergeNodes(sourceId, targetId)
        }
    }

    fun deleteNode(id: String) {
        mutate {
            repository.deleteNode(id)
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    private fun mutate(block: suspend () -> Unit) {
        if (_state.value.mutating) return
        _state.update { it.copy(mutating = true) }
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess {
                    _state.update { it.copy(mutating = false, selected = null, error = null) }
                    refresh()
                }
                .onFailure { failure ->
                    _state.update {
                        it.copy(
                            mutating = false,
                            error = failure.message ?: failure.javaClass.simpleName,
                        )
                    }
                }
        }
    }

    private fun applyFilters() {
        val current = _state.value
        _state.update {
            it.copy(nodes = filteredNodes(allNodes, current.query, current.typeFilter))
        }
    }

    private fun filteredNodes(
        source: List<KgNode>,
        query: String,
        type: NodeType?,
    ): List<KgNode> {
        val needle = query.trim().lowercase()
        return source.filter { node ->
            (type == null || node.type == type) &&
                (needle.isBlank() ||
                    node.label.lowercase().contains(needle) ||
                    node.type.name.lowercase().contains(needle) ||
                    node.properties.toString().lowercase().contains(needle))
        }
    }

    private fun surfaceFailure(failure: Throwable) {
        _state.update {
            it.copy(error = failure.message ?: failure.javaClass.simpleName)
        }
    }
}
