package com.aura.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.memory.MemoryEditEntity
import com.aura.memory.MemoryFeedbackDao
import com.aura.memory.MemoryFeedbackEntity
import com.aura.memory.MemoryEntity
import com.aura.memory.MemoryStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.aura.evolution.EvolutionHooks
import java.util.UUID
import javax.inject.Inject

data class MemoryUiState(
    val memories: List<MemoryEntity> = emptyList(),
    val query: String = "",
    /** When non-null, restricts the list to memories in this category. */
    val categoryFilter: String? = null,
    val loading: Boolean = true,
    /**
     * True while [rebuildEmbeddings] is in progress. Disables the
     * button in the UI. After the rebuild completes, [rebuildResult]
     * holds the count of rows re-embedded (or null if not run).
     */
    val rebuildInFlight: Boolean = false,
    /**
     * Most recent rebuild summary, e.g. "Rebuilt 142 of 142
     * embeddings." Null until the first rebuild completes. The UI
     * renders this as a snackbar-style banner; the user can dismiss
     * it with [clearRebuildResult].
     */
    val rebuildResult: String? = null,
    val undoMessage: String? = null,
    val editHistoryMemoryId: String? = null,
    val editHistory: List<MemoryEditEntity> = emptyList(),
    val editHistoryLoading: Boolean = false,
    /**
     * Count of dream summaries ever written by [com.aura.dream.DreamWorker].
     * Surfaced as "X dream summaries" stat row in the Memory screen.
     * Source memories are NOT deleted in v1, so this is a
     * subset-of-row-count number, not a replacement.
     */
    val dreamSummaryCount: Int = 0,
    /**
     * Recent dream summaries for the "X dream summaries" dialog.
     * Loaded on-demand when the user taps the stat row. Limit 50
     * — summaries are concise, so a long list is fine to scroll
     * but we cap to avoid a memory hit on a multi-year install.
     */
    val dreamSummaries: List<com.aura.dream.DreamSummaryEntity> = emptyList(),
    val dreamSummariesLoading: Boolean = false,
    val routineCount: Int = 0,
    val contradictionCount: Int = 0,
)

