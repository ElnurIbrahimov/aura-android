package com.aura.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aura.ui.viewmodel.TasteProfileViewModel
import kotlinx.serialization.json.Json

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasteProfileScreen(
    onBack: () -> Unit = {},
    viewModel: TasteProfileViewModel = viewModel(),
) {
    val signals by viewModel.signals.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Taste profile") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Learned preferences",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            item {
                ProfileAttributesCard(profile)
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { viewModel.clearAllSignals() }) { Text("Clear all") }
                    OutlinedButton(onClick = { viewModel.recompute() }) { Text("Recompute") }
                }
            }
            if (signals.isNotEmpty()) {
                item {
                    Text(
                        "Signals (${signals.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
                items(signals, key = { it.id }) { signal ->
                    SignalCard(signal) { viewModel.deleteSignal(it) }
                }
            }
            if (signals.isEmpty() && profile == null) {
                item {
                    Text(
                        "No taste signals yet. Reactions, edits, and accepted suggestions will build your taste profile.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 32.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileAttributesCard(profile: com.aura.taste.StyleProfileEntity?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (profile == null) {
                Text(
                    "No profile computed yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val attrs = runCatching {
                    Json.decodeFromString<Map<String, Map<String, Float>>>(profile.attributesJson)
                }.getOrDefault(emptyMap())
                if (attrs.isEmpty()) {
                    Text(
                        "Profile is empty. Add reactions or edit generated outputs to populate it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    for ((category, values) in attrs) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                category,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            // Visual bar chart: top 3 values as colored bars
                            val top3 = values.entries.sortedByDescending { it.value }.take(3)
                            for ((bucket, score) in top3) {
                                val parts = bucket.split(":", limit = 2)
                                val label = if (parts.size == 2) parts[1] else bucket
                                val pct = (score * 100).toInt().coerceIn(0, 100)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                ) {
                                    Text(
                                        label.take(15),
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.weight(0.3f),
                                    )
                                    androidx.compose.material3.LinearProgressIndicator(
                                        progress = { score.coerceIn(0f, 1f) },
                                        modifier = Modifier
                                            .weight(0.5f)
                                            .padding(vertical = 2.dp),
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                    )
                                    Text(
                                        "${pct}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(0.2f),
                                    )
                                }
                            }
                        }
                    }
                }
                Text(
                    "${profile.signalCount} signals · Updated ${java.text.DateFormat.getDateTimeInstance().format(profile.updatedAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SignalCard(
    signal: com.aura.taste.PreferenceSignalEntity,
    onDelete: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${signal.signalType} · ${signal.category}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (signal.artifactId != null) {
                    Text(
                        "Artifact ${signal.artifactId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "Weight ${"%.1f".format(signal.weight)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { onDelete(signal.id) }) { Text("Delete") }
        }
    }
}
