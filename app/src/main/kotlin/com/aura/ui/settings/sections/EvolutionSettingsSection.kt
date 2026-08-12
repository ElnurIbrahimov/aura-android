package com.aura.ui.settings.sections

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.aura.ui.settings.SettingsClickableRow
import com.aura.ui.settings.SettingsEvolutionSection
import com.aura.ui.settings.SettingsSection
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.Icons

@Composable
fun EvolutionSettingsSection(
    onNavigateEvolutionInbox: () -> Unit,
    onNavigateMind: () -> Unit = {},
) {
    SettingsSection(
        icon = Icons.Filled.AutoAwesome,
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
                title = "What Aura thinks",
                subtitle = "Beliefs, taste, corrections and open questions",
                onClick = onNavigateMind,
            )
            SettingsEvolutionSection()
        }
    }
}
