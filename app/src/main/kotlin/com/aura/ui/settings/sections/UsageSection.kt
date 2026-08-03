package com.aura.ui.settings.sections

import com.aura.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aura.ui.settings.SettingsSection
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.util.modelDisplayName
import com.aura.usage.UsageSnapshot
import com.aura.ui.theme.AuraSpacing

@Composable
fun UsageSection(
    usage: UsageSnapshot,
    onReset: () -> Unit,
) {
    SettingsSection(
        emoji = "\uD83D\uDCCA",
        title = "Usage",
        subtitle = "${usage.totalTokens} tokens across ${usage.calls} model calls",
        initialExpanded = false,
    ) {
        Text(
            text = "${usage.promptTokens} input - ${usage.completionTokens} output tokens",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Tool results processed: ${"%.1f".format(usage.toolResultChars / 1000.0)} KB",
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.65f),
        )
        Spacer(modifier = Modifier.height(AuraSpacing.xs))
        if (usage.models.isEmpty()) {
            Text(
                "No model calls recorded yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.65f),
            )
        } else {
            usage.models.take(8).forEach { model ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = modelDisplayName(model.modelId),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "${model.promptTokens + model.completionTokens} - ${model.calls} calls${if (model.estimated) " ~" else ""}",
                        style = MaterialTheme.typography.labelMedium,
                        color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.65f),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(AuraSpacing.xs))
        Text(
            text = stringResource(R.string.marks_estimated_tokens_cost_is_not),
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.55f),
        )
        var confirmUsageReset by remember { mutableStateOf(false) }
        TextButton(onClick = { confirmUsageReset = true }) { Text(stringResource(R.string.reset_usage)) }
        if (confirmUsageReset) {
            AlertDialog(
                onDismissRequest = { confirmUsageReset = false },
                title = { Text(stringResource(R.string.reset_usage_history)) },
                text = { Text(stringResource(R.string.this_clears_token_call_and_tool)) },
                confirmButton = {
                    TextButton(onClick = {
                        onReset()
                        confirmUsageReset = false
                    }) { Text(stringResource(R.string.reset)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmUsageReset = false }) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
    }
}