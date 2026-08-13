package com.aura.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.BuildConfig
import com.aura.backup.BackupManager
import com.aura.data.UserPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class BackupUiState(
    val exportInFlight: Boolean = false,
    val importInFlight: Boolean = false,
    val lastResult: String? = null,
    val pendingImportBytes: String? = null,
    val showImportConfirm: Boolean = false,
    val showPurgeConfirm: Boolean = false,
    // ---- Automatic backup ----
    val autoBackupEnabled: Boolean = false,
    /** Display form of the chosen SAF tree, or blank when none is set. */
    val backupFolderLabel: String = "",
    /** The passphrase itself never reaches the UI — only whether one exists. */
    val passphraseSet: Boolean = false,
    val lastBackupAt: Long = 0L,
    val lastBackupError: String = "",
)

/**
 * View model for the Backup & restore section in Settings.
 *
 * Export: snapshot the local DB, encode to JSON, write to a file in
 * the app cache, surface as a share Intent.
 *
 * Import: read the JSON the user picked, surface a confirmation
 * dialog, then write the rows back. The destructive "purge all
 * first" option is exposed for users who want a clean restore.
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupManager: BackupManager,
    private val userPreferences: com.aura.data.UserPreferences,
    private val secureDataStore: com.aura.security.SecureDataStore,
    private val scheduler: com.aura.proactive.ProactiveScheduler,
) : ViewModel() {

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(
                userPreferences.autoBackupEnabled,
                userPreferences.backupFolderUri,
                userPreferences.lastBackupAt,
                userPreferences.lastBackupError,
            ) { on, folder, at, error ->
                listOf(on, folder, at, error)
            }.collect { (on, folder, at, error) ->
                _state.update {
                    it.copy(
                        autoBackupEnabled = on as Boolean,
                        backupFolderLabel = folderLabel(folder as String?),
                        lastBackupAt = at as Long,
                        lastBackupError = error as String,
                    )
                }
            }
        }
        viewModelScope.launch { refreshPassphraseSet() }
    }

    /**
     * The last path segment of the tree URI, percent-decoded.
     *
     * A raw `content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FAura`
     * tells a person nothing about whether they picked the right folder, which is
     * the only question this row exists to answer.
     */
    private fun folderLabel(uri: String?): String {
        if (uri.isNullOrBlank()) return ""
        return runCatching {
            android.net.Uri.decode(uri).substringAfterLast(':').substringAfterLast('/')
                .ifBlank { android.net.Uri.decode(uri).substringAfterLast(':') }
        }.getOrDefault("")
    }

    private suspend fun refreshPassphraseSet() {
        val set = runCatching { secureDataStore.getString(UserPreferences.BACKUP_PASSPHRASE_KEY) }
            .getOrNull()
            .isNullOrBlank()
            .not()
        _state.update { it.copy(passphraseSet = set) }
    }

    /** Persist the SAF grant and remember the tree. The caller takes the permission. */
    fun setBackupFolder(uri: android.net.Uri) {
        viewModelScope.launch {
            runCatching { userPreferences.setBackupFolderUri(uri.toString()) }
                .onFailure { android.util.Log.w(TAG, "could not save the backup folder", it) }
        }
    }

    /**
     * Store the passphrase, or clear it when blank.
     *
     * It goes to [com.aura.security.SecureDataStore] rather than the plaintext
     * preference store: the whole security of an off-device backup file reduces
     * to this string, and the DataStore next to it is readable by anything with
     * the device unlocked.
     *
     * A weekly unattended job cannot prompt for a passphrase, so the device has
     * to know it. That is fine and is not the threat this defends against — the
     * point is that the *file*, sitting in a synced folder, is not readable
     * without it.
     */
    fun setBackupPassphrase(passphrase: String) {
        viewModelScope.launch {
            runCatching {
                if (passphrase.isBlank()) {
                    secureDataStore.removeString(UserPreferences.BACKUP_PASSPHRASE_KEY)
                    // A backup that cannot be sealed must not stay scheduled and
                    // silently skip every week.
                    userPreferences.setAutoBackupEnabled(false)
                } else {
                    secureDataStore.putString(UserPreferences.BACKUP_PASSPHRASE_KEY, passphrase)
                }
            }.onFailure { android.util.Log.w(TAG, "could not save the backup passphrase", it) }
            refreshPassphraseSet()
        }
    }

    fun setAutoBackupEnabled(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { userPreferences.setAutoBackupEnabled(enabled) }
                .onFailure { android.util.Log.w(TAG, "could not change the backup schedule", it) }
        }
    }

    /** Run one now, through the scheduled worker. See `ProactiveScheduler.requestBackupNow`. */
    fun runBackupNow() {
        runCatching { scheduler.requestBackupNow() }
            .onFailure { android.util.Log.w(TAG, "could not request a backup", it) }
    }

    init {
        // A marker still on disk means a restore started and never finished —
        // process death partway through about forty-five independent
        // transactions across eleven databases. The database is some mixture of
        // the file and what was there before, and nothing else in the app can
        // tell. Surfacing it here, where restore lives, is the honest place.
        //
        // Read on the constructing thread on purpose: the file is a few dozen
        // bytes and usually absent, and deferring it to a coroutine would race
        // the first composition that reads `lastResult`.
        backupManager.consumeInterruptedRestore()?.let { pending ->
            _state.update {
                it.copy(
                    lastResult = "A restore from \"${pending.sourceVersion}\" was interrupted before it " +
                        "finished (${pending.mode.lowercase()}). Your data may be part-restored. " +
                        (
                            if (pending.rollbackAvailable) "Re-run the restore to finish it."
                            else "No rollback snapshot was available; re-run the restore to finish it."
                            ),
                )
            }
        }
    }

    /**
     * Build the export file in the app cache and expose its URI
     * via [exportFile]. Caller is responsible for launching the
     * share Intent (e.g. via a chooser). We return the file rather
     * than a Uri because the caller is a Composable and the
     * `FileProvider.getUriForFile` step is best done with
     * [com.aura.tools.ShareIntentTool] or a manual FileProvider
     * reference.
     */
    suspend fun prepareExportFile(): java.io.File? = withContext(Dispatchers.IO) {
        _state.update { it.copy(exportInFlight = true, lastResult = null) }
        try {
            // Each export leaves a full plaintext copy of the database in the
            // cache. Pruning at the moment of creating the next one is the only
            // point that is guaranteed to run whenever they accumulate.
            backupManager.pruneCacheExports()
            val backup = backupManager.snapshot(appVersionName = BuildConfig.VERSION_NAME)
            val json = backupManager.encodeToJson(backup)
            val file = backupManager.exportFile().apply {
                writeText(json)
            }
            val exportedCount = backup.memories.size + backup.conversations.size +
                backup.knowledgeGraph.nodes.size + backup.knowledgeGraph.edges.size +
                backup.hands.size + backup.tasks.size +
                if (backup.userProfile != null) 1 else 0
            _state.update { it.copy(exportInFlight = false, lastResult = "Exported $exportedCount rows to ${file.name}") }
            file
        } catch (e: Exception) {
            _state.update { it.copy(exportInFlight = false, lastResult = "Export failed: ${e.message ?: e.javaClass.simpleName}") }
            // Return null instead of rethrowing — the caller is a
            // Composable coroutineScope, and an unhandled exception
            // would crash the app.
            null
        }
    }

    /**
     * Stage an import. The user has picked a file via the system
     * document picker; we read its bytes into memory and surface a
     * confirmation. We hold the bytes in memory (not on disk) so
     * the user has to explicitly confirm before we mutate their DB.
     */
    fun stageImport(uri: Uri) {
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("Could not open file")
                }
                val text = bytes.toString(Charsets.UTF_8)
                // Validate before showing the confirm dialog so the
                // user doesn't get a parse error after clicking Yes.
                // Keep the decoded backup so confirmImport doesn't have
                // to parse the JSON a second time.
                stagedBackup = withContext(Dispatchers.IO) { backupManager.decodeFromJson(text) }
                _state.update {
                    it.copy(
                        pendingImportBytes = text,
                        showImportConfirm = true,
                        lastResult = "Ready to restore. Read ${bytes.size / 1024} KB.",
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(lastResult = "Import failed: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    /** Decoded staged import, kept off UI state (it can be large). */
    private var stagedBackup: com.aura.backup.AuraBackup? = null

    fun cancelImport() {
        stagedBackup = null
        _state.update { it.copy(showImportConfirm = false, pendingImportBytes = null) }
    }

    /**
     * Confirm the staged import. Writes the rows back. Does NOT
     * purge first — that's a separate step for users who want a
     * clean restore.
     */
    /**
     * @param replace true for [BackupManager.RestoreMode.REPLACE].
     *
     * The purge no longer happens here. `restore` owns it, because a purge run
     * outside the guarded write phase is a purge with no rollback behind it —
     * exactly the shape this wave exists to remove.
     */
    fun confirmImport(replace: Boolean) {
        val bytes = _state.value.pendingImportBytes ?: return
        _state.update { it.copy(importInFlight = true, showImportConfirm = false) }
        viewModelScope.launch {
            try {
                // Reuse the backup decoded at stage time; fall back to
                // decoding only if staging state was lost (process death).
                val backup = stagedBackup
                    ?: withContext(Dispatchers.IO) { backupManager.decodeFromJson(bytes) }
                stagedBackup = null
                val mode = if (replace) BackupManager.RestoreMode.REPLACE else BackupManager.RestoreMode.MERGE
                val counts = backupManager.restore(backup, mode)
                val rowSummary = "${counts.memories} memories, " +
                    "${counts.conversations} convos, " +
                    "${counts.nodes + counts.edges} KG, " +
                    "${counts.hands} hands, " +
                    "${counts.tasks} tasks" +
                    (
                        if (counts.evolutionRevisionsUnreadable > 0) {
                            ", ${counts.evolutionRevisionsUnreadable} skill snapshots from another " +
                                "device kept as history only (not revertible)"
                        } else {
                            ""
                        }
                        )
                _state.update {
                    it.copy(
                        importInFlight = false,
                        pendingImportBytes = null,
                        lastResult = "Restored ${counts.total} rows ($rowSummary).",
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(importInFlight = false, lastResult = "Restore failed: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    fun clearResult() {
        _state.update { it.copy(lastResult = null) }
    }

    private companion object {
        const val TAG = "BackupViewModel"
    }
}
