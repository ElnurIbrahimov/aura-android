package com.aura.ui.settings.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aura.triggers.Trigger
import com.aura.triggers.TriggerAction
import com.aura.triggers.TriggerCondition
import com.aura.ui.settings.SettingsSection
import java.util.UUID

@Composable
fun TriggersSection(
    triggersEnabled: Boolean,
    triggers: List<Trigger>,
    onSetEnabled: (Boolean) -> Unit,
    onSave: (Trigger) -> Unit,
    onRemove: (String) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var cron by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }

    SettingsSection(emoji = "⏰", title = "Triggers", subtitle = "Schedule-based hands and prompts", initialExpanded = false) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Enable triggers", style = MaterialTheme.typography.bodyLarge)
                    Text("Run hands or prompts on schedule", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = triggersEnabled, onCheckedChange = onSetEnabled)
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text("Existing: ${triggers.size}", style = MaterialTheme.typography.bodyMedium)
            triggers.forEach { trigger ->
                val summary = when (val c = trigger.condition) {
                    is TriggerCondition.Schedule -> "schedule @ ${c.cron}"
                    is TriggerCondition.WebChanged -> "web: ${c.url}"
                    is TriggerCondition.LocationEntered -> "location (not yet implemented)"
                    is TriggerCondition.IntentReceived -> "intent: ${c.action}"
                }
                Text("• ${trigger.label} — $summary", style = MaterialTheme.typography.bodySmall)
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))
            Text("Add schedule trigger", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Label") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = cron,
                onValueChange = { cron = it },
                label = { Text("Cron: hourly | daily@HH:mm | weekly@DAY@HH:mm") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("Chat prompt when fired") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    if (label.isNotBlank() && cron.isNotBlank() && prompt.isNotBlank()) {
                        onSave(
                            Trigger(
                                id = UUID.randomUUID().toString(),
                                label = label,
                                condition = TriggerCondition.Schedule(cron = cron),
                                action = TriggerAction.StartChat(prompt = prompt),
                            )
                        )
                        label = ""
                        cron = ""
                        prompt = ""
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = label.isNotBlank() && cron.isNotBlank() && prompt.isNotBlank(),
            ) {
                Text("Save trigger")
            }
        }
    }
}
