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
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.Icons

@Composable
fun UsageSection(
    usage: UsageSnapshot,
    onReset: () -> Unit,
    backgroundSpend: com.aura.usage.BackgroundSpend = com.aura.usage.BackgroundSpend(),
) {
    SettingsSection(
        icon = Icons.Filled.BarChart,
        title = "Usage",
        subtitle = "${usage.totalTokens} tokens across ${usage.calls} model calls",
        initialExpanded = false,
    ) {
        Text(
            text = "${usage.promptTokens} input - ${usage.completionTokens} output tokens",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        // Today's unattended spend, against its ceiling.
        //
        // A different question from the cumulative total above: that one is
        // everything Aura has ever done, this is what it did today while nobody
        // was watching. Before the cap there was no number for it at all, which
        // is how a daemon on an expensive model gets noticed on an invoice.
        Text(
            text = "Background today: ${backgroundSpend.tokens} / ${backgroundSpend.limit} tokens" +
                if (backgroundSpend.blockedCalls > 0) " — ${backgroundSpend.blockedCalls} held back" else "",
            style = MaterialTheme.typography.bodySmall,
            color = if (backgroundSpend.exhausted) {
                MaterialTheme.colorScheme.error
            } else {
                AuraThemeTokens.colors.textPrimary.copy(alpha = 0.65f)
            },
        )
        Text(
            text = "Tool results processed: ${"%.1f".format(usage.toolResultChars / 1000.0)} KB",
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.65f),
        )
        // The prompt-cache line. This is the whole measurement behind the
        // decision on dynamic tool selection, and before it was here that
        // evidence existed only as a debug log — which in practice means it
        // would never have been read, and the decision would have been made on
        // intuition after all the work of making it measurable.
        if (usage.cacheReportingModels.isNotEmpty()) {
            val pct = (usage.measuredCacheHitRate * 100).toInt()
            val reporting = usage.cacheReportingModels.size
            Text(
                text = "Prompt cache: $pct% of input tokens served from cache",
                style = MaterialTheme.typography.bodySmall,
                color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.65f),
            )
            Text(
                // Named explicitly, because the rate is over these models only.
                // Folding in providers that never report the field would drag
                // the number toward zero and read as "caching is broken".
                text = "measured across $reporting of ${usage.models.size} models — " +
                    "the rest do not report cache figures",
                style = MaterialTheme.typography.bodySmall,
                color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.5f),
            )
        }
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