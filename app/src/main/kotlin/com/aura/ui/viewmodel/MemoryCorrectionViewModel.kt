package com.aura.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.memory.CorrectionStore
import com.aura.memory.MemoryStore
import com.aura.provenance.ConversationProvenance
import com.aura.skills.SkillsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What Aura used to answer a turn, and the means to say it was wrong.
 *
 * Backs the recall sheet. It exists separately from `ChatViewModel` because
 * correcting a memory has nothing to do with holding a conversation, and
 * because the sheet's contents — the actual text of what was recalled — were
 * never loaded by anything: `MemoryRecallChip` has always accepted a
 * `recalledMemoryContents` list and the one call site has always passed none,
 * so the sheet said "Aura looked at its memories" and listed nothing.
 */
@HiltViewModel
class MemoryCorrectionViewModel @Inject constructor(
    private val memoryStore: MemoryStore,
    private val correctionStore: CorrectionStore,
    private val skillsStore: SkillsStore,
) : ViewModel() {

    data class RecalledItem(
        val id: String,
        val label: String,
        val detail: String,
        val isSkill: Boolean = false,
        /** Set once this item has been corrected, so the row can strike through. */
        val correctionId: String? = null,
    )

    data class State(
        val loading: Boolean = true,
        val items: List<RecalledItem> = emptyList(),
        /** The sentence describing the last correction, shown with an Undo. */
        val report: String? = null,
        val lastCorrectionId: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun load(memoryIds: List<String>, handIds: List<String>) {
        viewModelScope.launch {
            val memories = memoryIds.mapNotNull { id ->
                runCatching { memoryStore.get(id) }
                    .onFailure { Log.w(TAG, "could not load recalled memory $id", it) }
                    .getOrNull()
            }.map { mem ->
                RecalledItem(
                    id = mem.id,
                    label = mem.content,
                    detail = mem.category,
                    // A memory retired since the turn is already corrected;
                    // showing it as live would invite a second correction that
                    // does nothing.
                    correctionId = mem.retiredAt?.let { mem.id },
                )
            }
            val skills = handIds.mapNotNull { id ->
                runCatching { skillsStore.findById(id) }
                    .onFailure { Log.w(TAG, "could not load skill $id", it) }
                    .getOrNull()
            }.map { skill ->
                RecalledItem(id = skill.id, label = skill.name, detail = skill.description, isSkill = true)
            }
            _state.update { it.copy(loading = false, items = memories + skills) }
        }
    }

    fun neverTrue(memoryId: String, provenance: ConversationProvenance) = act(memoryId) {
        correctionStore.neverTrue(memoryId, provenance = provenance)
    }

    fun noLongerTrue(memoryId: String, replacement: String, provenance: ConversationProvenance) = act(memoryId) {
        correctionStore.noLongerTrue(memoryId, replacement, provenance = provenance)
    }

    fun irrelevantHere(memoryId: String, queryText: String, provenance: ConversationProvenance) = act(memoryId) {
        correctionStore.irrelevantHere(memoryId, queryText, provenance = provenance)
    }

    fun badAnswer(skillId: String, provenance: ConversationProvenance) = act(skillId) {
        correctionStore.badAnswer(skillId, provenance = provenance)
    }

    fun undo() {
        val id = _state.value.lastCorrectionId ?: return
        viewModelScope.launch {
            val report = runCatching { correctionStore.undo(id) }
                .onFailure { Log.w(TAG, "undo failed", it) }
                .getOrNull()
            _state.update { current ->
                current.copy(
                    report = report?.summary,
                    lastCorrectionId = null,
                    items = current.items.map { if (it.correctionId == id) it.copy(correctionId = null) else it },
                )
            }
        }
    }

    private fun act(itemId: String, block: suspend () -> CorrectionStore.Report) {
        viewModelScope.launch {
            val report = runCatching { block() }
                .onFailure { Log.w(TAG, "correction failed", it) }
                .getOrNull()
                ?: CorrectionStore.Report("", "Couldn't record that — try again.")
            _state.update { current ->
                current.copy(
                    report = report.summary,
                    lastCorrectionId = report.correctionId.takeIf { it.isNotBlank() },
                    items = current.items.map {
                        if (it.id == itemId && report.correctionId.isNotBlank()) {
                            it.copy(correctionId = report.correctionId)
                        } else {
                            it
                        }
                    },
                )
            }
        }
    }

    private companion object {
        const val TAG = "MemoryCorrectionVM"
    }
}
