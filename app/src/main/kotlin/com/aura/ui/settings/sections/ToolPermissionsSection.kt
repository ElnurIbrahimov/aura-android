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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import com.aura.agent.policy.ConfirmationLevel
import com.aura.agent.policy.ToolPolicy
import com.aura.ui.settings.SettingsSection
import com.aura.ui.theme.AuraThemeTokens

@Composable
fun ToolPermissionsSection(
    toolPolicies: Map<String, ToolPolicy>,
    onSetToolEnabled: (String, Boolean) -> Unit,
    onSetToolConfirmation: (String, ConfirmationLevel) -> Unit,
) {
    var editingToolPolicy by remember { mutableStateOf<String?>(null) }
    var showAll by remember { mutableStateOf(false) }

    SettingsSection(
        emoji = "\uD83D\uDEE1\uFE0F",
        title = "Tool Permissions",
        subtitle = "Disable tools or require confirmation before they run",
        initialExpanded = false,
    ) {
        Text(
            text = stringResource(R.string.turn_off_any_tool_aura_should),
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
        )
        Spacer(modifier = Modifier.height(8.dp))
        val tools = toolPolicies.entries.sortedBy { it.key }
        val visibleTools = if (showAll) tools else tools.take(8)
        for ((toolName, policy) in visibleTools) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = toolName.replace("_", " ").replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = "Confirmation: ${policy.confirmation.name.lowercase().replaceFirstChar { it.uppercase() }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
                    )
                }
                Switch(
                    checked = policy.enabled,
                    onCheckedChange = { onSetToolEnabled(toolName, it) },
                )
                TextButton(onClick = { editingToolPolicy = toolName }) { Text(stringResource(R.string.policy)) }
            }
        }
        if (toolPolicies.size > 8) {
            Text(
                text = "+ ${toolPolicies.size - 8} more tools",
                style = MaterialTheme.typography.bodySmall,
                color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 16.dp),
            )
        }

        editingToolPolicy?.let { editingName ->
            val current = toolPolicies[editingName] ?: return@let
            var selectedLevel by remember(editingName) { mutableStateOf(current.confirmation) }
            AlertDialog(
                onDismissRequest = { editingToolPolicy = null },
                title = { Text(editingName.replace("_", " ").replaceFirstChar { it.uppercase() }) },
                text = {
                    Column {
                        Text(stringResource(R.string.choose_the_confirmation_level), style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(8.dp))
                        for (level in ConfirmationLevel.entries) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = selectedLevel == level, onClick = { selectedLevel = level })
                                Text(
                                    text = level.name.lowercase().replaceFirstChar { it.uppercase() },
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        onSetToolConfirmation(editingName, selectedLevel)
                        editingToolPolicy = null
                    }) { Text(stringResource(R.string.save)) }
                },
                dismissButton = {
                    TextButton(onClick = { editingToolPolicy = null }) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
    }
}