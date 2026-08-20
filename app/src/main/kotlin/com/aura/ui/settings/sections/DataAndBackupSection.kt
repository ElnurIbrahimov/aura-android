package com.aura.ui.settings.sections

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aura.R
import com.aura.ui.settings.BackupUiState
import com.aura.ui.settings.SettingsSection
import com.aura.ui.theme.AuraSpacing
import com.aura.ui.theme.AuraThemeTokens

@Composable
fun DataAndBackupSection(
    backupState: BackupUiState,
    onExport: () -> Unit,
    onStageImport: (android.net.Uri) -> Unit,
    onConfirmImport: (replace: Boolean) -> Unit,
    onCancelImport: () -> Unit,
    onSubmitImportPassphrase: (String) -> Unit = {},
    onCancelPassphrasePrompt: () -> Unit = {},
    onClearResult: () -> Unit,
    onNavigateDiagnostics: () -> Unit,
    onNavigateCrashLogs: () -> Unit = {},
    onPickBackupFolder: (android.net.Uri) -> Unit = {},
    onSetBackupPassphrase: (String) -> Unit = {},
    onSetAutoBackupEnabled: (Boolean) -> Unit = {},
    onRunBackupNow: () -> Unit = {},
    onExportKeys: (android.net.Uri, String) -> Unit = { _, _ -> },
    onImportKeys: (android.net.Uri, String) -> Unit = { _, _ -> },
) {
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? -> if (uri != null) onStageImport(uri) }

    // The key flows ask for the passphrase and the file in opposite orders, because each
    // asks for the cheap thing first: exporting cannot seal without a passphrase, and
    // importing should not prompt for one before knowing there is a file to open.
    var keyExportPassphrase by remember { mutableStateOf<String?>(null) }
    var askKeyExportPassphrase by remember { mutableStateOf(false) }
    var pendingKeyImportUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val keyExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: android.net.Uri? ->
        val passphrase = keyExportPassphrase
        keyExportPassphrase = null
        if (uri != null && passphrase != null) onExportKeys(uri, passphrase)
    }
    val keyImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? -> if (uri != null) pendingKeyImportUri = uri }

    val context = LocalContext.current
    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: android.net.Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        // Without the persisted grant the URI stops working the next time the
        // process starts, which for a weekly job means it stops working before
        // it is ever used.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure { android.util.Log.w("DataAndBackupSection", "takePersistableUriPermission failed", it) }
        onPickBackupFolder(uri)
    }

    SettingsSection(
        icon = Icons.Filled.Backup,
        title = "Data & Backup",
        subtitle = "Export, restore, and inspect local diagnostics",
        initialExpanded = false,
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.diagnostics), style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Local crash and error history",
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
                )
            }
            TextButton(onClick = onNavigateDiagnostics) { Text(stringResource(R.string.open)) }
        }
        Spacer(Modifier.height(AuraSpacing.xxs))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.crash_logs), style = MaterialTheme.typography.bodyLarge)
                Text(
                    "View crash history without ADB",
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
                )
            }
            TextButton(onClick = onNavigateCrashLogs) { Text(stringResource(R.string.open)) }
        }
        Spacer(Modifier.height(AuraSpacing.xs))

        OutlinedButton(
            onClick = onExport,
            enabled = !backupState.exportInFlight,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (backupState.exportInFlight) "Exporting..." else "Export to JSON") }

        // What the file does NOT contain, said before the user relies on it.
        //
        // The omission is deliberate and correct: API keys and OAuth tokens live
        // in SecureDataStore under an Android Keystore key that never leaves the
        // install, and writing them into a JSON file written unattended on a
        // schedule would be a security regression (see AuraBackup's KDoc).
        //
        // What used to follow was "keep a copy of your API keys somewhere else",
        // which is advice, not a feature — and it is advice that has already
        // failed. "Save API keys" below is the somewhere else: a separate file,
        // written only when asked, sealed under a typed passphrase rather than a
        // Keystore key, so it opens on an install that shares nothing with this
        // one. This text now points at it instead of at the user's memory.
        Text(
            "Does not include API keys, connected accounts, or embeddings — those are " +
                "encrypted to this install. Embeddings rebuild themselves; use " +
                "\"Save API keys\" below for the keys.",
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = AuraSpacing.xxs),
        )

        Spacer(Modifier.height(AuraSpacing.xs))

        OutlinedButton(
            onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
            enabled = !backupState.importInFlight,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (backupState.importInFlight) "Restoring..." else "Restore from JSON") }

        Spacer(Modifier.height(AuraSpacing.sm))
        ApiKeyTransferRows(
            busy = backupState.exportInFlight || backupState.importInFlight,
            onSave = { askKeyExportPassphrase = true },
            onLoad = { keyImportLauncher.launch(arrayOf("application/json", "*/*")) },
        )

        Spacer(Modifier.height(AuraSpacing.sm))
        AutomaticBackupRows(
            state = backupState,
            onPickFolder = { folderLauncher.launch(null) },
            onSetPassphrase = onSetBackupPassphrase,
            onSetEnabled = onSetAutoBackupEnabled,
            onRunNow = onRunBackupNow,
        )

        if (backupState.lastResult != null) {
            val result = backupState.lastResult!!
            Spacer(Modifier.height(AuraSpacing.xs))
            Surface(
                color = AuraThemeTokens.colors.surface1,
                shape = RoundedCornerShape(AuraSpacing.xs),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = AuraSpacing.sm, vertical = AuraSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = result, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClearResult) { Text(stringResource(R.string.dismiss)) }
                }
            }
        }

        if (backupState.showPassphrasePrompt) {
        RestorePassphraseDialog(
            error = backupState.passphraseError,
            onDismiss = onCancelPassphrasePrompt,
            onConfirm = onSubmitImportPassphrase,
        )
    }

    if (backupState.showImportConfirm) {
            AlertDialog(
                onDismissRequest = onCancelImport,
                title = { Text(stringResource(R.string.restore_from_backup)) },
                text = {
                    Text(
                        "Add to existing keeps what is already here and writes the backup on top: " +
                        "rows with the same id are replaced, new rows are added, nothing is deleted.\n\n" +
                        "Replace all wipes your current data first, so this device ends up holding " +
                        "exactly what the file holds.\n\n" +
                        "Either way, the settings in the backup overwrite your current settings - " +
                        "a merge merges rows, not preferences.\n\n" +
                        "Embeddings are NOT included - after restoring, go to the Memory tab " +
                        "and tap 'Rebuild embeddings' to re-embed everything in one pass.\n\n" +
                        "API keys and connected accounts are NOT included either - they are " +
                        "encrypted to the install that made the file. Re-paste your keys in " +
                        "Settings after restoring."
                    )
                },
                confirmButton = {
                    Row {
                        TextButton(
                            onClick = { onConfirmImport(false) },
                            modifier = Modifier.padding(end = AuraSpacing.xs),
                        ) { Text(stringResource(R.string.add_to_existing)) }
                        TextButton(onClick = { onConfirmImport(true) }) { Text(stringResource(R.string.replace_all)) }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onCancelImport) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
    }
        if (askKeyExportPassphrase) {
            PassphraseDialog(
                onDismiss = { askKeyExportPassphrase = false },
                onConfirm = { entered ->
                    askKeyExportPassphrase = false
                    // Held only until the picker returns. Nothing writes it to disk: it is
                    // the key to the file, so storing it beside the file would defeat it.
                    keyExportPassphrase = entered
                    keyExportLauncher.launch("aura-api-keys.json")
                },
            )
        }

        pendingKeyImportUri?.let { uri ->
            RestorePassphraseDialog(
                error = null,
                onDismiss = { pendingKeyImportUri = null },
                onConfirm = { entered ->
                    pendingKeyImportUri = null
                    onImportKeys(uri, entered)
                },
            )
        }
}

/**
 * The automatic half.
 *
 * Ordered as a setup sequence rather than a settings list — folder, then
 * passphrase, then the switch — because the switch does nothing until the other
 * two exist, and a toggle that silently no-ops is the defect class this repo has
 * spent the most effort removing.
 */
@Composable
private fun AutomaticBackupRows(
    state: BackupUiState,
    onPickFolder: () -> Unit,
    onSetPassphrase: (String) -> Unit,
    onSetEnabled: (Boolean) -> Unit,
    onRunNow: () -> Unit,
) {
    var showPassphrase by remember { mutableStateOf(false) }
    val ready = state.backupFolderLabel.isNotBlank() && state.passphraseSet

    Text(stringResource(R.string.automatic_backup), style = MaterialTheme.typography.titleSmall)
    Text(
        "Everything Aura knows lives on this phone only. Android's own backup is " +
            "switched off deliberately — it would hand your whole memory store to Google.",
        style = MaterialTheme.typography.bodySmall,
        color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
    )
    Spacer(Modifier.height(AuraSpacing.xs))

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.folder), style = MaterialTheme.typography.bodyLarge)
            Text(
                state.backupFolderLabel.ifBlank { "Not set — pick one your cloud already syncs" },
                style = MaterialTheme.typography.bodySmall,
                color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
            )
        }
        TextButton(onClick = onPickFolder) { Text(if (state.backupFolderLabel.isBlank()) "Choose" else "Change") }
    }

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.passphrase), style = MaterialTheme.typography.bodyLarge)
            Text(
                if (state.passphraseSet) "Set. The backup file cannot be opened without it."
                else "Not set",
                style = MaterialTheme.typography.bodySmall,
                color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
            )
        }
        TextButton(onClick = { showPassphrase = true }) { Text(if (state.passphraseSet) "Change" else "Set") }
    }

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.back_up_weekly), style = MaterialTheme.typography.bodyLarge)
            Text(
                when {
                    !ready -> "Needs a folder and a passphrase first"
                    state.lastBackupError.isNotBlank() -> "Last attempt failed: ${state.lastBackupError}"
                    state.lastBackupAt > 0L -> "Last backup ${relativeDays(state.lastBackupAt)}"
                    else -> "Runs while charging. None yet."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (state.lastBackupError.isNotBlank()) {
                    MaterialTheme.colorScheme.error
                } else {
                    AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f)
                },
            )
        }
        Switch(
            checked = state.autoBackupEnabled,
            onCheckedChange = onSetEnabled,
            enabled = ready,
        )
    }

    if (ready) {
        Spacer(Modifier.height(AuraSpacing.xxs))
        // A backup that has never been restored is not a backup. This is how a
        // person finds out the folder and passphrase work without waiting a week
        // to not find out.
        OutlinedButton(onClick = onRunNow, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.back_up_now)) }
    }

    if (showPassphrase) {
        PassphraseDialog(
            onDismiss = { showPassphrase = false },
            onConfirm = {
                onSetPassphrase(it)
                showPassphrase = false
            },
        )
    }
}

