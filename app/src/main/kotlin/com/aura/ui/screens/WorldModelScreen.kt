package com.aura.ui.screens
import com.aura.ui.theme.AuraThemeTokens

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
import com.aura.ui.viewmodel.WorldModelViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldModelScreen(
    onBack: () -> Unit = {},
    viewModel: WorldModelViewModel = viewModel(),
) {
    val beliefs by viewModel.beliefs.collectAsStateWithLifecycle()
    val events by viewModel.worldEvents.collectAsStateWithLifecycle()
    val opportunities by viewModel.opportunities.collectAsStateWithLifecycle()
    val contradictions by viewModel.contradictions.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("World model") },
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
            if (beliefs.isNotEmpty()) {
                item {
                    Text(
                        "Beliefs (${beliefs.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                items(beliefs, key = { it.id }) { belief ->
                    BeliefCard(
                        belief = belief,
                        onVerify = { viewModel.verifyBelief(belief.id) },
                        onRetire = { viewModel.retireBelief(belief.id) },
                    )
                }
            }
            if (events.isNotEmpty()) {
                item {
                    Text(
                        "World events (${events.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
                items(events, key = { it.id }) { WorldEventCard(it) }
            }
            if (opportunities.isNotEmpty()) {
                item {
                    Text(
                        "Opportunities (${opportunities.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
                items(opportunities, key = { it.id }) { OpportunityCard(it, viewModel::resolveOpportunity) }
            }
            if (contradictions.isNotEmpty()) {
                item {
                    Text(
                        "Contradictions (${contradictions.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
                items(contradictions, key = { it.id }) { ContradictionCard(it) }
            }
            if (beliefs.isEmpty() && events.isEmpty() && opportunities.isEmpty() && contradictions.isEmpty()) {
                item {
                    Text(
                        "No world-model data yet. Beliefs, events, and opportunities are created as the system learns.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = AuraThemeTokens.colors.textSecondary,
                        modifier = Modifier.padding(top = 32.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun BeliefCard(
    belief: com.aura.world.BeliefEntity,
    onVerify: () -> Unit,
    onRetire: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "${belief.subject} · ${belief.predicate}",
                style = MaterialTheme.typography.labelMedium,
                color = AuraThemeTokens.colors.actionPrimary,
            )
            Text(
                belief.valueJson.trim('"'),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                "Confidence ${"%.0f".format(belief.confidence * 100)}% · ${belief.status}",
                style = MaterialTheme.typography.labelSmall,
                color = AuraThemeTokens.colors.textSecondary,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                TextButton(onClick = onVerify) {
                    Text("Verify")
                }
                TextButton(
                    onClick = onRetire,
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = AuraThemeTokens.colors.error,
                    ),
                ) {
                    Text("Retire")
                }
            }
        }
    }
}

@Composable
private fun WorldEventCard(event: com.aura.world.WorldEventEntity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "${event.eventType} · ${event.source}",
                style = MaterialTheme.typography.labelMedium,
                color = AuraThemeTokens.colors.actionPrimary,
            )
            Text(
                event.summary,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (event.payloadJson != "{}") {
                Text(
                    event.payloadJson,
                    style = MaterialTheme.typography.labelSmall,
                    color = AuraThemeTokens.colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun OpportunityCard(
    opportunity: com.aura.world.OpportunityEntity,
    onResolve: (String, Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                opportunity.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                opportunity.description,
                style = MaterialTheme.typography.bodyMedium,
                color = AuraThemeTokens.colors.textSecondary,
            )
            Text(
                "Urgency ${"%.0f".format(opportunity.urgency * 100)}% · Benefit ${"%.0f".format(opportunity.benefit * 100)}% · ${opportunity.kind}",
                style = MaterialTheme.typography.labelSmall,
                color = AuraThemeTokens.colors.textSecondary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = { onResolve(opportunity.id, false) }) { Text("Dismiss") }
                OutlinedButton(onClick = { onResolve(opportunity.id, true) }) { Text("Approve") }
            }
        }
    }
}

@Composable
private fun ContradictionCard(contradiction: com.aura.dream.ContradictionEntity) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                contradiction.triggerPhrase,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            if (contradiction.newerText.isNotBlank()) {
                Text(
                    contradiction.newerText,
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textSecondary,
                )
            }
        }
    }
}
