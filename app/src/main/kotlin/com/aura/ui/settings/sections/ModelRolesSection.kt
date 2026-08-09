package com.aura.ui.settings.sections

import com.aura.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aura.providers.ModelRole
import com.aura.ui.settings.SettingsSection
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.theme.AuraSpacing
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.Icons

/**
 * @param roleModels the model the user **explicitly chose** per role, blank when
 *   they have not chosen one.
 * @param roleFallbacks what each role resolves to when nothing is set — normally
 *   the conversation default.
 *
 * The two were one map, populated from `ModelRoleRouter.resolve`, which already
 * includes the fallback. So every row displayed a model and read as configured,
 * an unset Planner was indistinguishable from a deliberately-pinned one, and
 * opening a row and pressing Save wrote that inherited value back as an explicit
 * override.
 */
@Composable
fun ModelRolesSection(
    roleModels: Map<ModelRole, String>,
    roleFallbacks: Map<ModelRole, String>,
    availableModels: List<String>,
    onSetRoleModel: (ModelRole, String) -> Unit,
) {
    var editingRole by remember { mutableStateOf<ModelRole?>(null) }

    SettingsSection(
        icon = Icons.Filled.Tune,
        title = "Model Roles",
        subtitle = "Pick a model for each kind of task",
        initialExpanded = false,
    ) {
        Text(
            text = stringResource(R.string.override_the_default_model_for_specific),
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
        )
        Spacer(modifier = Modifier.height(AuraSpacing.xs))
        for (role in ModelRole.configurable) {
            val selected = roleModels[role].orEmpty()
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = AuraSpacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val fallback = roleFallbacks[role].orEmpty()
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = role.displayName, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        // Say which model an unset role will actually use, and
                        // mark it as inherited rather than chosen.
                        text = selected.ifBlank {
                            if (fallback.isBlank()) "Auto — no default model set" else "Auto — $fallback"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = AuraThemeTokens.colors.textPrimary
                            .copy(alpha = if (selected.isBlank()) 0.45f else 0.75f),
                    )
                }
                TextButton(onClick = { editingRole = role }) {
                    Text(if (selected.isBlank()) "Set" else "Change")
                }
            }
        }

        editingRole?.let { role ->
            // Seeded from the explicit choice, so an unset role opens empty.
            // It used to be seeded from the resolved value, which meant opening
            // an unset row and pressing Save silently pinned the conversation
            // default as an explicit override for that role — and thereafter it
            // no longer followed the default when the user changed it.
            val current = roleModels[role].orEmpty()
            var pickerModel by remember(role) { mutableStateOf(current) }
            AlertDialog(
                onDismissRequest = { editingRole = null },
                title = { Text("${role.displayName} model") },
                text = {
                    Column {
                        OutlinedTextField(
                            value = pickerModel,
                            onValueChange = { pickerModel = it },
                            label = { Text(stringResource(R.string.model_id_e_g_ollama_gemma4)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(modifier = Modifier.height(AuraSpacing.xs))
                        Text(
                            text = "Leave empty to follow the default model.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
                        )
                        Spacer(modifier = Modifier.height(AuraSpacing.xxs))
                        Text(
                            text = "Available models: ${availableModels.take(6).joinToString(", ")}",
                            style = MaterialTheme.typography.bodySmall,
                            color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.5f),
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        onSetRoleModel(role, pickerModel)
                        editingRole = null
                    }) { Text(stringResource(R.string.save)) }
                },
                dismissButton = {
                    TextButton(onClick = { editingRole = null }) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
    }
}