package com.aura.ui.settings.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.aura.ui.settings.SettingsClickableRow
import com.aura.ui.settings.SettingsEvolutionSection
import com.aura.ui.settings.SettingsSection

@Composable
fun EvolutionSettingsSection(
    onNavigateEvolutionInbox: () -> Unit,
    onNavigateBeliefs: () -> Unit,
    onNavigateWorldModel: () -> Unit = {},
    onNavigateTasteProfile: () -> Unit = {},
) {
    SettingsSection(
        emoji = "*",
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
            SettingsClickableRow(
                title = "World model",
                subtitle = "Beliefs, events, opportunities, contradictions",
                onClick = onNavigateWorldModel,
            )
            SettingsClickableRow(
                title = "Taste profile",
                subtitle = "Learned style preferences and signals",
                onClick = onNavigateTasteProfile,
            )
            SettingsEvolutionSection()
        }
    }
}
