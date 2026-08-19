package com.aura.ui.screens
import com.aura.ui.components.AuraCard
import com.aura.R
import androidx.compose.ui.res.stringResource
import com.aura.ui.theme.AuraThemeTokens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.ui.theme.AuraSpacing
import com.aura.ui.viewmodel.TasteProfileViewModel
import kotlinx.serialization.json.Json
import android.util.Log

// The standalone `TasteProfileScreen` composable that used to live here was
// removed when `MindScreen` absorbed this content (15d2cc0d). Its route went
// with it; the composable stayed behind, reachable from nothing. Only
// [tasteSection] is live now.

/**
 * What Aura has worked out about how the user likes things done.
 *
 * A `LazyListScope` extension for the same reason as `worldModelSection`: it
 * has to share `MindScreen`'s scroll container rather than nest inside it.
 */
internal fun LazyListScope.tasteSection(
    viewModel: TasteProfileViewModel,
    showEmptyState: Boolean = true,
) {
    item {
        val signals by viewModel.signals.collectAsStateWithLifecycle()
        val profile by viewModel.profile.collectAsStateWithLifecycle()

        Column(verticalArrangement = Arrangement.spacedBy(AuraSpacing.sm)) {
            SectionHeading("Learned preferences", top = false)
            ProfileAttributesCard(profile)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { viewModel.clearAllSignals() }) { Text(stringResource(R.string.clear_all)) }
                OutlinedButton(onClick = { viewModel.recompute() }) { Text(stringResource(R.string.recompute)) }
            }
            if (signals.isNotEmpty()) {
                SectionHeading("Signals (${signals.size})")
                for (signal in signals) {
                    SignalCard(signal) { viewModel.deleteSignal(it) }
                }
            }
            if (showEmptyState && signals.isEmpty() && profile == null) {
                Text(
                    "No taste signals yet. Reactions, edits, and accepted suggestions will build your taste profile.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AuraThemeTokens.colors.textSecondary,
                    modifier = Modifier.padding(top = AuraSpacing.xl),
                )
            }
        }
    }
}

@Composable
private fun ProfileAttributesCard(profile: com.aura.taste.StyleProfileEntity?) {
    AuraCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
        ) {
            if (profile == null) {
                Text(
                    "No profile computed yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AuraThemeTokens.colors.textSecondary,
                )
            } else {
                val attrs = runCatching {
                    Json.decodeFromString<Map<String, Map<String, Float>>>(profile.attributesJson)
                }.onFailure { Log.w("TasteProfileScreen", "runCatching failed: ${it.message}", it) }.getOrDefault(emptyMap())
                if (attrs.isEmpty()) {
                    Text(
                        "Profile is empty. Add reactions or edit generated outputs to populate it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AuraThemeTokens.colors.textSecondary,
                    )
                } else {
                    for ((category, values) in attrs) {
                        Column(verticalArrangement = Arrangement.spacedBy(AuraSpacing.tiny)) {
                            Text(
                                category,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = AuraThemeTokens.colors.actionPrimary,
                            )
                            // Visual bar chart: top 3 values as colored bars
                            val top3 = values.entries.sortedByDescending { it.value }.take(3)
                            for ((bucket, score) in top3) {
                                val parts = bucket.split(":", limit = 2)
                                val label = if (parts.size == 2) parts[1] else bucket
                                val pct = (score * 100).toInt().coerceIn(0, 100)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
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
                                            .padding(vertical = AuraSpacing.tiny),
                                        color = AuraThemeTokens.colors.actionPrimary,
                                        trackColor = AuraThemeTokens.colors.surface2,
                                    )
                                    Text(
                                        "${pct}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AuraThemeTokens.colors.textSecondary,
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
                    color = AuraThemeTokens.colors.textSecondary,
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
    AuraCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
                        color = AuraThemeTokens.colors.textSecondary,
                    )
                }
                Text(
                    "Weight ${"%.1f".format(signal.weight)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = AuraThemeTokens.colors.textSecondary,
                )
            }
            TextButton(onClick = { onDelete(signal.id) }) { Text(stringResource(R.string.delete)) }
        }
    }
}
