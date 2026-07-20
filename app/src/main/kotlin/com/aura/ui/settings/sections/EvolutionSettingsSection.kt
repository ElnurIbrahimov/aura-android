package com.aura.ui.settings.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aura.ui.settings.SettingsClickableRow
import com.aura.ui.settings.SettingsEvolutionSection
import com.aura.ui.settings.SettingsSection
import com.aura.ui.theme.AuraThemeTokens

@Composable
fun EvolutionSettingsSection(
    onNavigateEvolutionInbox: () -> Unit,
    onNavigateBeliefs: () -> Unit,
) {
    SettingsSection(
        emoji = "\uD83E\uDDF0",
        title = "Evolution",
        subtitle = "Review and control self-improvement proposals",
        initialExpanded = false,
    ) {
        Column {
            SettingsClickableRow(
                title = "Evolution inbox",
                subtitle = "Pending skill / memory / proactive proposals",
                onClick = onNavigateEvolutionInbox,
            )
            SettingsClickableRow(
                title = "Active beliefs",
                subtitle = "Memory-synthesized beliefs and evidence",
                onClick = onNavigateBeliefs,
            )
            SettingsEvolutionSection()
        }
    }
}