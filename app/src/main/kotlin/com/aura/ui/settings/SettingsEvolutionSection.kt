package com.aura.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Evolution", style = MaterialTheme.typography.titleMedium)
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Enabled", modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { viewModel.setEvolutionEnabled(it) })
            }
            TextField(
                value = interval.toString(),
                onValueChange = { viewModel.setEvolutionIntervalHours(it.toIntOrNull() ?: 24) },
                label = { Text("Interval (hours)") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }
}
