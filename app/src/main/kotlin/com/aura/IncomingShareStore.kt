package com.aura

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tiny singleton that holds the most recent image shared into the app via the
 * share-sheet. MainActivity writes here, the ChatScreen reads via collection
 * and clears the slot.
 *
 * Uses a monotonically increasing sequence number so the collector can
 * detect new shares even when the same URI is shared twice in a
 * row — a nullable StateFlow alone can't distinguish "no new share"
 * from "same value shared again".
 *
 * Images are passed as a [Uri] rather than pre-decoded base64 so the
 * chat screen can decode the Bitmap at display resolution and route
 * it through [ChatViewModel.onImageCaptured] for vision analysis,
 * instead of dumping a wall of base64 text into the chat input.
 *
 * **Text no longer passes through here.** It went to the chat composer's draft
 * field, which meant backing out lost it — `consume()` had already cleared the
 * slot and nothing persisted it. Shared text now goes to
 * [com.aura.capture.CaptureActivity] and is written before anything is drawn.
 * The text slot is gone rather than left unused, because an unused slot in a
 * store whose whole job is one slot is an invitation to reintroduce the bug.
 */
@Singleton
class IncomingShareStore @Inject constructor() {

    data class SharePayload(
        val imageUri: Uri?,
        val seq: Long,
    )

    private val _pending = MutableStateFlow<SharePayload?>(null)
    val pending: StateFlow<SharePayload?> = _pending.asStateFlow()

    private var seqCounter = 0L

    fun setImageUri(uri: Uri) {
        _pending.value = SharePayload(imageUri = uri, seq = ++seqCounter)
    }

    /**
     * Atomically read + clear, so a stale share doesn't get re-applied.
     * Returns null if no share is pending.
     */
    fun consume(): SharePayload? {
        val v = _pending.value
        _pending.value = null
        return v
    }
}