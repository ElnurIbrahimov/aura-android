package com.aura.ui.settings.sections

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

@Composable
fun ModelRolesSection(
    roleModels: Map<ModelRole, String>,
    availableModels: List<String>,
    onSetRoleModel: (ModelRole, String) -> Unit,
) {
    var editingRole by remember { mutableStateOf<ModelRole?>(null) }

    SettingsSection(
        emoji = "\uD83C\uDFAF",
        title = "Model Roles",
        subtitle = "Pick a model for each kind of task",
        initialExpanded = false,
    ) {
        Text(
            text = "Override the default model for specific tasks. Leave a role empty to fall back to the conversation model.",
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
        )
        Spacer(modifier = Modifier.height(8.dp))
        for (role in ModelRole.configurable) {
            val selected = roleModels[role].orEmpty()
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = role.displayName, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = selected.ifBlank { "Fallback to default model" },
                        style = MaterialTheme.typography.bodySmall,
                        color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
                    )
                }
                TextButton(onClick = { editingRole = role }) {
                    Text(if (selected.isBlank()) "Set" else "Change")
                }
            }
        }

        editingRole?.let { role ->
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
                            label = { Text("Model id (e.g. ollama:gemma4:e4b)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
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
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { editingRole = null }) { Text("Cancel") }
                },
            )
        }
    }
}