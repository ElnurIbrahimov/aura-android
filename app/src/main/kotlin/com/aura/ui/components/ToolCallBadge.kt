package com.aura.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.viewmodel.InFlightToolCall
import com.aura.ui.theme.AuraSpacing

sealed class ToolCallState {
    data class Running(val inFlight: InFlightToolCall) : ToolCallState()
    data class Done(val name: String, val args: String, val result: String) : ToolCallState()
    data class Failed(val name: String, val args: String, val error: String) : ToolCallState()
}

@Composable
fun ToolCallBadge(
    state: ToolCallState,
    modifier: Modifier = Modifier,
) {
    val (contentColor, summary) = when (state) {
        is ToolCallState.Running -> Pair(
            AuraThemeTokens.colors.assistantAccent,
            "${state.inFlight.name}…"
        )
        is ToolCallState.Done -> Pair(
            AuraThemeTokens.colors.actionPrimary,
            state.name
        )
        is ToolCallState.Failed -> Pair(
            AuraThemeTokens.colors.error,
            state.name
        )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AuraSpacing.small),
        modifier = modifier
            .background(
                color = AuraThemeTokens.colors.surface1.copy(alpha = 0.5f),
                shape = RoundedCornerShape(AuraSpacing.xs)
            )
            .padding(horizontal = AuraSpacing.xs, vertical = AuraSpacing.xxs),
    ) {
        Box(
            modifier = Modifier
                .size(AuraSpacing.small)
                .background(
                    color = when (state) {
                        is ToolCallState.Running -> AuraThemeTokens.colors.assistantAccent
                        is ToolCallState.Done -> AuraThemeTokens.colors.actionPrimary
                        is ToolCallState.Failed -> AuraThemeTokens.colors.error
                    },
                    shape = CircleShape
                )
        )
        Text(
            text = summary,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
        )
        when (state) {
            is ToolCallState.Done -> Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "done",
                tint = AuraThemeTokens.colors.actionPrimary,
                modifier = Modifier.size(AuraSpacing.medium),
            )
            is ToolCallState.Failed -> Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "failed",
                tint = AuraThemeTokens.colors.error,
                modifier = Modifier.size(AuraSpacing.medium),
            )
            else -> {}
        }
    }
}
