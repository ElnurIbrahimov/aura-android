package com.aura.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aura.agent.AgentEntity
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.theme.AuraSpacing

/**
 * A single chip showing the currently active [AgentEntity]. Tapping it
 * opens the agent picker.
 */
@Composable
fun AgentChip(
    agent: AgentEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
    ) {
        FilterChip(
            selected = true,
            onClick = onClick,
            label = {
                Text(
                    text = agent.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = AuraThemeTokens.colors.textPrimary,
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = AuraThemeTokens.colors.actionPrimary,
                )
            },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = AuraThemeTokens.colors.surface1,
                selectedLabelColor = AuraThemeTokens.colors.textPrimary,
            ),
            border = FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = true,
                borderColor = AuraThemeTokens.colors.borderSubtle,
                selectedBorderColor = AuraThemeTokens.colors.actionPrimary.copy(alpha = 0.4f),
            ),
        )
    }
}
