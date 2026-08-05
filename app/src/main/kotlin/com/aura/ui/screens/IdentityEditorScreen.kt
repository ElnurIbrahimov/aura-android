package com.aura.ui.screens

import com.aura.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aura.ui.settings.SettingsViewModel

import com.aura.ui.theme.AuraThemeTokens
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.ui.theme.AuraSpacing
/**
 * Full-screen identity (SOUL.md) editor.
 *
 * Lets the user edit the persona Aura sends as its system prompt. The custom
 * text is stored in DataStore, so the brain, this editor, and backup/restore
 * all use one source of truth. Blank text falls back to bundled `SOUL.md`.
 *
 * Why a separate composable? The persona is long-form markdown
 * (5-10KB) and a small `OutlinedTextField` in the Settings
 * scrolling list would be cramped and slow. A full-screen editor
 * with monospace font, word counter, and Save/Reset bar gives
 * the user a real writing surface.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdentityEditorScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var text by remember(state.identityText) { mutableStateOf(state.identityText) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showSaveConfirm by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.identity),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = if (state.identityCustomized) "Customized" else "Default persona",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.identityCustomized)
                                AuraThemeTokens.colors.actionPrimary
                            else AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showSaveConfirm = true }) {
                        Icon(Icons.Default.Save, contentDescription = "Save")
                    }
                    IconButton(onClick = { showResetConfirm = true }) {
                        Icon(Icons.Default.Restore, contentDescription = "Reset to default")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AuraThemeTokens.colors.surface1,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
        ) {
            // Helper text
            Text(
                text = stringResource(R.string.this_is_aura_s_system_prompt) +
                    "speaks, what it knows about itself, and the rules it follows. " +
                    "It is included in settings backups. Leave blank for the bundled default.",
                style = MaterialTheme.typography.bodySmall,
                color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = AuraSpacing.md, vertical = AuraSpacing.xs),
            )
            HorizontalDivider()
            // The editor itself
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = AuraSpacing.md, vertical = AuraSpacing.xs),
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                ),
            )
            // Footer
            Surface(
                color = AuraThemeTokens.colors.surface1,
                tonalElevation = AuraSpacing.tiny,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AuraSpacing.md, vertical = AuraSpacing.xs),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${text.length} chars · ${text.split("\\s+".toRegex()).size} words",
                        style = MaterialTheme.typography.bodySmall,
                        color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
                    )
                    Row {
                        TextButton(onClick = { showResetConfirm = true }) {
                            Text(stringResource(R.string.reset))
                        }
                        Spacer(Modifier.width(AuraSpacing.xs))
                        Button(onClick = { showSaveConfirm = true }) {
                            Text(stringResource(R.string.save))
                        }
                    }
                }
            }
        }
    }

    // Save confirmation
    if (showSaveConfirm) {
        AlertDialog(
            onDismissRequest = { showSaveConfirm = false },
            title = { Text(stringResource(R.string.save_identity)) },
            text = {
                Text(
                    "This replaces Aura's system prompt on next chat send. " +
                        "You can reset to the bundled default anytime.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    showSaveConfirm = false
                    viewModel.saveIdentity(text)
                    onBack()
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showSaveConfirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    // Reset confirmation
    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.reset_to_default)) },
            text = {
                Text(
                    "Your custom identity will be cleared. The bundled " +
                        "Aura persona will be used on the next chat send.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    showResetConfirm = false
                    viewModel.resetIdentity()
                    onBack()
                }) { Text(stringResource(R.string.reset)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}
