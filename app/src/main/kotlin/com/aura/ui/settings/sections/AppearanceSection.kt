package com.aura.ui.settings.sections

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aura.ui.settings.SettingsSection
import com.aura.ui.theme.AuraThemeTokens

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceSection(
    themeMode: String,
    onSetThemeMode: (String) -> Unit,
) {
    SettingsSection(
        emoji = "\uD83C\uDFA8",
        title = "Appearance",
        subtitle = "Light, dark, or follow the system theme",
        initialExpanded = false,
    ) {
        Spacer(modifier = Modifier.height(2.dp))
        FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
            listOf(
                "system" to "System",
                "light" to "Light",
                "dark" to "Dark",
            ).forEach { (id, label) ->
                AssistChip(
                    onClick = { onSetThemeMode(id) },
                    label = { Text(label) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (themeMode == id)
                            AuraThemeTokens.colors.actionPrimary
                        else
                            AuraThemeTokens.colors.surface1,
                        labelColor = if (themeMode == id)
                            AuraThemeTokens.colors.onActionPrimary
                        else
                            AuraThemeTokens.colors.textPrimary,
                    ),
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}