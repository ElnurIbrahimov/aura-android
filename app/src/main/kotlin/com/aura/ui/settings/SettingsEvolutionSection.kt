package com.aura.ui.settings
import com.aura.ui.theme.AuraThemeTokens

import com.aura.R
import com.aura.ui.theme.AuraSpacing
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun SettingsEvolutionSection(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val enabled = state.evolutionEnabled
    val interval = state.evolutionIntervalHours
    val autoApply = state.evolutionAutoApply
    Card(modifier = Modifier.fillMaxWidth().padding(AuraSpacing.md)) {
        Column(modifier = Modifier.padding(AuraSpacing.md)) {
            Text(stringResource(R.string.evolution), style = MaterialTheme.typography.titleMedium)
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(R.string.enabled), modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { viewModel.setEvolutionEnabled(it) })
            }
            TextField(
                value = interval.toString(),
                onValueChange = { viewModel.setEvolutionIntervalHours(it.toIntOrNull() ?: 24) },
                label = { Text(stringResource(R.string.interval_hours)) },
                modifier = Modifier.fillMaxWidth().padding(top = AuraSpacing.xs),
            )
            Spacer(modifier = Modifier.padding(top = AuraSpacing.xs))
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Auto-apply approved proposals", modifier = Modifier.weight(1f))
                Switch(checked = autoApply, onCheckedChange = { viewModel.setEvolutionAutoApply(it) })
            }
            Text(
                "When enabled, evolution proposals that pass evaluation are applied automatically without requiring manual approval from the inbox.",
                style = MaterialTheme.typography.labelSmall,
                color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = AuraSpacing.xxs),
            )
        }
    }
}
