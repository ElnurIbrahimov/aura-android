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
import com.aura.ui.util.agentDisplayName

/**
 * A single chip showing the currently active [AgentEntity]. Tapping it
 * opens the agent picker.
 *
 * Carries the agent's own [AgentMark]. It previously showed
 * `Icons.Filled.Person` tinted with the theme's action colour — the same icon,
 * in the same colour, for all seven agents — so the one place an agent is
 * visible *while you are talking to it* was the one place that did not say
 * which agent it was.
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
                    text = agentDisplayName(agent.name),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = AuraThemeTokens.colors.textPrimary,
                )
            },
            leadingIcon = {
                // 22dp, not the chip's usual 18: the mark is a filled squircle
                // rather than a bare glyph, so it needs a little more room to
                // read as the same object shown in the picker.
                AgentMark(
                    agentName = agent.name,
                    accentIndex = agent.color,
                    size = 22.dp,
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
                // The chip's own edge steps back so the mark carries the colour.
                // Two accents competing at this size reads as noise.
                selectedBorderColor = AuraThemeTokens.colors.borderSubtle,
            ),
        )
    }
}
