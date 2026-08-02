package com.aura.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import com.aura.agent.ToolCall
import com.aura.agent.ToolContext
import com.aura.agent.ToolRegistry
import com.aura.agent.ToolResult
import com.aura.core.error.CrashLogger
import com.aura.documents.DocumentTextExtractor
import com.aura.tools.MAX_TRANSCRIPTION_AUDIO_BYTES
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.UUID
import android.util.Log

/**
 * Handles vision (image), audio (transcription), and document
 * (PDF/text extraction) media flows that used to live directly
 * in [ChatViewModel].
 *
 * Each method stages media into the conversation as a user turn
 * or tool call, executes the appropriate tool, and surfaces the
 * result back into [ChatUiState] via [state].
 *
 * Extracted from ChatViewModel to reduce its size (was 1204 lines)
 * and to isolate the media-handling I/O from the send pipeline.
 */
class ChatMediaController(
    private val application: Application,
    private val state: MutableStateFlow<ChatUiState>,
    private val toolRegistry: ToolRegistry,
    private val crashLogger: CrashLogger,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val documentTextExtractor: DocumentTextExtractor? = null,
    private val onSaveConversation: () -> Unit,
    private val onError: (String) -> Unit,
    private val onTriggerSend: () -> Unit = {},
) {
    /**
     * Capture/pick an image and stage it for vision. The user is
     * shown a small row of 3 chips (Describe / Read text / Translate)
     * above the input bar and picks the prompt they want. The
     * bitmap is held in [ChatUiState.pendingVisionBitmap] until a
     * chip is tapped, at which point [runVisionPrompt] is called
     * with the picked prompt and the bitmap is cleared.
     *
     * If no chips are visible (the user can still see the photo
     * in their gallery / camera roll), the default behavior — a
     * direct vision call — is preserved.
     */
    fun onImageCaptured(bitmap: Bitmap, question: String = "Describe this image in detail") {
        state.update { it.copy(pendingVisionBitmap = bitmap) }
        if (question != "Describe this image in detail") {
            runVisionPrompt(bitmap, question)
        }
    }

    /**
     * Send the staged bitmap to vision with the chosen prompt.
     * Clears the staged bitmap from state once the call starts.
     */
    fun runVisionPrompt(bitmap: Bitmap, question: String) {
        state.update { it.copy(pendingVisionBitmap = null) }
        scope.launch(Dispatchers.IO) {
            val base64 = bitmap.toBase64Jpeg()
            val tool = toolRegistry.get("vision") ?: run {
                state.update { it.copy(error = "vision tool not available") }
                return@launch
            }
            state.update { old ->
                old.copy(
                    conversation = old.conversation.addUser(question),
                    streaming = true,
                )
            }
            val toolCallId = UUID.randomUUID().toString()
            val result = tool.execute(
                ToolCall(
                    id = toolCallId,
                    name = "vision",
                    arguments = mapOf("image_base64" to base64, "prompt" to question),
                ),
                ToolContext(
                    conversationId = state.value.conversation.id,
                    memoryEnabled = !state.value.incognitoMode,
                ),
            )
            val text = when (result) {
                is ToolResult.Ok -> result.output
                is ToolResult.Error -> "Vision error: ${result.message}"
                is ToolResult.NeedsPermission -> "Permission needed: ${result.permission}"
                is ToolResult.NeedsApproval -> "Approval needed: ${result.rationale}"
            }
            state.update { old ->
                val conv = old.conversation
                    .attachCompletedToolTurn(toolCallId, "vision", "{}", text)
                    .addAssistant(text)
                old.copy(
                    conversation = conv,
                    streaming = false,
                )
            }
            onSaveConversation()
            onTriggerSend()
        }
    }

    /**
     * Clear a staged vision bitmap without sending it.
     */
    fun dismissPendingVision() {
        state.update { it.copy(pendingVisionBitmap = null) }
    }

    /**
     * Transcribe an audio file picked by the user and insert the text as a user
     * message so the agent can act on it.
     */
    fun onAudioPicked(uri: Uri) {
        scope.launch(Dispatchers.IO) {
            val resolver = application.contentResolver
            val knownSize = runCatching {
                resolver.query(
                    uri,
                    arrayOf(android.provider.OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
                }
            }.onFailure { Log.w("ChatMediaCtrl", "op failed: ${it.message}", it) }.getOrNull()
            if (knownSize != null && knownSize > MAX_TRANSCRIPTION_AUDIO_BYTES) {
                state.update { it.copy(error = "Audio is larger than the 25 MB limit.") }
                return@launch
            }

            val bytes = try {
                val stream = resolver.openInputStream(uri) ?: run {
                    state.update { it.copy(error = "failed to open audio") }
                    return@launch
                }
                stream.use { readStreamWithinLimit(it, MAX_TRANSCRIPTION_AUDIO_BYTES) }
            } catch (e: Exception) {
                state.update { it.copy(error = "failed to read audio: ${e.message}") }
                return@launch
            }
            if (bytes == null) {
                state.update { it.copy(error = "Audio is larger than the 25 MB limit.") }
                return@launch
            }
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val tool = toolRegistry.get("transcribe") ?: run {
                state.update { it.copy(error = "transcribe tool not available") }
                return@launch
            }
            state.update { it.copy(streaming = true) }
            val result = tool.execute(
                ToolCall(
                    id = UUID.randomUUID().toString(),
                    name = "transcribe",
                    arguments = mapOf("audio_base64" to base64, "language" to "en"),
                ),
                ToolContext(conversationId = state.value.conversation.id),
            )
            val text = when (result) {
                is ToolResult.Ok -> result.output
                is ToolResult.Error -> "Transcription error: ${result.message}"
                is ToolResult.NeedsPermission -> "Permission needed: ${result.permission}"
                is ToolResult.NeedsApproval -> "Approval needed: ${result.rationale}"
            }
            state.update { old ->
                old.copy(
                    conversation = old.conversation.addUser("🎤 $text"),
                    streaming = false,
                )
            }
            onSaveConversation()
        }
    }

    /**
     * Extract text from a picked document (PDF, DOCX, TXT, MD, CSV, JSON,
     * YAML, XML, HTML, source code) and insert it as a user message so the
     * agent can act on it. The message is prefixed with the file name so
     * the model knows where the content came from. Errors surface as a
     * non-blocking chat error.
     */
    fun onDocumentPicked(uri: Uri) {
        val extractor = documentTextExtractor ?: run {
            state.update { it.copy(error = "Document extraction is not available.") }
            return
        }
        // Stays on the caller's dispatcher (viewModelScope → Main): the
        // blocking work is already inside DocumentTextExtractor.extract,
        // which wraps its whole body in withContext(Dispatchers.IO). Adding
        // a second hop here would only move the state.update() calls off
        // Main for no benefit.
        scope.launch {
            state.update { it.copy(streaming = true) }
            val result = runCatching { extractor.extract(uri) }
            result.onFailure { e ->
                crashLogger.log(code = "document_extract", message = e.message ?: e.javaClass.simpleName)
                state.update { it.copy(streaming = false, error = "Could not read document: ${e.message ?: "unknown error"}") }
            }
            result.onSuccess { doc ->
                val prefix = "Attached document: ${doc.name}"
                val body = doc.text.take(12000)
                val suffix = if (doc.text.length > 12000) {
                    "\n\n[${doc.text.length - 12000} more characters truncated]"
                } else ""
                val message = "$prefix\n\n$body$suffix"
                state.update { old ->
                    old.copy(
                        conversation = old.conversation.addUser(message),
                        streaming = false,
                    )
                }
                onSaveConversation()
            }
        }
    }
}

private fun Bitmap.toBase64Jpeg(quality: Int = 85): String {
    val stream = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.JPEG, quality, stream)
    return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
}

/**
 * Read an InputStream up to [maxBytes]. Returns null if the stream
 * exceeds the limit, so callers can show a "file too large" error
 * without loading the entire file into memory.
 */
private fun readStreamWithinLimit(
    stream: java.io.InputStream,
    maxBytes: Long,
): ByteArray? {
    val buffer = ByteArray(8192)
    val output = java.io.ByteArrayOutputStream()
    var total = 0L
    while (true) {
        val read = stream.read(buffer)
        if (read == -1) break
        total += read
        if (total > maxBytes) return null
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
