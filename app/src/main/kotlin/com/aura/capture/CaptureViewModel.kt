package com.aura.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.memory.MemoryStore
import com.aura.memory.WriteGate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Capture is a write, not a conversation.
 *
 * The only fast path into Aura before this was the widget's quick-ask, which
 * calls `startIsolatedSession`, sends the text to a model and waits for a
 * streamed answer — and refuses outright without a verified model. Capturing a
 * thought therefore cost a network round-trip and a configured provider. A
 * thought that lasts five seconds does not survive that.
 *
 * So nothing here touches a model, the network, or `ChatViewModel`. Text in,
 * row written, done. Anything clever happens later and must never block the
 * write.
 *
 * ## Why this bypasses the write gate
 *
 * [MemoryStore.storeIfAbsent] does not consult [WriteGate] — the same call
 * `RememberTool` uses. That is deliberate and it is what makes the conservative
 * gate safe: the gate exists to judge *incidental* chat, where the cost of a
 * false positive is a store full of "Hey you". A capture is the opposite of
 * incidental. The user selected the text and tapped a button called Aura; there
 * is nothing left to infer.
 *
 * The gate's *categoriser* is still reused, because it is free, has no model
 * call, and is the same logic that classifies everything else in the store.
 * Only its verdict is ignored.
 */
@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val memoryStore: MemoryStore,
) : ViewModel() {

    sealed interface State {
        /** Waiting for text — the tile and shortcut open here. */
        data object Composing : State

        /** Written. [id] is what Undo removes. */
        data class Saved(val id: String, val text: String) : State

        /** The row already existed; nothing was written, and nothing should be undone. */
        data class Duplicate(val text: String) : State

        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Composing)
    val state: StateFlow<State> = _state.asStateFlow()

    private val gate = WriteGate()

    /**
     * Where the text came from, which decides how far it is trusted.
     *
     * The class KDoc's reasoning — "the user selected the text and tapped a
     * button called Aura; there is nothing left to infer" — is true of text the
     * user typed or selected, and false of text that arrived in an intent.
     * `CaptureActivity` is `exported="true"` (it must be: `ACTION_PROCESS_TEXT`
     * throws otherwise, and the launcher shortcut targets it by action), and it
     * auto-captures incoming text from a `LaunchedEffect` before any tap. Any
     * co-installed app could therefore write rows into permanent memory with
     * `source = "user"` and the write gate skipped — the same trust level as a
     * sentence the user typed, later recalled into the system prompt.
     */
    enum class Origin {
        /** Typed into the sheet, or selected and sent through the OS toolbar. */
        USER,

        /** Delivered by an intent. A gesture may have caused it; nothing proves that. */
        RECEIVED,
    }

    fun capture(raw: String, origin: Origin = Origin.USER) {
        val text = raw.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                // The heuristic categoriser, without its verdict. evaluate()
                // returns category = "fact" on any rejection, which is exactly
                // the fallback we would have chosen anyway.
                val verdict = gate.evaluate(text, SOURCE)
                // Received text gets the gate's verdict *applied*, not just its
                // category read. The bypass is what makes a deliberate capture
                // reliable; there is nothing deliberate to protect when the
                // sender is an intent.
                if (origin == Origin.RECEIVED && !verdict.shouldStore) {
                    return@runCatching null
                }
                memoryStore.storeIfAbsent(
                    content = text,
                    // A distinct source so recall and the memory screen can tell
                    // "Elnur said this" from "something handed this to Aura".
                    source = if (origin == Origin.USER) SOURCE else SOURCE_RECEIVED,
                    category = verdict.category,
                    importance = if (origin == Origin.USER) IMPORTANCE else IMPORTANCE_RECEIVED,
                    tags = if (origin == Origin.USER) listOf("capture") else listOf("capture", "received"),
                )
            }.onSuccess { id ->
                // storeIfAbsent returns null when an identical memory already
                // exists. That is a success — the thought is in Aura — but it
                // must not offer Undo, which would delete a row this capture
                // did not create.
                _state.value = if (id == null) State.Duplicate(text) else State.Saved(id, text)
            }.onFailure {
                android.util.Log.w(TAG, "capture failed: ${it.message}", it)
                _state.value = State.Failed(it.message ?: "Could not save that.")
            }
        }
    }

    /** Remove exactly the row [capture] just wrote. */
    fun undo() {
        val saved = _state.value as? State.Saved ?: return
        viewModelScope.launch {
            runCatching { memoryStore.forget(saved.id) }
                .onFailure { android.util.Log.w(TAG, "undo failed: ${it.message}", it) }
            _state.value = State.Composing
        }
    }

    companion object {
        private const val TAG = "CaptureViewModel"

        /**
         * "user", matching the `remember` tool and the manual Memory-screen
         * note. Anything else would make deliberate captures invisible to the
         * scope filters and the correction flow.
         */
        const val SOURCE = "user"

        /** Same as `RememberTool` — the user asked for this to be kept. */
        const val IMPORTANCE = 0.7f

        /**
         * Text handed to Aura by an intent rather than typed or selected.
         *
         * A separate source, not a tag, because scope filters and the
         * correction flow both key on source — and the point is that this text
         * must never be indistinguishable from something the user said. Any
         * app on the device can reach `CaptureActivity`; none of them can make
         * their text read as the user's.
         */
        const val SOURCE_RECEIVED = "shared"

        /** Lower than a deliberate capture: nobody has vouched for this yet. */
        const val IMPORTANCE_RECEIVED = 0.4f
    }
}