/**
 * Asked for every sealed restore, even when a passphrase is stored on this device.
 *
 * Distinct from [PassphraseDialog], which *sets* the passphrase: this one opens a
 * file with it, so its failure text has to stay honest. `BackupCrypto.open` fails
 * closed and returns null for a wrong passphrase, a truncated file and a corrupt
 * payload alike; the message says both rather than guessing which.
 *
 * Reuses [MIN_PASSPHRASE] rather than declaring its own — `BackupPassphraseUiContractTest`
 * regexes the first `const val MIN_PASSPHRASE` in this file, so a second one could
 * pass while meaning something different.
 */
@Composable
private fun RestorePassphraseDialog(
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_passphrase)) },
        text = {
            Column {
                Text(
                    "This backup is encrypted. Enter the passphrase it was written with — " +
                        "it is not stored in the file and cannot be recovered from it.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(AuraSpacing.xs))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.backup_passphrase_field)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Spacer(Modifier.height(AuraSpacing.xs))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value) },
                enabled = value.length >= MIN_PASSPHRASE,
            ) { Text(stringResource(R.string.restore_from_backup)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PassphraseDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    val tooShort = value.isNotEmpty() && value.length < MIN_PASSPHRASE

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_passphrase)) },
        text = {
            Column {
                // Said once, plainly, at the only moment it can be acted on.
                Text(
                    "This is the only thing that can open your backup file. It is not " +
                        "stored in the file and it cannot be recovered — if you forget it, " +
                        "every backup written with it is gone.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(AuraSpacing.xs))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text("At least $MIN_PASSPHRASE characters") },
                    isError = tooShort,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(value) },
                enabled = value.length >= MIN_PASSPHRASE,
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Mirrors `BackupCrypto.MIN_PASSPHRASE`; asserted equal by `BackupPassphraseUiContractTest`. */
private const val MIN_PASSPHRASE = 8

private fun relativeDays(at: Long): String {
    val days = (System.currentTimeMillis() - at) / (24L * 60 * 60 * 1000)
    return when {
        days <= 0L -> "today"
        days == 1L -> "yesterday"
        else -> "$days days ago"
    }
}

/**
 * Save and load API keys as their own file.
 *
 * Keys are the only thing in this app that cannot be rebuilt from anything else. Memories
 * and conversations are in the backup; embeddings regenerate; a key that is gone has to be
 * re-issued at the provider, and on some of them that means losing the old one's history.
 * They are excluded from the automatic backup for good reason — it runs unattended into a
 * folder that is often cloud-synced — so they need a door of their own, and this is it.
 *
 * Sealed with [com.aura.security.BackupCrypto], which derives its key from the typed
 * passphrase and never consults the Keystore. That is the entire point: the file has to
 * open on an install that shares nothing with the one that wrote it.
 */
@Composable
private fun ApiKeyTransferRows(
    busy: Boolean,
    onSave: () -> Unit,
    onLoad: () -> Unit,
) {
    Text(stringResource(R.string.api_key_backup), style = MaterialTheme.typography.titleSmall)
    Text(
        "A separate encrypted file, written only when you ask. Opens with the passphrase " +
            "you choose — including after a reinstall, which is when you will need it.",
        style = MaterialTheme.typography.bodySmall,
        color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
        modifier = Modifier.padding(top = AuraSpacing.xxs),
    )
    Spacer(Modifier.height(AuraSpacing.xs))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
    ) {
        OutlinedButton(onClick = onSave, enabled = !busy, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.save_api_keys))
        }
        OutlinedButton(onClick = onLoad, enabled = !busy, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.load_api_keys))
        }
    }
}
