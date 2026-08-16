package com.aura.ui.settings.sections

import com.aura.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aura.ui.settings.BackupUiState
import com.aura.ui.settings.SettingsSection
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.theme.AuraSpacing
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.Icons

@Composable
fun DataAndBackupSection(
    backupState: BackupUiState,
    onExport: () -> Unit,
    onStageImport: (android.net.Uri) -> Unit,
    onConfirmImport: (replace: Boolean) -> Unit,
    onCancelImport: () -> Unit,
    onClearResult: () -> Unit,
    onNavigateDiagnostics: () -> Unit,
    onNavigateCrashLogs: () -> Unit = {},
    onPickBackupFolder: (android.net.Uri) -> Unit = {},
    onSetBackupPassphrase: (String) -> Unit = {},
    onSetAutoBackupEnabled: (Boolean) -> Unit = {},
    onRunBackupNow: () -> Unit = {},
) {
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? -> if (uri != null) onStageImport(uri) }

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
                Text("Crash logs", style = MaterialTheme.typography.bodyLarge)
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
        // install, and writing them into a JSON file would be a security
        // regression (see AuraBackup's KDoc). But nothing said so on this screen.
        // The restore dialog mentions embeddings — which rebuild themselves — and
        // stays silent on the keys, which cannot be recovered from anywhere and
        // have to be re-issued by the provider. That is the omission worth
        // naming, and this is the last moment naming it can still prevent the
        // loss rather than explain it.
        Text(
            "Does not include API keys, connected accounts, or embeddings — those are " +
                "encrypted to this install and cannot be restored from a file. Keep a " +
                "copy of your API keys somewhere else.",
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
                        TextButton(onClick = { onConfirmImport(true) }) { Text("Replace all") }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onCancelImport) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
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

    Text("Automatic backup", style = MaterialTheme.typography.titleSmall)
    Text(
        "Everything Aura knows lives on this phone only. Android's own backup is " +
            "switched off deliberately — it would hand your whole memory store to Google.",
        style = MaterialTheme.typography.bodySmall,
        color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
    )
    Spacer(Modifier.height(AuraSpacing.xs))

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Folder", style = MaterialTheme.typography.bodyLarge)
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
            Text("Passphrase", style = MaterialTheme.typography.bodyLarge)
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
            Text("Back up weekly", style = MaterialTheme.typography.bodyLarge)
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
        OutlinedButton(onClick = onRunNow, modifier = Modifier.fillMaxWidth()) { Text("Back up now") }
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

@Composable
private fun PassphraseDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    val tooShort = value.isNotEmpty() && value.length < MIN_PASSPHRASE

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Backup passphrase") },
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
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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