package com.aura.ui.viewmodel

import android.util.Log
import com.aura.agent.ConversationStore
import com.aura.kg.KnowledgeGraphRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Extracted conversation lifecycle controller for [ChatViewModel].
 *
 * Handles save/load/fork/clear/export/delete and KG node refresh so the
 * ViewModel can focus on chat stream orchestration.
 */
internal class ChatConversationController(
    private val _state: MutableStateFlow<ChatUiState>,
    private val conversationStore: ConversationStore,
    private val knowledgeGraphRepository: KnowledgeGraphRepository,
    private val scope: CoroutineScope,
    private val cancelSend: () -> Unit,
) {

    internal fun saveConversation() {
        if (_state.value.incognitoMode) return
        scope.launch {
            runCatching {
                conversationStore.save(_state.value.conversation)
            }.onFailure { e ->
                // Surface the first save failure per session as a
                // non-blocking warning. Subsequent failures don't
                // re-set the warning so the UI doesn't spam.
                if (_state.value.saveWarning == null) {
                    _state.update {
                        it.copy(saveWarning = "Conversation could not be saved: ${e.message ?: e.javaClass.simpleName}")
                    }
                }
            }
        }
    }


    fun loadConversation(id: String) {
        scope.launch {
            _state.update { it.copy(conversationLoading = true) }
            try {
                conversationStore.load(id)?.let { conv ->
                    // Re-derive generated images from the stored tool results.
                    // Conversations saved before that bug was fixed have an
                    // empty generatedImages on every turn, so their images
                    // never came back on replay even though the URLs were
                    // sitting in the tool results all along.
                    _state.update { it.copy(conversation = conv.withImagesFromToolResults()) }
                }
            } finally {
                _state.update { it.copy(conversationLoading = false) }
            }
        }
    }

    /**
     * Fork the current conversation from a specific turn index.
     * Creates a new conversation with turns up to [fromTurnIndex],
     * loads it, and saves. The original conversation is untouched.
     */

    fun forkConversation(fromTurnIndex: Int) {
        val convId = _state.value.conversation.id
        scope.launch {
            val forkId = conversationStore.fork(convId, fromTurnIndex)
            if (forkId != null) {
                conversationStore.load(forkId)?.let { conv ->
                    _state.update { it.copy(conversation = conv) }
                }
            }
        }
    }

    /**
     * Start a fresh session owned by a compact surface (widget, share sheet,
     * future quick actions) without allowing the asynchronous recent-chat load
     * to overwrite it. Execution still uses the full send controller and agent loop.
     */

    fun clearConversation() {
        cancelSend()
        val conv = _state.value.conversation
        _state.update {
            it.copy(
                conversation = conv.copy(turns = emptyList()),
                draft = "",
                error = null,
                errorRetryable = false,
                errorTyped = null,
                deepModeActive = false,
                inFlightToolCalls = emptyList(),
            )
        }
        scope.launch {
            // The one save that legitimately shrinks a conversation in place:
            // clearing keeps the id and empties the turns. Every other caller
            // gets the default, which refuses to overwrite a stored row that
            // already has more turns than the snapshot being written.
            conversationStore.save(_state.value.conversation, allowTruncation = true)
        }
    }

    /**
     * Insert a council synthesis as an assistant-authored turn in the
     * current conversation. Used when the user taps "Send to chat" from
     * the agent council screen.
     */

    fun exportConversation(): kotlin.String {
        val conv = _state.value.conversation
        val sb = StringBuilder()
        sb.appendLine("# ${conv.title.ifBlank { "Conversation" }}")
        sb.appendLine()
        for (turn in conv.turns) {
            turn.user?.let {
                sb.appendLine("## User")
                sb.appendLine()
                sb.appendLine(it)
                sb.appendLine()
            }
            turn.assistant?.let {
                sb.appendLine("## Aura")
                sb.appendLine()
                sb.appendLine(it)
                sb.appendLine()
            }
        }
        return sb.toString()
    }


    fun deleteCurrentConversation() {
        cancelSend()
        val convId = _state.value.conversation.id
        scope.launch {
            if (!_state.value.incognitoMode) {
                runCatching { conversationStore.delete(convId) }.onFailure { Log.w("ChatConversationController", "Delete conversation failed", it) }
            }
            _state.update {
                it.copy(
                    conversation = com.aura.agent.Conversation(
                        systemPrompt = "You are Aura, a personal AI assistant. Be concise and direct. Ask what they need help with.",
                        title = "New conversation",
                    ),
                    draft = "",
                    error = null,
                    errorRetryable = false,
                    errorTyped = null,
                    deepModeEnabled = false,
                    deepModeActive = false,
                    activeAgent = null,
                    activeAgentId = null,
                    inFlightToolCalls = emptyList(),
                )
            }
        }
    }


    internal fun refreshKgNodeCount() {
        scope.launch {
            runCatching {
                val count = knowledgeGraphRepository.stats().nodeCount
                if (count > _state.value.kgNodeCount) {
                    _state.update { it.copy(kgNodeCount = count) }
                }
            }.onFailure { Log.w("ChatConversationController", "KG stats refresh failed", it) }
        }
    }


}
