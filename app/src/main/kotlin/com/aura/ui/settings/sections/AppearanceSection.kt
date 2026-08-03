package com.aura.ui.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aura.ui.settings.SettingsSection
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.theme.AuraSpacing

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
        Spacer(modifier = Modifier.height(AuraSpacing.xs))
        // Theme preview: a mini mock showing how the selected theme
        // looks on a message bubble + input bar.
        ThemePreview(themeMode)
        Spacer(modifier = Modifier.height(AuraSpacing.xxs))
    }
}

@Composable
private fun ThemePreview(themeMode: String) {
    val colors = AuraThemeTokens.colors
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colors.surface0,
        shape = MaterialTheme.shapes.medium,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.borderSubtle),
    ) {
        Column(modifier = Modifier.padding(AuraSpacing.sm), verticalArrangement = Arrangement.spacedBy(AuraSpacing.xs)) {
            // Mock assistant message
            Row(horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs)) {
                androidx.compose.material3.Icon(
                    Icons.Filled.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = colors.actionPrimary,
                )
                Column {
                    Text(
                        "Aura",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary,
                    )
                    Text(
                        "This is how your messages will look.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textPrimary,
                    )
                }
            }
            // Mock user message
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                androidx.compose.material3.Surface(
                    color = colors.actionPrimary,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                ) {
                    Text(
                        "And this is my reply.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onActionPrimary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            // Mock input bar
            androidx.compose.material3.Surface(
                color = colors.surface1,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.borderSubtle),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
                ) {
                    Text(
                        "Ask Aura…",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary,
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.Icon(
                        Icons.Filled.ArrowUpward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = colors.actionPrimary,
                    )
                }
            }
        }
    }
}