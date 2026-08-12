package com.aura.ui.screens
import com.aura.ui.theme.AuraThemeTokens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.Column
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
import com.aura.ui.components.AuraScreenShell
import com.aura.ui.theme.AuraSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldModelScreen(
    onBack: () -> Unit = {},
    onProactiveAction: (com.aura.proactive.ProactiveAction) -> Unit = {},
    viewModel: WorldModelViewModel = viewModel(),
) {
    AuraScreenShell(
        title = "World model",
        subtitle = "Beliefs, events, and opportunities",
        action = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(AuraSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AuraSpacing.sm),
        ) {
            worldModelSection(viewModel, onProactiveAction)
        }
    }
}

/**
 * The world model as sections of somebody else's list.
 *
 * A `LazyListScope` extension rather than a composable, so `MindScreen` can put
 * it in the same scroll container as everything else — nesting a LazyColumn
 * inside another scrollable throws at measure time, which is why
 * `LivingWorldSection` is shaped this way too.
 */
internal fun LazyListScope.worldModelSection(
    viewModel: WorldModelViewModel,
    onProactiveAction: (com.aura.proactive.ProactiveAction) -> Unit = {},
    showEmptyState: Boolean = true,
    /**
     * False in `MindScreen`, where `beliefsSection` renders them instead —
     * that rendering carries the supporting evidence and the superseded chain,
     * which this view model has never loaded.
     */
    includeBeliefs: Boolean = true,
) {
    item {
        val beliefs by viewModel.beliefs.collectAsStateWithLifecycle()
        val events by viewModel.worldEvents.collectAsStateWithLifecycle()
        val opportunities by viewModel.opportunities.collectAsStateWithLifecycle()
        val contradictions by viewModel.contradictions.collectAsStateWithLifecycle()

        Column(verticalArrangement = Arrangement.spacedBy(AuraSpacing.sm)) {
            if (includeBeliefs && beliefs.isNotEmpty()) {
                SectionHeading("Beliefs (${beliefs.size})", top = false)
                for (belief in beliefs) {
                    BeliefCard(
                        belief = belief,
                        onVerify = { viewModel.verifyBelief(belief.id) },
                        onRetire = { viewModel.retireBelief(belief.id) },
                    )
                }
            }
            if (events.isNotEmpty()) {
                SectionHeading("World events (${events.size})")
                for (event in events) WorldEventCard(event)
            }
            if (opportunities.isNotEmpty()) {
                SectionHeading("Opportunities (${opportunities.size})")
                for (opportunity in opportunities) {
                    OpportunityCard(opportunity) { id, approve ->
                        // Approving now performs the suggestion instead of only
                        // recording that it was approved.
                        onProactiveAction(viewModel.resolveOpportunity(id, approve))
                    }
                }
            }
            if (contradictions.isNotEmpty()) {
                SectionHeading("Contradictions (${contradictions.size})")
                for (contradiction in contradictions) ContradictionCard(contradiction)
            }
            if (showEmptyState &&
                beliefs.isEmpty() && events.isEmpty() && opportunities.isEmpty() && contradictions.isEmpty()
            ) {
                Text(
                    "No world-model data yet. Beliefs, events, and opportunities are created as the system learns.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AuraThemeTokens.colors.textSecondary,
                    modifier = Modifier.padding(top = AuraSpacing.xl),
                )
            }
        }
    }
}

@Composable
internal fun SectionHeading(text: String, top: Boolean = true) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = if (top) Modifier.padding(top = AuraSpacing.md) else Modifier,
    )
}

@Composable
private fun BeliefCard(
    belief: com.aura.world.BeliefEntity,
    onVerify: () -> Unit,
    onRetire: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(AuraSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AuraSpacing.xxs),
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
                horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
                modifier = Modifier.padding(top = AuraSpacing.xxs),
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
            modifier = Modifier.padding(AuraSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AuraSpacing.xxs),
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
            modifier = Modifier.padding(AuraSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AuraSpacing.xxs),
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
            modifier = Modifier.padding(AuraSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AuraSpacing.xxs),
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
