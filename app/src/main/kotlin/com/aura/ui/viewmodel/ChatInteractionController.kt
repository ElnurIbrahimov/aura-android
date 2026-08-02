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
 * - Permission requests (NeedsPermission)
 * - REMOTE_COST tool approval (pendingApproval)
 * - IMPLICIT/BIOMETRIC tool confirmation
 * - In-app browser dismissal (pendingBrowserUrl)
 * - Canvas/Artifacts dismissal and save-to-memory
 * - Proactive message loading and dismissal
 *
 * Each method is a pure state mutation (or a single coroutine launch
 * for I/O). The controller takes [MutableStateFlow] and the few
 * dependencies it needs — not the full ViewModel.
 *
 * The [reSend] callback re-engages the send pipeline after an approval
 * or confirmation, so the approved tool re-executes with the updated
 * ToolContext.
 */
class ChatInteractionController(
    private val state: MutableStateFlow<ChatUiState>,
    private val memoryStore: MemoryStore,
    private val proactiveMessageStore: ProactiveMessageStore?,
    private val scope: CoroutineScope,
    private val reSend: () -> Unit,
) {

    // --- Permission / approval ---

    /** Dismiss the permission request dialog and clear the pending retry. */
    fun dismissPermission() {
        state.update { it.copy(pendingPermission = null, permissionRationale = null, pendingToolRetry = null) }
    }

    /**
     * User approved a REMOTE_COST tool. Add it to the per-conversation
     * approved set and re-engage the model so the tool re-executes
     * with the approved set in ToolContext.
     */
    fun approveRemoteCost() {
        val pending = state.value.pendingApproval ?: return
        val toolName = pending.first
        state.update {
            it.copy(
                pendingApproval = null,
                approvedRemoteCostTools = it.approvedRemoteCostTools + toolName,
            )
        }
        reSend()
    }

    /**
     * User confirmed an IMPLICIT/BIOMETRIC tool. Dismiss the dialog and
     * re-engage so the tool executes with the user's confirmation in context.
     */
    fun confirmTool(@Suppress("UNUSED_PARAMETER") toolName: String) {
        state.update { it.copy(pendingApproval = null) }
        reSend()
    }

    /** User dismissed the REMOTE_COST approval dialog. */
    fun dismissApproval() {
        state.update { it.copy(pendingApproval = null) }
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