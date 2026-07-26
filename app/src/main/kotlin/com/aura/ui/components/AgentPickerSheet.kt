package com.aura.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aura.agent.AgentEntity
import com.aura.ui.theme.AuraThemeTokens

/**
 * Bottom sheet that lets the user pick an active [AgentEntity] for the current chat.
 * A "No agent" row lets the user return to the default persona.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentPickerSheet(
    currentAgent: AgentEntity?,
    agents: List<AgentEntity>,
    onPick: (AgentEntity?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AuraThemeTokens.colors.background,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text(
                text = "Select agent",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = AuraThemeTokens.colors.textPrimary,
            )
            Spacer(Modifier.height(12.dp))
            LazyColumn {
                item {
                    AgentRow(
                        name = "No agent",
                        description = "Default Aura assistant",
                        selected = currentAgent == null,
                        onClick = { onPick(null) },
                    )
                }
                items(agents) { agent ->
                    AgentRow(
                        name = agent.name,
                        description = agent.personality().toPromptDirective().takeIf { it.isNotBlank() }
                            ?: agent.description,
                        selected = agent == currentAgent,
                        onClick = { onPick(agent) },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun AgentRow(
    name: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Person,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (selected) AuraThemeTokens.colors.actionPrimary
            else AuraThemeTokens.colors.textSecondary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = AuraThemeTokens.colors.textPrimary,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = AuraThemeTokens.colors.textTertiary,
                maxLines = 1,
            )
        }
        RadioButton(
            selected = selected,
            onClick = onClick,
        )
    }
    HorizontalDivider(color = AuraThemeTokens.colors.borderSubtle)
}
