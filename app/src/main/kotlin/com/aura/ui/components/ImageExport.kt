package com.aura.ui.components

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "ImageExport"

/**
 * Getting a generated image *out* of the chat.
 *
 * A generated image is a reference to something a provider hosts, and until
 * now that is all it ever was: it rendered, and there was nothing you could do
 * with it. No save, no share. The picture existed only for as long as the
 * conversation was on screen, which for a thing the user asked to be *made* is
 * the wrong end state.
 *
 * Both paths write the decoded bitmap rather than re-fetching the URL, so they
 * work for `file://` images too — [ImageGenTool] decodes an inline `b64_json`
 * response to a cache file, and those have no remote URL to hand anywhere.
 */

/**
 * A file name for the image at [url].
 *
 * Provider URLs end in an opaque hash (`.../t2i/b8d74297….png`), which is ugly
 * but unique — worth keeping, because it is the only thing distinguishing two
 * images saved seconds apart. Anything that is not a name gets replaced.
 */
internal fun imageFileName(url: String): String {
    val lastSegment = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
    val stem = lastSegment.substringBeforeLast('.', lastSegment)
        .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        .take(48)
    return if (stem.isBlank()) "aura-image.png" else "aura-$stem.png"
}

/**
 * Write [bitmap] into the device gallery, under `Pictures/Aura`.
 *
 * Returns false rather than throwing: the caller's only reasonable response is
 * to say so, and a crash on a save button is worse than a message.
 */
suspend fun saveImageToGallery(context: Context, bitmap: Bitmap, url: String): Boolean =
    withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, imageFileName(url))
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Aura")
                // Hide the row until the bytes are written, so the gallery
                // never shows a half-decoded image.
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val uri = runCatching { resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) }
            .onFailure { Log.w(TAG, "gallery insert rejected: ${it.message}", it) }
            .getOrNull()
            ?: return@withContext false

        runCatching {
            resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                ?: error("no output stream for $uri")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            true
        }.getOrElse {
            Log.w(TAG, "writing the image failed: ${it.message}", it)
            // Otherwise the gallery keeps an empty pending row forever.
            resolver.delete(uri, null, null)
            false
        }
    }

/**
 * Hand [bitmap] to the system share sheet.
 *
 * Goes through the app's existing FileProvider — the same authority the
 * diagnostics and history exports use — because a `content://` URI with a
 * one-shot read grant is the only form other apps will accept.
 */
suspend fun shareImage(context: Context, bitmap: Bitmap, url: String): Boolean {
    val uri = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "shared_images").apply { mkdirs() }
            val file = File(dir, imageFileName(url))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.onFailure { Log.w(TAG, "could not stage the image for sharing: ${it.message}", it) }
            .getOrNull()
    } ?: return false

    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return runCatching {
        context.startActivity(Intent.createChooser(send, "Share image").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrElse {
        Log.w(TAG, "no app accepted the share intent: ${it.message}", it)
        false
    }
}
