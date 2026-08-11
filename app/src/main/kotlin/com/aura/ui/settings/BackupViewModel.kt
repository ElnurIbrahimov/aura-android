package com.aura.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.BuildConfig
import com.aura.backup.BackupManager
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
) : ViewModel() {

    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

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
}
