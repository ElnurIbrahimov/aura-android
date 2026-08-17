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

            val sealed = crypto.seal(backupManager.encodeToJson(backupManager.snapshot(version)), passphrase)
                ?: error("the stored passphrase is shorter than BackupCrypto allows")

            val bytes = write(Uri.parse(folder), backupManager.defaultExportFileName(now), sealed)
            val pruned = prune(Uri.parse(folder))

            userPreferences.recordBackupOutcome(at = now)
            lastOutcome = WorkerRunRecorder.Result.ok(
                "wrote ${bytes / 1024} KB" + if (pruned > 0) ", pruned $pruned old" else "",
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
        if (!dir.canWrite()) error("the backup folder is no longer writable — re-pick it in Settings")
        // Same name twice would otherwise create "name (1)", which breaks prune's
        // ordering and quietly doubles the folder.
        dir.findFile(name)?.delete()
        val file = dir.createFile(MIME, name) ?: error("could not create $name in the backup folder")
        val bytes = content.toByteArray(Charsets.UTF_8)
        appContext.contentResolver.openOutputStream(file.uri)?.use { it.write(bytes) }
            ?: error("could not open $name for writing")
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
        dir.listFiles()
            .filter { it.isFile && it.name.orEmpty().let { n -> n.startsWith(PREFIX) && n.endsWith(SUFFIX) } }
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
