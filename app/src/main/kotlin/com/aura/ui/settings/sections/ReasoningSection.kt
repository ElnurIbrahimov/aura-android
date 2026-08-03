package com.aura.ui.settings.sections
import com.aura.ui.theme.AuraThemeTokens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.ui.settings.SettingsViewModel
import com.aura.ui.theme.AuraSpacing

@Composable
fun ReasoningSection(
    viewModel: SettingsViewModel,
) {
    val reasoningEnabled by viewModel.reasoningEnabled.collectAsStateWithLifecycle()
    val reasoningBudget by viewModel.reasoningBudget.collectAsStateWithLifecycle()

    Text(
        "Reasoning",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    Text(
        "Extended thinking lets the model reason internally before responding. When on, every response uses maximum reasoning depth. This costs more tokens but produces significantly better answers for complex questions, code, math, and analysis.",
        style = MaterialTheme.typography.bodySmall,
        color = AuraThemeTokens.colors.textSecondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Extended Thinking", style = MaterialTheme.typography.bodyLarge)
            Text(
                if (reasoningEnabled) "ON — maximum reasoning (32K token budget)" else "OFF — faster, cheaper responses",
                style = MaterialTheme.typography.bodySmall,
                color = if (reasoningEnabled) AuraThemeTokens.colors.actionPrimary else AuraThemeTokens.colors.textSecondary,
            )
        }
        Switch(
            checked = reasoningEnabled,
            onCheckedChange = { viewModel.setReasoningEnabled(it) },
        )
    }

    if (reasoningEnabled) {
        Text(
            "Budget: ${reasoningBudget} tokens",
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textSecondary,
            modifier = Modifier.padding(horizontal = AuraSpacing.md),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("8K", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = reasoningBudget.toFloat(),
                onValueChange = { viewModel.setReasoningBudget(it.toInt()) },
                valueRange = 2000f..64000f,
                steps = 14,
                modifier = Modifier.weight(1f).padding(horizontal = AuraSpacing.xs),
            )
            Text("64K", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "Supported by: Anthropic (thinking budget), OpenAI o-series (reasoning effort), Gemini (thinking config). Other providers ignore the budget and respond normally.",
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
        )
    }

    Spacer(Modifier.height(AuraSpacing.md))
}