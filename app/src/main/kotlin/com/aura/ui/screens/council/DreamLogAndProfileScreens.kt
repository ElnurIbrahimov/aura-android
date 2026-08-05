package com.aura.ui.screens.council

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.ui.components.AuraScreenShell
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.theme.AuraSpacing

@Composable
fun DreamLogScreen(
    onBack: () -> Unit,
    viewModel: DreamLogViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    AuraScreenShell(
        title = "Dream Log",
        subtitle = "What the council debated while you were away",
        action = {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.Close, contentDescription = "Back")
            }
        },
    ) { paddingValues ->
        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.5f))
            }
        } else if (state.logText.isBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(top = 80.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Filled.Bedtime,
                    contentDescription = null,
                    modifier = Modifier.padding(bottom = AuraSpacing.md),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "No council activity yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Agents will debate overnight. Check back in the morning.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = AuraSpacing.xs, start = AuraSpacing.lg, end = AuraSpacing.lg),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = AuraSpacing.md)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (state.summary.isNotBlank()) {
                    Text(
                        state.summary,
                        style = MaterialTheme.typography.labelMedium,
                        color = AuraThemeTokens.colors.assistantAccent,
                        modifier = Modifier.padding(bottom = AuraSpacing.md),
                    )
                }
                Text(
                    state.logText,
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textSecondary,
                )
            }
        }
    }
}

@Composable
fun AgentProfileScreen(
    onBack: () -> Unit,
    viewModel: AgentProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    AuraScreenShell(
        title = "Agents",
        subtitle = "Mood, energy, and relationships",
        action = {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.Close, contentDescription = "Back")
            }
        },
    ) { paddingValues ->
        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.5f))
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = AuraSpacing.md),
                verticalArrangement = Arrangement.spacedBy(AuraSpacing.md),
            ) {
                items(state.agents, key = { it.id }) { agent ->
                    AgentProfileCard(agent)
                }
            }
        }
    }
}

@Composable
private fun AgentProfileCard(agent: AgentProfileData) {
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                agent.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = AuraThemeTokens.colors.textPrimary,
            )
            if (agent.participationCount > 0) {
                Text(
                    "${agent.participationCount} sessions",
                    style = MaterialTheme.typography.labelSmall,
                    color = AuraThemeTokens.colors.textTertiary,
                )
            }
        }

        if (agent.currentGoal.isNotBlank()) {
            Spacer(modifier = Modifier.height(AuraSpacing.xxs))
            Text(
                "Goal: ${agent.currentGoal}",
                style = MaterialTheme.typography.bodySmall,
                color = AuraThemeTokens.colors.textSecondary,
            )
        }

        Spacer(modifier = Modifier.height(AuraSpacing.xs))

        // Mood bar
        LabeledBar("Mood", agent.mood, AuraThemeTokens.colors.actionPrimary)
        Spacer(modifier = Modifier.height(AuraSpacing.xxs))
        // Energy bar
        LabeledBar("Energy", agent.energy, AuraThemeTokens.colors.assistantAccent)

        // Stance
        if (agent.stanceOnUser != 0f) {
            Spacer(modifier = Modifier.height(AuraSpacing.xxs))
            Text(
                "Stance: ${stanceLabel(agent.stanceOnUser)} (${agent.stanceOnUser.toInt()})",
                style = MaterialTheme.typography.labelSmall,
                color = AuraThemeTokens.colors.textTertiary,
            )
        }

        // Relationships
        if (agent.relationships.isNotEmpty()) {
            Spacer(modifier = Modifier.height(AuraSpacing.xs))
            Text(
                "Relationships:",
                style = MaterialTheme.typography.labelSmall,
                color = AuraThemeTokens.colors.textTertiary,
            )
            agent.relationships.take(4).forEach { rel ->
                val otherId = if (rel.agentAId == agent.id) rel.agentBId else rel.agentAId
                val otherName = otherId.removePrefix("agent_").replaceFirstChar { it.uppercase() }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "  $otherName",
                        style = MaterialTheme.typography.labelSmall,
                        color = AuraThemeTokens.colors.textTertiary,
                    )
                    Text(
                        "${rel.affinity.toInt()} (${if (rel.affinity > 0) "ally" else if (rel.affinity < -20) "rival" else "neutral"})",
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            rel.affinity > 20 -> AuraThemeTokens.colors.success
                            rel.affinity < -20 -> MaterialTheme.colorScheme.error
                            else -> AuraThemeTokens.colors.textTertiary
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LabeledBar(label: kotlin.String, value: Float, color: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$label: ",
            style = MaterialTheme.typography.labelSmall,
            color = AuraThemeTokens.colors.textTertiary,
        )
        LinearProgressIndicator(
            progress = { value / 100f },
            modifier = Modifier
                .weight(1f)
                .height(AuraSpacing.xxs),
            color = color,
        )
        Text(
            " ${value.toInt()}",
            style = MaterialTheme.typography.labelSmall,
            color = AuraThemeTokens.colors.textTertiary,
        )
    }
}

private fun stanceLabel(stance: Float): kotlin.String = when {
    stance > 50 -> "very supportive"
    stance > 10 -> "supportive"
    stance > -10 -> "neutral"
    stance > -50 -> "critical"
    else -> "very critical"
}