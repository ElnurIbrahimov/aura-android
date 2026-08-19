package com.aura.ui.settings

import androidx.compose.runtime.Immutable
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.BuildConfig
import com.aura.backup.BackupManager
import com.aura.backup.BackupService
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

/**
 * Marked [Immutable] so Compose skips on `equals` instead of identity.
 *
 * Every one of these is republished as a fresh object on each change, so under strong
 * skipping an unstable state class meant a screen taking it recomposed on every publish
 * whether or not anything it read had changed. The promise holds: all properties are
 * `val`, and the collections are replaced through `copy()` — there is no `MutableList`
 * property anywhere in main sources and nothing mutates a state collection in place.
 *
 * It is a promise the compiler cannot check. A field that starts being mutated in place
 * will stop recomposing rather than fail to build.
 */
@Immutable
data class BackupUiState(
    val exportInFlight: Boolean = false,
    val importInFlight: Boolean = false,
    val lastResult: String? = null,
    val pendingImportBytes: String? = null,
    val showImportConfirm: Boolean = false,
    /**
     * The picked file is a sealed envelope and needs a passphrase before it can
     * be read. [pendingImportBytes] holds the still-encrypted text meanwhile.
     */
    val showPassphrasePrompt: Boolean = false,
    /** Why the last unseal attempt failed, shown inside the prompt. */
    val passphraseError: String? = null,
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
    private val backupManager: BackupService,
    private val userPreferences: com.aura.data.UserPreferences,
    private val secureDataStore: com.aura.security.SecureDataStore,
    private val scheduler: com.aura.proactive.ProactiveScheduler,
    private val keyTransfer: com.aura.security.ProviderKeyTransfer,
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

    // ------------------------------------------------------------------ API key transfer

    /**
     * Seal every configured API key into [uri] under [passphrase].
     *
     * Separate from the JSON export on purpose. That file is written unattended on a
     * schedule into a folder picked once; this one exists only because the user asked for
     * it in this moment and typed a passphrase for it. Keys are the one thing that cannot
     * be regenerated from anywhere — losing them means re-issuing at every provider.
     */
    fun exportKeys(uri: Uri, passphrase: String) {
        viewModelScope.launch {
            _state.update { it.copy(exportInFlight = true, lastResult = null) }
            val result = runCatching {
                val sealed = keyTransfer.export(passphrase, System.currentTimeMillis())
                    ?: error("passphrase must be at least ${com.aura.security.BackupCrypto.MIN_PASSPHRASE} characters")
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(sealed.toByteArray()) }
                        ?: error("could not open the file for writing")
                }
                "API keys saved. This file opens with that passphrase on any install."
            }.getOrElse { "Key export failed: ${it.message ?: it.javaClass.simpleName}" }
            _state.update { it.copy(exportInFlight = false, lastResult = result) }
        }
    }

    /** Open a key file written by [exportKeys] and write what it holds back into storage. */
    fun importKeys(uri: Uri, passphrase: String) {
        viewModelScope.launch {
            _state.update { it.copy(importInFlight = true, lastResult = null) }
            val result = runCatching {
                val sealed = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                        ?: error("could not open the file")
                }
                when (val outcome = keyTransfer.import(sealed, passphrase)) {
                    is com.aura.security.ProviderKeyTransfer.Result.Restored ->
                        "Restored ${outcome.keys} API key(s)" +
                            if (outcome.embeddingModel) " and the embedding model." else "."
                    com.aura.security.ProviderKeyTransfer.Result.Empty ->
                        "That file opened but held no keys this build can use."
                    // Wrong passphrase and not-a-key-file are indistinguishable by design —
                    // nothing stores a hash of the passphrase — so the message says both.
                    com.aura.security.ProviderKeyTransfer.Result.Unreadable ->
                        "Could not open that file. Wrong passphrase, or not an Aura key file."
                }
            }.getOrElse { "Key import failed: ${it.message ?: it.javaClass.simpleName}" }
            _state.update { it.copy(importInFlight = false, lastResult = result) }
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

                // Automatic backups are sealed; manual "Export to JSON" files are
                // not. Decide on content rather than filename — BackupWorker names
                // its sealed output `*.json` too, and the SAF provider gets the
                // final say on the display name anyway.
                if (backupManager.isSealed(text)) {
                    _state.update {
                        it.copy(
                            pendingImportBytes = text,
                            showPassphrasePrompt = true,
                            passphraseError = null,
                            lastResult = "This backup is encrypted. Enter its passphrase.",
                        )
                    }
                    return@launch
                }

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
        _state.update {
            it.copy(
                showImportConfirm = false,
                showPassphrasePrompt = false,
                passphraseError = null,
                pendingImportBytes = null,
            )
        }
    }

    /**
     * Open the sealed file staged by [stageImport] and, if it opens, hand it to
     * the same confirmation the plaintext path uses.
     *
     * The passphrase is always typed, never read back from [secureDataStore],
     * even on the device that stored it. A restore is rare and the passphrase is
     * unrecoverable, so asking makes every restore a rehearsal: you find out you
     * have forgotten it while you still have the phone, rather than on the day
     * the phone is gone and this file is all that is left.
     *
     * On failure the prompt stays open — the common case is a typo, and closing
     * it would throw away the staged bytes and make the user pick the file again.
     */
    fun submitImportPassphrase(passphrase: String) {
        val sealed = _state.value.pendingImportBytes ?: return
        viewModelScope.launch {
            _state.update { it.copy(passphraseError = null) }
            val plaintext = runCatching { backupManager.unseal(sealed, passphrase) }.getOrNull()
            if (plaintext == null) {
                // BackupCrypto returns null for a wrong passphrase, a truncated
                // file and a corrupt payload alike, on purpose. Do not invent a
                // distinction it refuses to make.
                _state.update {
                    it.copy(passphraseError = "Could not open this backup — wrong passphrase, or the file is damaged.")
                }
                return@launch
            }
            try {
                stagedBackup = withContext(Dispatchers.IO) { backupManager.decodeFromJson(plaintext) }
                _state.update {
                    it.copy(
                        showPassphrasePrompt = false,
                        passphraseError = null,
                        showImportConfirm = true,
                        lastResult = "Backup opened. Read ${plaintext.length / 1024} KB.",
                    )
                }
            } catch (e: Exception) {
                // It decrypted and then would not parse: a real backup from a
                // newer build, or a file that is not one at all. Distinct from a
                // failed unseal, and worth saying so.
                _state.update {
                    it.copy(passphraseError = "Opened, but could not be read: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    fun cancelPassphrasePrompt() {
        _state.update {
            it.copy(showPassphrasePrompt = false, passphraseError = null, pendingImportBytes = null)
        }
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
