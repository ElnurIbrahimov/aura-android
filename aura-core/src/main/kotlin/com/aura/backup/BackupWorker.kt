package com.aura.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aura.data.UserPreferences
import com.aura.health.WorkerRunRecorder
import com.aura.security.BackupCrypto
import com.aura.security.SecureDataStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Writes an encrypted copy of everything, to a folder that is not this phone.
 *
 * `allowBackup="false"` in the manifest is correct — Android's cloud backup
 * would hand the entire memory store to Google — but it left the only copy of
 * every memory, graph node, correction, belief and dream on one device, behind a
 * button somebody had to remember to press. This is the only failure mode in the
 * app that cannot be recovered from afterwards.
 *
 * The work itself was already written: [BackupManager.snapshot] and
 * [BackupManager.encodeToJson] are what the manual export has always used. What
 * this adds is a destination, a schedule, and a key that survives the device —
 * see [BackupCrypto], and note that the Keystore key everything else uses would
 * be exactly the wrong choice here.
 *
 * **Never returns `Result.retry()`.** WorkManager's backoff would re-run a full
 * database snapshot and a 200k-iteration key derivation on a phone that is
 * probably failing for a reason retrying will not fix — a revoked folder grant,
 * a full disk. It runs weekly; the next run is the retry, and the reason is
 * recorded where a person will see it.
 */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val backupManager: BackupService,
    private val userPreferences: UserPreferences,
    private val secureDataStore: SecureDataStore,
    private val recorder: WorkerRunRecorder? = null,
) : CoroutineWorker(appContext, params) {

    private val crypto = BackupCrypto()

    private var lastOutcome: WorkerRunRecorder.Result = WorkerRunRecorder.Result.ok("")

    override suspend fun doWork(): Result {
        // if/else, not `recorder?.record(...) ?: runNow()`. `record` returns null
        // when its block threw — so the elvis form would run the entire snapshot,
        // key derivation and write a second time on exactly the failure path.
        if (recorder != null) {
            recorder.record(WORKER_NAME) { runNow() to lastOutcome }
        } else {
            runNow()
        }
        // Always success: see the class KDoc on why this must not retry.
        return Result.success()
    }

    /** @return true when a file was written. */
    suspend fun runNow(now: Long = System.currentTimeMillis()): Boolean {
        if (!readFlag { userPreferences.autoBackupEnabled.first() }) {
            lastOutcome = WorkerRunRecorder.Result.skipped("automatic backup is switched off")
            return false
        }
        val folder = readOrNull { userPreferences.backupFolderUri.first() }
        if (folder.isNullOrBlank()) {
            lastOutcome = WorkerRunRecorder.Result.skipped("no backup folder has been chosen")
            return false
        }
        val passphrase = runCatching { secureDataStore.getString(UserPreferences.BACKUP_PASSPHRASE_KEY) }
            .onFailure { Log.w(TAG, "passphrase read failed", it) }
            .getOrNull()
        if (passphrase.isNullOrBlank()) {
            lastOutcome = WorkerRunRecorder.Result.skipped("no backup passphrase has been set")
            return false
        }

        return try {
            val version = runCatching {
                appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
            }.getOrNull().orEmpty()

            val snapshot = backupManager.snapshot(version)
            val sealed = crypto.seal(backupManager.encodeToJson(snapshot), passphrase)
                ?: error("the stored passphrase is shorter than BackupCrypto allows")

            val bytes = write(Uri.parse(folder), backupManager.defaultExportFileName(now), sealed)
            val pruned = prune(Uri.parse(folder))

            userPreferences.recordBackupOutcome(at = now)
            // A backup missing tables is still worth keeping — it is most of the data, and
            // the next run may well read them fine. What it must not do is report the same
            // thing a complete one reports. The file records the same list, so a restore
            // months from now can say what was never in it.
            val missing = snapshot.unreadableTables
            lastOutcome = WorkerRunRecorder.Result.ok(
                "wrote ${bytes / 1024} KB" +
                    (if (pruned > 0) ", pruned $pruned old" else "") +
                    (if (missing.isEmpty()) "" else " — ${missing.size} table(s) unreadable: ${missing.joinToString()}"),
            )
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            val reason = t.message ?: t::class.java.simpleName
            Log.w(TAG, "backup failed", t)
            // Recorded in two places on purpose. The run log answers "did it run";
            // the preference is what the Backup section of Settings shows, and a
            // backup that has been silently failing for a month is the failure
            // this whole worker exists to prevent.
            userPreferences.recordBackupOutcome(at = now, error = reason)
            lastOutcome = WorkerRunRecorder.Result(
                com.aura.health.WorkerRunEntity.OUTCOME_FAILED,
                reason,
            )
            false
        }
    }

    /** @return bytes written. */
    private suspend fun write(tree: Uri, name: String, content: String): Int = withContext(Dispatchers.IO) {
        val dir = DocumentFile.fromTreeUri(appContext, tree)
            ?: error("the backup folder could not be opened")
        writeInto(dir, name, content)
    }

    /**
     * Write [content] to [name] in [dir] without destroying what is already there first.
     *
     * The old order was delete-then-create, so anything that failed in between — a full
     * disk, a dropped network on a Drive-backed tree, a provider that simply refused
     * createFile — left the folder with neither the old backup nor the new one. The bytes
     * are already in memory by the time this is called, so there is nothing to gain by
     * clearing the way first.
     *
     * The replacement writes to a `.part` sibling, and only once those bytes are down does
     * it remove the old file and rename over it. A crash leaves a stray `.part`, which the
     * next run overwrites and [prune] ignores.
     *
     * `renameTo` is not universal across document providers. If it refuses, the bytes are
     * still in memory and the folder has just proven itself writable, so writing again
     * under the real name is the safe fallback rather than a lost backup.
     *
     * Separate from [write] and `internal` rather than private so the ordering can be
     * asserted against a mocked [DocumentFile]; resolving a real tree Uri is a device
     * concern and stays in the manual plan.
     *
     * @return bytes written.
     */
    internal suspend fun writeInto(dir: DocumentFile, name: String, content: String): Int =
        withContext(Dispatchers.IO) {
            if (!dir.canWrite()) error("the backup folder is no longer writable — re-pick it in Settings")
            val bytes = content.toByteArray(Charsets.UTF_8)
            val partName = "$name$PART_SUFFIX"
            // A .part left by a crashed run would otherwise become "name.part (1)".
            dir.findFile(partName)?.delete()
            val part = dir.createFile(MIME, partName)
                ?: error("could not create $partName in the backup folder")
            try {
                appContext.contentResolver.openOutputStream(part.uri)?.use { it.write(bytes) }
                    ?: error("could not open $partName for writing")
                // Same name twice would otherwise create "name (1)", which breaks prune's
                // ordering and quietly doubles the folder.
                dir.findFile(name)?.delete()
                if (!part.renameTo(name)) {
                    val file = dir.createFile(MIME, name)
                        ?: error("could not create $name in the backup folder")
                    appContext.contentResolver.openOutputStream(file.uri)?.use { it.write(bytes) }
                        ?: error("could not open $name for writing")
                    runCatching { part.delete() }
                }
            } catch (t: Throwable) {
                runCatching { part.delete() }
                throw t
            }
            bytes.size
        }

    /**
     * Keep [KEEP] most recent, delete the rest.
     *
     * Matches only this app's own file naming, so a folder the user also keeps
     * other things in does not lose them — the folder is theirs, and it is
     * likely to be one their cloud syncs.
     */
    private suspend fun prune(tree: Uri): Int = withContext(Dispatchers.IO) {
        val dir = DocumentFile.fromTreeUri(appContext, tree) ?: return@withContext 0
        val files = dir.listFiles().filter { it.isFile }
        // Export names carry a to-the-second timestamp, so a .part left by a crashed run
        // is never named again and nothing else would ever remove it. Swept here rather
        // than counted — the number reported to the user is about real backups.
        files.filter { it.name.orEmpty().let { n -> n.startsWith(PREFIX) && n.endsWith(PART_SUFFIX) } }
            .forEach { runCatching { it.delete() } }
        files
            .filter { it.name.orEmpty().let { n -> n.startsWith(PREFIX) && n.endsWith(SUFFIX) } }
            .sortedByDescending { it.lastModified() }
            .drop(KEEP)
            .count { it.delete() }
    }

    // Two names rather than an overload: `suspend () -> T?` and
    // `suspend () -> Boolean` are ambiguous to the resolver at the call site.
    private suspend fun <T> readOrNull(block: suspend () -> T?): T? =
        runCatching { block() }.onFailure { Log.w(TAG, "preference read failed", it) }.getOrNull()

    private suspend fun readFlag(block: suspend () -> Boolean): Boolean =
        runCatching { block() }.onFailure { Log.w(TAG, "preference read failed", it) }.getOrDefault(false)

    companion object {
        const val UNIQUE_NAME = "automatic-backup"
        const val WORKER_NAME = "BackupWorker"

        private const val TAG = "BackupWorker"
        /** Extension for the half-written file; [prune] must not count these as backups. */
        private const val PART_SUFFIX = ".part"

        private const val MIME = "application/octet-stream"

        /** Must match [BackupManager.defaultExportFileName]'s shape. */
        internal const val PREFIX = "aura-backup-"
        internal const val SUFFIX = ".json"

        /**
         * Three. Enough that a corrupt or half-written file is not the only copy,
         * few enough that a decade of weekly backups does not fill the folder.
         */
        internal const val KEEP = 3
    }
}
