package com.aura.ui.settings.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
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
import com.aura.ui.theme.AuraSpacing
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
        Column(modifier = Modifier.padding(horizontal = AuraSpacing.md, vertical = AuraSpacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Enable triggers", style = MaterialTheme.typography.bodyLarge)
                    Text("Run hands or prompts on schedule", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = triggersEnabled, onCheckedChange = onSetEnabled)
            }

            Spacer(modifier = Modifier.height(AuraSpacing.sm))
            Text("Existing: ${triggers.size}", style = MaterialTheme.typography.bodyMedium)
            triggers.forEach { trigger ->
                val summary = when (val c = trigger.condition) {
                    is TriggerCondition.Schedule -> "schedule @ ${c.cron}"
                    is TriggerCondition.WebChanged -> "web: ${c.url}"
                    is TriggerCondition.LocationEntered -> "location @ ${"%.3f".format(c.lat)},${"%.3f".format(c.lon)} (r=${c.radiusMeters.toInt()}m)"
                    is TriggerCondition.IntentReceived -> "intent: ${c.action}"
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "• ${trigger.label} — $summary",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { onRemove(trigger.id) }) {
                        Text("Delete")
                    }
                }
            }

            Spacer(modifier = Modifier.height(AuraSpacing.sm))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(AuraSpacing.sm))
            Text("Add schedule trigger", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Label") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(AuraSpacing.xs))
            OutlinedTextField(
                value = cron,
                onValueChange = { cron = it },
                label = { Text("Cron: hourly | daily@HH:mm | weekly@DAY@HH:mm") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            // Quick preset buttons
            Spacer(modifier = Modifier.height(AuraSpacing.xxs))
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(AuraSpacing.xxs)) {
                listOf(
                    "Daily @9am" to "daily@09:00",
                    "Hourly" to "hourly",
                    "Weekly Mon @9am" to "weekly@MON@09:00",
                ).forEach { (label_text, cronValue) ->
                    androidx.compose.material3.AssistChip(
                        onClick = { cron = cronValue },
                        label = { Text(label_text, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(AuraSpacing.xs))
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("Chat prompt when fired") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(AuraSpacing.xs))
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
