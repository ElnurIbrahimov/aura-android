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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aura.ui.settings.BackupUiState
import com.aura.ui.settings.SettingsSection
import com.aura.ui.theme.AuraThemeTokens
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

@Composable
fun DataAndBackupSection(
    backupState: BackupUiState,
    onExport: () -> Unit,
    onStageImport: (android.net.Uri) -> Unit,
    onConfirmImport: () -> Unit,
    onCancelImport: () -> Unit,
    onClearResult: () -> Unit,
    onNavigateDiagnostics: () -> Unit,
    onNavigateCrashLogs: () -> Unit = {},
) {
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? -> if (uri != null) onStageImport(uri) }

    SettingsSection(
        emoji = "\uD83D\uDCBE",
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
        Spacer(Modifier.height(4.dp))
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
        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = onExport,
            enabled = !backupState.exportInFlight,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (backupState.exportInFlight) "Exporting..." else "Export to JSON") }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
            enabled = !backupState.importInFlight,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (backupState.importInFlight) "Restoring..." else "Restore from JSON") }

        if (backupState.lastResult != null) {
            val result = backupState.lastResult!!
            Spacer(Modifier.height(8.dp))
            Surface(
                color = AuraThemeTokens.colors.surface1,
                shape = RoundedCornerShape(8.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
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
                        "This will add the rows from the backup file to your existing data. " +
                        "Existing rows with the same id are replaced; new rows are added. " +
                        "Embeddings are NOT included - after restoring, go to the Memory tab " +
                        "and tap 'Rebuild embeddings' to re-embed everything in one pass."
                    )
                },
                confirmButton = {
                    TextButton(onClick = onConfirmImport) { Text(stringResource(R.string.add_to_existing)) }
                },
                dismissButton = {
                    TextButton(onClick = onCancelImport) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
    }
}