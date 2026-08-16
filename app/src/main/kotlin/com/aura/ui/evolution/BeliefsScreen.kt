package com.aura.ui.evolution

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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.ui.components.AuraEmptyState
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.theme.AuraSpacing

/**
 * Beliefs as a section of `MindScreen`'s list.
 *
 * This is the richer of the two belief renderings the app had: it carries the
 * supporting evidence and the superseded chain — "previously: X" — neither of
 * which `WorldModelViewModel` has ever loaded. When the two screens merged it
 * was this one that survived, which is the opposite of what the line count
 * suggested.
 *
 * A `LazyListScope` extension so it shares one scroll container with the rest.
 */
internal fun LazyListScope.beliefsSection(viewModel: BeliefsViewModel) {
    item {
        val state by viewModel.state.collectAsStateWithLifecycle()
        val selected by viewModel.selected.collectAsStateWithLifecycle()
        val beliefs = state.beliefs

        Column {
            if (beliefs.isEmpty()) {
                AuraEmptyState(
                    icon = Icons.Outlined.Lightbulb,
                    title = "No beliefs yet",
                    message = "Aura forms beliefs as it learns stable facts from your " +
                        "conversations. They'll appear here once it has enough signal.",
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    "Beliefs (${beliefs.size})",
                    style = MaterialTheme.typography.titleMedium,
                )
                for (belief in beliefs) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = AuraSpacing.xxs)
                            .clickable { viewModel.select(belief.id) },
                    ) {
                        Column(modifier = Modifier.padding(AuraSpacing.md)) {
                            Text(
                                "${belief.subject} — ${belief.predicate}",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(modifier = Modifier.height(AuraSpacing.xxs))
                            Text(belief.valueJson, style = MaterialTheme.typography.bodyMedium)
                            Spacer(modifier = Modifier.height(AuraSpacing.xxs))
                            Row {
                                Text("conf: ${belief.confidence}", style = MaterialTheme.typography.labelSmall)
                                Spacer(modifier = Modifier.width(AuraSpacing.sm))
                                Text("status: ${belief.status}", style = MaterialTheme.typography.labelSmall)
                            }
                            val supporting = state.evidence[belief.id].orEmpty()
                            if (supporting.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(AuraSpacing.xxs))
                                supporting.take(3).forEach { evidence ->
                                    Text(
                                        text = "· ${evidence.summary}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AuraThemeTokens.colors.textSecondary,
                                    )
                                }
                            }
                            val chain = state.history[belief.id].orEmpty()
                            if (chain.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(AuraSpacing.xxs))
                                val previouslyLabel = stringResource(R.string.previously)
                                chain.take(3).forEach { old ->
                                    Text(
                                        text = "$previouslyLabel${old.valueJson}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AuraThemeTokens.colors.textSecondary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Detail dialog — belief details with retire/verify actions.
        selected?.let { belief ->
            AlertDialog(
                onDismissRequest = { viewModel.clearSelection() },
                confirmButton = {
                    TextButton(onClick = { viewModel.clearSelection() }) { Text(stringResource(R.string.close)) }
                },
                title = { Text("${belief.subject} — ${belief.predicate}") },
                text = {
                    Column {
                        Text(belief.valueJson, style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(AuraSpacing.sm))
                        Text(
                            "Confidence: ${"%.0f".format(belief.confidence * 100)}%",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text("Status: ${belief.status}", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.height(AuraSpacing.md))
                        Row {
                            TextButton(onClick = { viewModel.verify(belief.id) }) { Text(stringResource(R.string.verify)) }
                            Spacer(modifier = Modifier.width(AuraSpacing.xs))
                            TextButton(
                                onClick = {
                                    viewModel.retire(belief.id)
                                    viewModel.clearSelection()
                                },
                            ) { Text(stringResource(R.string.retire), color = AuraThemeTokens.colors.error) }
                        }
                    }
                },
            )
        }
    }
}
