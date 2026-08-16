package com.aura.ui.components

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "TextExport"

/** Staged files older than this are swept on the next export. */
private const val STALE_MS = 24L * 60 * 60 * 1000

/** Every file this writes starts here, which is also how the sweep finds them. */
private const val PREFIX = "aura-"

/**
 * Write [content] to a cache file and hand it to the system share chooser.
 *
 * Extracted from `HistoryScreen.shareMarkdown`, which was the fifth
 * hand-rolled copy of stage-file → FileProvider → `ACTION_SEND` in this app and
 * the only one shaped right for text. Rather than add a sixth for manuscripts,
 * this is the shared one; `HistoryScreen` now calls it too.
 *
 * The file route rather than `EXTRA_TEXT` is not a preference. `EXTRA_TEXT`
 * rides the Binder transaction buffer, which is shared across every in-flight
 * transaction in the process, so a large document fails unpredictably rather
 * than at a clean threshold. A `content://` URI is a hundred bytes whatever the
 * document weighs.
 *
 * Three things the original did not do, fixed once here instead of twice:
 *
 * - **Neither step was guarded.** `writeText` throws when the disk is full and
 *   `startActivity` throws when nothing can receive the intent; both took the
 *   caller down. `runCatching` at each, returning false, follows
 *   [shareImage] — the only one of the five that got this right.
 * - **Nothing was ever cleaned up**, despite the KDoc saying "and clean up
 *   after". Filenames carry a timestamp, so every share leaked a full copy into
 *   the cache permanently. The sweep runs on *entry*, never on exit: the read
 *   grant outlives this call, so deleting the file just shared would hand the
 *   receiving app an empty URI.
 * - **`startActivity` ran on `Dispatchers.IO`.** Only the write needs it.
 *
 * @param fileStem filename without extension — see [markdownFileName].
 * @return false if the file could not be written or nothing accepted the
 *   intent. Callers should surface that; a share that silently does nothing is
 *   indistinguishable from a button that is not wired up.
 */
suspend fun shareTextFile(
    context: Context,
    fileStem: String,
    content: String,
    mimeType: String = "text/markdown",
    extension: String = "md",
    subject: String = fileStem,
    chooserTitle: String = "Share",
): Boolean {
    val uri = withContext(Dispatchers.IO) {
        runCatching {
            sweepStale(context.cacheDir)
            val file = File(context.cacheDir, "$fileStem.$extension")
            file.writeText(content)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.onFailure { Log.w(TAG, "could not stage $fileStem for sharing: ${it.message}", it) }
            .getOrNull()
    } ?: return false

    val send = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, subject)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return runCatching {
        context.startActivity(
            Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrElse {
        Log.w(TAG, "no app accepted the share intent: ${it.message}", it)
        false
    }
}

/**
 * Delete previously staged exports older than [STALE_MS].
 *
 * Best-effort and deliberately silent: a cache sweep that fails is not a reason
 * to refuse the export the user actually asked for.
 */
private fun sweepStale(cacheDir: File, nowMs: Long = System.currentTimeMillis()) {
    runCatching {
        cacheDir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) }
            ?.filter { nowMs - it.lastModified() > STALE_MS }
            ?.forEach { it.delete() }
    }.onFailure { Log.w(TAG, "cache sweep failed: ${it.message}", it) }
}

/**
 * Filename stem for a shared Markdown document: `aura-<slug>-<timestamp>`.
 *
 * Timestamped so two exports in a row do not collide, which is also why the
 * sweep above has to exist — unlike [imageFileName], which is stable per URL
 * and therefore self-overwriting.
 *
 * `internal` so it is testable: it is the only part of the share path that can
 * be exercised without a device.
 */
internal fun markdownFileName(title: String, nowMs: Long = System.currentTimeMillis()): String {
    val safe = title.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(40)
        .ifBlank { "document" }
    val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMs))
    return "$PREFIX$safe-$ts"
}