@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val memoryStore: MemoryStore,
    private val feedbackDao: MemoryFeedbackDao,
    private val evolutionHooks: EvolutionHooks? = null,
    private val dreamConsolidationDao: com.aura.dream.DreamConsolidationDao? = null,
    private val routineDao: com.aura.dream.RoutineDao? = null,
    private val contradictionDao: com.aura.dream.ContradictionDao? = null,
) : ViewModel() {

    private val _state = MutableStateFlow(MemoryUiState())
    val state: StateFlow<MemoryUiState> = _state.asStateFlow()

    private data class DeletedMemory(
        val memory: MemoryEntity,
        val edits: List<MemoryEditEntity>,
    )

    private var searchJob: kotlinx.coroutines.Job? = null
    private var lastDeleted: DeletedMemory? = null

    init {
        refresh()
        // Auto-refresh the list whenever the memory dataset changes.
        // We observe a flow of the *list* (not just count) so we can
        // update the UI without setting loading=true on every change.
        viewModelScope.launch {
            memoryStore.observeCount().collect { refreshWithoutLoading() }
        }
        // Observe the dream summary count so the "X dream summaries" stat
        // in the Memory screen updates when DreamWorker writes new
        // summaries. Done as a separate flow (not via the count-based
        // refresh) so we don't re-fetch the memory list on every dream
        // cycle — dream writes are infrequent and don't invalidate the
        // memory list.
        viewModelScope.launch {
            runCatching { dreamConsolidationDao?.observeCount()?.collect { count ->
                _state.update { it.copy(dreamSummaryCount = count) }
            } }.onFailure { Log.w("MemoryViewModel", "dream count observe failed", it) }
        }
        // Observe the v2 dream phase outputs (routines + contradictions).
        // The count is the cheap stat the user sees in the Memory header;
        // the bodies are only loaded on demand (loadDreamSummaries or a
        // future tap-into-row action).
        viewModelScope.launch {
            runCatching { routineDao?.observeCount()?.collect { c ->
                _state.update { it.copy(routineCount = c) }
            } }.onFailure { Log.w("MemoryViewModel", "routine count observe failed", it) }
        }
        viewModelScope.launch {
            runCatching { contradictionDao?.observeUnresolvedCount()?.collect { c ->
                _state.update { it.copy(contradictionCount = c) }
            } }.onFailure { Log.w("MemoryViewModel", "contradiction count observe failed", it) }
        }
    }

    /**
     * Manual refresh (e.g., pull-to-refresh). Shows the loading skeleton.
     */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val current = _state.value
            val results = when {
                current.categoryFilter != null -> memoryStore.listByCategory(current.categoryFilter, 100)
                current.query.isNotBlank() -> memoryStore.searchByText(current.query, 50)
                else -> memoryStore.recent(100)
            }
            _state.update { it.copy(memories = results, loading = false) }
        }
    }

    /**
     * Refresh the current list while preserving the existing items so the
     * screen does not flash a loading skeleton when an automatic change
     * (e.g., the agent storing a new memory) triggers this.
     */
    private fun refreshWithoutLoading() {
        viewModelScope.launch {
            val current = _state.value
            val results = when {
                current.categoryFilter != null -> memoryStore.listByCategory(current.categoryFilter, 100)
                current.query.isNotBlank() -> memoryStore.searchByText(current.query, 50)
                else -> memoryStore.recent(100)
            }
            _state.update { it.copy(memories = results) }
        }
    }

    /**
     * Debounced text search. 250ms keeps Room from being hammered
     * on every keystroke. When the query is cleared, immediately
     * shows the recent list. When a category filter is active, the
     * text search is ignored (the two are mutually exclusive in v1).
     */
    fun setQuery(q: String) {
        _state.update { it.copy(query = q) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            // Small delay for non-empty queries to avoid thrashing Room on
            // every keystroke. Empty queries return immediately.
            val trimmed = q.trim()
            if (trimmed.isNotEmpty()) {
                _state.update { it.copy(loading = true) }
                kotlinx.coroutines.delay(250)
            }
            val results = when {
                _state.value.categoryFilter != null -> memoryStore.listByCategory(_state.value.categoryFilter!!, 100)
                trimmed.isNotBlank() -> memoryStore.searchByText(trimmed, 50)
                else -> memoryStore.recent(100)
            }
            _state.update { it.copy(memories = results, loading = false) }
        }
    }

    /**
     * Set a category filter. Pass null to clear (show all). Tapping a category
     * chip in the UI calls this with the category name; tapping "All" passes
     * null. The category filter takes precedence over the text query — the
     * two are mutually exclusive in the v1 UI.
     */
    fun setCategory(category: String?) {
        _state.update { it.copy(categoryFilter = category) }
        refresh()
    }

    fun search() {
        // Clear any category filter when a text search is explicitly triggered.
        if (_state.value.categoryFilter != null) {
            _state.update { it.copy(categoryFilter = null) }
        }
        refresh()
    }

    /**
     * Delete a memory, keeping a snapshot so [undoDelete] can put it back.
     *
     * The snapshot used to come from `recent(200).find { it.id == id }`, which
     * made deletion silently do nothing for anything outside the 200
     * newest-by-`createdAt` rows. The list on screen is not bounded that way —
     * search and category views return older rows freely — so the ordinary act
     * of searching for a memory and swiping it away hit `?: return@launch`,
     * skipped the delete, showed no message, and left the row in place after
     * `refresh()`. A memory you tried to forget kept being recalled into future
     * prompts. `get(id)` is an indexed primary-key lookup with no such bound.
     *
     * Deletion no longer depends on the snapshot succeeding: the destructive
     * intent is honoured either way, and undo degrades to unavailable rather
     * than taking the delete down with it. That coupling is how the bug got in
     * — the lookup was added to support undo and quietly became a precondition.
     */
    fun forget(id: String) {
        viewModelScope.launch {
            val memory = memoryStore.get(id)
            lastDeleted = memory?.let { DeletedMemory(it, memoryStore.getEditHistory(id)) }
            memoryStore.forget(id)
            _state.update { it.copy(undoMessage = if (lastDeleted != null) "Memory deleted" else null) }
            refresh()
        }
    }

    fun undoDelete() {
        val deleted = lastDeleted ?: return
        viewModelScope.launch {
            memoryStore.restore(deleted.memory, deleted.edits)
            lastDeleted = null
            _state.update { it.copy(undoMessage = null) }
            refresh()
        }
    }

    fun clearUndo() {
        _state.update { it.copy(undoMessage = null) }
        lastDeleted = null
    }

    /**
     * Delete all memories. Irreversible — the UI shows a confirm
     * dialog before calling this.
     */
    fun forgetAll() {
        viewModelScope.launch {
            memoryStore.forgetAll()
            _state.update { it.copy(categoryFilter = null, query = "") }
            refresh()
        }
    }

    /**
     * Delete all memories in [category]. Irreversible.
     */
    fun forgetByCategory(category: String) {
        viewModelScope.launch {
            memoryStore.forgetByCategory(category)
            refresh()
        }
    }

    /**
     * Edit a memory's content + category + importance + tags. Embedding
     * is invalidated by the store; the next recall will re-embed on
     * demand, or the user can run the "Rebuild embeddings" action.
     */
    fun update(id: String, content: String, category: String, importance: Float, tags: String) {
        viewModelScope.launch {
            memoryStore.update(id, content, category, importance, tags)
            refresh()
        }
    }

    fun loadEditHistory(memoryId: String) {
        _state.update {
            it.copy(
                editHistoryMemoryId = memoryId,
                editHistory = emptyList(),
                editHistoryLoading = true,
            )
        }
        viewModelScope.launch {
            val entries = memoryStore.getEditHistory(memoryId)
            _state.update { current ->
                if (current.editHistoryMemoryId != memoryId) current
                else current.copy(editHistory = entries, editHistoryLoading = false)
            }
        }
    }

    fun clearEditHistory() {
        _state.update {
            it.copy(
                editHistoryMemoryId = null,
                editHistory = emptyList(),
                editHistoryLoading = false,
            )
        }
    }

    /**
     * Re-embed every memory whose embedding is currently null.
     * After a backup import, every row has embedding=null and the
     * next recall would re-embed one at a time on demand — slow for
     * a fresh restore with hundreds of rows. This sweeps them all in
     * one pass.
     *
     * Idempotent: memories with an existing embedding are left
     * alone, so running it twice is a no-op the second time.
     *
     * Safe to re-enter: the second call waits for the first to
     * finish because [rebuildInFlight] is set synchronously at the
     * start of the function and the button in the UI is disabled
     * while true.
     */
    fun rebuildEmbeddings() {
        if (_state.value.rebuildInFlight) return
        _state.update { it.copy(rebuildInFlight = true) }
        viewModelScope.launch {
            val total = runCatching { memoryStore.count() }.onFailure { Log.w("MemoryViewModel", "runCatching failed: ${it.message}", it) }.getOrDefault(0)
            val rebuilt = runCatching { memoryStore.rebuildEmbeddings() }
                .onFailure { Log.w("MemoryViewModel", "runCatching failed: ${it.message}", it) }.getOrDefault(0)
            val msg = when {
                total == 0 -> "No memories to rebuild."
                rebuilt == 0 -> "All $total memories already have embeddings."
                rebuilt == total -> "Rebuilt $rebuilt embedding${if (rebuilt == 1) "" else "s"}."
                else -> "Rebuilt $rebuilt of $total embeddings (some failed)."
            }
            _state.update {
                it.copy(
                    rebuildInFlight = false,
                    rebuildResult = msg,
                )
            }
            refresh()
        }
    }

    fun clearRebuildResult() {
        _state.update { it.copy(rebuildResult = null) }
    }

    /**
     * Load the most recent dream summaries into [MemoryUiState.dreamSummaries]
     * for the "X dream summaries" dialog. Idempotent — calling twice
     * re-queries (the user might tap "Refresh" in a future iteration).
     */
    fun loadDreamSummaries() {
        if (_state.value.dreamSummariesLoading) return
        _state.update { it.copy(dreamSummariesLoading = true) }
        viewModelScope.launch {
            val rows = runCatching { dreamConsolidationDao?.all() ?: emptyList() }
                .onFailure { Log.w("MemoryViewModel", "runCatching failed: ${it.message}", it) }.getOrDefault(emptyList())
                .take(50)
            _state.update {
                it.copy(
                    dreamSummaries = rows,
                    dreamSummariesLoading = false,
                )
            }
        }
    }

    /**
     * Manually create a memory — bypasses the write gate and dedup
     * check. The user explicitly wants this stored, so it goes in
     * directly via [memoryStore.store]. Tagged with source="manual"
     * so the UI can distinguish agent-stored from user-stored notes.
     */
    fun createNote(content: String, category: String, importance: Float) {
        if (content.isBlank()) return
        viewModelScope.launch {
            runCatching {
                memoryStore.store(
                    content = content.trim(),
                    source = "manual",
                    category = category,
                    importance = importance,
                )
            }.onFailure { Log.w("MemVM", "op failed: ${it.message}", it) }
            refresh()
        }
    }

    fun renameCategory(oldCategory: String, newCategory: String) {
        viewModelScope.launch {
            runCatching { memoryStore.renameCategory(oldCategory, newCategory) }.onFailure { Log.w("MemoryViewModel", "rename category failed", it) }
            refresh()
        }
    }

    fun mergeCategories(source: String, target: String) {
        viewModelScope.launch {
            runCatching { memoryStore.mergeCategories(source, target) }.onFailure { Log.w("MemoryViewModel", "merge categories failed", it) }
            refresh()
        }
    }

    /**
     * Record user feedback on a memory. Writes a [MemoryFeedbackEntity] row
     * and emits an evolution signal so the synthesis engine can learn which
     * memories are useful.
     */
    fun submitFeedback(memoryId: String, helpful: Boolean, note: String = "") {
        viewModelScope.launch {
            runCatching {
                feedbackDao.insert(
                    MemoryFeedbackEntity(
                        id = UUID.randomUUID().toString(),
                        memoryId = memoryId,
                        kind = if (helpful) "upvote" else "downvote",
                        note = note,
                    )
                )
                evolutionHooks?.onMemoryFeedback(memoryId, helpful, note)
            }.onFailure { Log.w("MemoryViewModel", "feedback insert failed", it) }
        }
    }
}
