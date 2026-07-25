package com.aura.ui.settings.sections

import com.aura.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aura.ui.settings.SettingsSection
import com.aura.ui.theme.AuraThemeTokens
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Composable
fun PersonaSection(
    identityCustomized: Boolean,
    specialistOverrides: String,
    onNavigateIdentity: () -> Unit,
    onSetSpecialistOverrides: (String) -> Unit,
) {
    SettingsSection(
        emoji = "\uD83E\uDDD8",
        title = "Persona",
        subtitle = "Aura identity and specialist overrides",
        initialExpanded = false,
    ) {
        Surface(
            color = AuraThemeTokens.colors.surface1,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onNavigateIdentity),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.aura_identity),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (identityCustomized) "Customized - backed up with your settings"
                        else "Using the bundled default persona",
                        style = MaterialTheme.typography.bodySmall,
                        color = AuraThemeTokens.colors.textPrimary,
                    )
                }
                OutlinedButton(onClick = onNavigateIdentity) {
                    Text(if (identityCustomized) "Edit" else "Customize")
                }
            }
        }
        // ── Specialist prompt overrides ──
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.specialist_prompts),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(R.string.override_the_built_in_system_prompt),
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
        )
        Spacer(modifier = Modifier.height(8.dp))

        val overridesMap = remember(specialistOverrides) {
            try { Json.decodeFromString<Map<String, String>>(specialistOverrides) }
            catch (e: Exception) { emptyMap() }
        }
        var editingSpecialist by remember { mutableStateOf<com.aura.agent.Specialist?>(null) }

        for (specialist in com.aura.agent.Specialist.ALL) {
            val hasOverride = overridesMap.containsKey(specialist.name) && !overridesMap[specialist.name].isNullOrBlank()
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${specialist.icon} ${specialist.name.replaceFirstChar { it.uppercase() }}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(modifier = Modifier.weight(1f))
                if (hasOverride) {
                    Text("\u270F\uFE0F", style = MaterialTheme.typography.bodySmall, color = AuraThemeTokens.colors.actionPrimary)
                }
                TextButton(onClick = { editingSpecialist = specialist }) {
                    Text(if (hasOverride) "Edit" else "Default")
                }
            }
        }

        editingSpecialist?.let { specialist ->
            var promptText by remember(specialist) {
                mutableStateOf(overridesMap[specialist.name] ?: specialist.systemPrompt)
            }
            AlertDialog(
                onDismissRequest = { editingSpecialist = null },
                title = { Text("${specialist.icon} ${specialist.name.replaceFirstChar { it.uppercase() }}") },
                text = {
                    OutlinedTextField(
                        value = promptText,
                        onValueChange = { promptText = it },
                        label = { Text(stringResource(R.string.system_prompt)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        maxLines = 12,
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        val updated = overridesMap.toMutableMap().apply { put(specialist.name, promptText.trim()) }
                        onSetSpecialistOverrides(
                            Json.encodeToString(MapSerializer(String.serializer(), String.serializer()), updated)
                        )
                        editingSpecialist = null
                    }) { Text(stringResource(R.string.save)) }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            val updated = overridesMap.toMutableMap().apply { remove(specialist.name) }
                            onSetSpecialistOverrides(
                                Json.encodeToString(MapSerializer(String.serializer(), String.serializer()), updated)
                            )
                            editingSpecialist = null
                        }) { Text(stringResource(R.string.reset_to_default_2)) }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = { editingSpecialist = null }) { Text(stringResource(R.string.cancel)) }
                    }
                },
            )
        }
    }
}