package com.aura.ui.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.ui.settings.SettingsSection

@Composable
fun CouncilSettingsSection(
    councilEnabled: androidx.compose.runtime.State<Boolean>,
    councilAutoApply: androidx.compose.runtime.State<Boolean>,
    councilActivityLevel: androidx.compose.runtime.State<Int>,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleAutoApply: (Boolean) -> Unit,
    onActivityLevelChange: (Int) -> Unit,
) {
    SettingsSection(
        emoji = "=",
        title = "Council",
        subtitle = "Agent society debates and interventions",
        initialExpanded = false,
    ) {
        Column {
            // Enable/disable toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Enable overnight council", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Agents debate findings and propose interventions while idle",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = councilEnabled.value, onCheckedChange = onToggleEnabled)
            }

            if (councilEnabled.value) {
                // Auto-apply toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-apply interventions", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Skip approval — apply council proposals automatically",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = councilAutoApply.value, onCheckedChange = onToggleAutoApply)
                }

                // Activity level slider
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                ) {
                    Text("Activity level: ${councilActivityLevel.value}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "1 = minimal, 5 = active",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = councilActivityLevel.value.toFloat(),
                        onValueChange = { onActivityLevelChange(it.toInt()) },
                        valueRange = 1f..5f,
                        steps = 3,
                    )
                }
            }
        }
    }
}