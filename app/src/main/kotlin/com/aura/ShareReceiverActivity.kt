package com.aura

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val payload = extractPayload(intent)
        if (payload.text.isNullOrBlank() && payload.imageUri == null) {
            finish()
            return
        }
        // Text goes to capture, not to a draft.
        //
        // It used to land in the chat composer's draft via IncomingShareStore,
        // which meant backing out lost it — consume() had already cleared the
        // slot and nothing persisted the text. Sharing something into a memory
        // app and having it silently discarded is the worst possible outcome
        // for the one capture path that existed.
        //
        // CaptureActivity writes it immediately and still offers "Ask about
        // this", so asking stays available without being the price of keeping.
        val text = payload.text
        val next = if (text != null) {
            Intent(this, com.aura.capture.CaptureActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(com.aura.capture.CaptureActivity.EXTRA_TEXT, text)
            }
        } else {
            // Images still go to chat: they need decoding at display resolution
            // and routing through the vision tool, which is a conversation.
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                payload.imageUri?.let { putExtra(EXTRA_SHARED_IMAGE_URI, it) }
            }
        }
        startActivity(next)
        finish()
    }

    private data class SharePayload(val text: String?, val imageUri: Uri?)

    private fun extractPayload(intent: Intent?): SharePayload {
        if (intent == null) return SharePayload(null, null)
        if (intent.action != Intent.ACTION_SEND) return SharePayload(null, null)

        // Image share: pass the URI directly so ChatScreen can decode
        // the Bitmap at display resolution and route it through
        // onImageCaptured() for vision analysis — no base64 wall.
        val imageUri: Uri? = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        if (imageUri != null && contentResolver.getType(imageUri)?.startsWith("image/") == true) {
            return SharePayload(text = null, imageUri = imageUri)
        }

        // Text share
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: ""
        val combined = if (subject.isNotBlank() && text.isNotBlank()) "$subject\n\n$text"
        else text.ifBlank { subject }
        return SharePayload(
            text = combined.ifBlank { null },
            imageUri = null,
        )
    }

    companion object {
        // EXTRA_SHARED_TEXT is gone: shared text now goes straight to
        // CaptureActivity rather than being forwarded to MainActivity as a
        // draft. Leaving the constant would have left an extra nobody sends
        // and a branch in MainActivity nobody reaches.
        const val EXTRA_SHARED_IMAGE_URI = "com.aura.SHARED_IMAGE_URI"
    }
}
