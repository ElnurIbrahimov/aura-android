package com.aura.ui.viewmodel

import com.aura.memory.MemoryStore
import com.aura.proactive.ProactiveMessageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Extracted UI-interaction controller for [ChatViewModel].
 *
 * Handles the "dismiss/approve/confirm" cluster of one-shot UI state
 * mutations that the agentic loop surfaces:
 * - Gate answers (pendingGate: permission / confirmation / approval)
 * - In-app browser dismissal (pendingBrowserUrl)
 * - Canvas/Artifacts dismissal and save-to-memory
 * - Proactive message loading and dismissal
 *
 * Each method is a pure state mutation (or a single coroutine launch
 * for I/O). The controller takes [MutableStateFlow] and the few
 * dependencies it needs — not the full ViewModel.
 *
 * The [resumeGate] callback resumes the paused agentic run after an
 * approval or confirmation — the loop replays the held tool with the
 * grant in ToolContext. [denyGate] drops the held tool without
 * resuming.
 */
class ChatInteractionController(
    private val state: MutableStateFlow<ChatUiState>,
    private val memoryStore: MemoryStore,
    private val proactiveMessageStore: ProactiveMessageStore?,
    private val scope: CoroutineScope,
    private val resumeGate: () -> Unit,
    private val denyGate: () -> Unit,
) {

    // --- Gate answers ---

    /**
     * User approved a REMOTE_COST tool. Resumes the paused run — the
     * loop re-executes the held tool with the approval in ToolContext.
     */
    fun approveRemoteCost() {
        resumeGate()
    }

    /**
     * User confirmed a confirmation-gated tool. Resumes the paused run —
     * the loop re-executes the held tool with the confirmation grant.
     */
    fun confirmTool(@Suppress("UNUSED_PARAMETER") toolName: String) {
        resumeGate()
    }

    /** User dismissed the approval/confirmation dialog. */
    fun dismissApproval() {
        denyGate()
    }

    // --- UI surface dismissals ---

    /** Clear the pending browser URL (user closed the in-app browser). */
    fun dismissBrowser() {
        state.update { it.copy(pendingBrowserUrl = null) }
    }

    /** Clear the pending canvas (user closed the canvas sheet). */
    fun dismissCanvas() {
        state.update { it.copy(pendingCanvas = null) }
    }

    /** Clear the proactive message from UI state. The store was already consumed in [loadProactiveMessage]. */
    fun dismissProactiveMessage() {
        state.update { it.copy(proactiveMessage = null) }
    }

    // --- Proactive message loading ---

    /** Load proactive message if one is waiting. Called on chat open. */
    fun loadProactiveMessage() {
        scope.launch {
            runCatching {
                val msg = proactiveMessageStore?.consumeMessage()
                if (!msg.isNullOrBlank()) {
                    state.update { it.copy(proactiveMessage = msg) }
                }
            }.onFailure { android.util.Log.w("ChatInteraction", "proactive load: ${it.message}", it) }
        }
    }

    // --- Canvas save ---

    /** Save canvas content as a memory. */
    fun saveCanvasToMemory(content: String) {
        scope.launch {
            runCatching {
                memoryStore.store(content, source = "canvas", category = "canvas", importance = 0.7f)
            }.onFailure { android.util.Log.w("ChatInteraction", "canvas save failed: ${it.message}", it) }
        }
        dismissCanvas()
    }
}