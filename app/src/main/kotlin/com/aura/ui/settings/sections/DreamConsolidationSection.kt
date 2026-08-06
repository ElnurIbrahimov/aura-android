package com.aura.ui.settings.sections

import com.aura.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aura.ui.settings.SettingsSection
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.theme.AuraSpacing
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.Icons

/**
 * Settings card for the dream-consolidator worker. Surfaces the
 * toggle, last-run timestamp, total summary count, and a "Run now"
 * button.
 *
 * Layout: standard settings section with title + body. The body
 * holds: (1) toggle row, (2) stats rows (last run, total summaries),
 * (3) Run now button.
 *
 * The "last run" timestamp is shown in relative form ("2 days ago"
 * or "Never") — absolute timestamps are useless in this context.
 */
@Composable
fun DreamConsolidationSection(
    enabled: Boolean,
    lastRunAt: Long,
    lastRunStats: String,
    totalSummaries: Int,
    isRunning: Boolean,
    onSetEnabled: (Boolean) -> Unit,
    onRunNow: () -> Unit,
) {
    SettingsSection(
        icon = Icons.Filled.Bedtime, // 🌟 "star" emoji for dream / sleep theme
        title = "Memory consolidation (Dream)",
        subtitle = "Cluster paraphrases into single summaries while charging",
        initialExpanded = false,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AuraSpacing.xxs, vertical = AuraSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.consolidate_memories_while_idle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = AuraThemeTokens.colors.textPrimary,
                )
                Text(
                    text = stringResource(R.string.daily_while_charging_clusters_paraphrases_into),
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textSecondary,
                )
            }
            Spacer(modifier = Modifier.padding(AuraSpacing.xs))
            Switch(
                checked = enabled,
                onCheckedChange = onSetEnabled,
            )
        }
        if (enabled) {
            Spacer(modifier = Modifier.height(AuraSpacing.xs))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AuraSpacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.last_ran),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AuraThemeTokens.colors.textSecondary,
                )
                Text(
                    text = formatRelativeTime(lastRunAt),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = AuraThemeTokens.colors.textPrimary,
                )
            }
            if (lastRunStats.isNotEmpty()) {
                Spacer(modifier = Modifier.height(AuraSpacing.xxs))
                Text(
                    text = lastRunStats,
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textSecondary,
                    modifier = Modifier.padding(horizontal = AuraSpacing.xxs),
                )
            }
            Spacer(modifier = Modifier.height(AuraSpacing.xxs))
            Text(
                text = "Total dream summaries: $totalSummaries",
                style = MaterialTheme.typography.bodySmall,
                color = AuraThemeTokens.colors.textSecondary,
                modifier = Modifier.padding(horizontal = AuraSpacing.xxs),
            )
            Spacer(modifier = Modifier.height(AuraSpacing.sm))
            OutlinedButton(
                onClick = onRunNow,
                enabled = !isRunning,
                shape = RoundedCornerShape(AuraSpacing.sm),
                modifier = Modifier.padding(horizontal = AuraSpacing.xxs),
            ) {
                Text(if (isRunning) "Running…" else "Run now")
            }
        }
    }
}

/**
 * Format a millis timestamp as a relative time string. Returns
 * "Never" for 0 or future timestamps (defensive — the bootstrap
 * should never produce a future timestamp).
 *
 * Buckets:
 *  - 0 or future → "Never"
 *  - < 60s → "Just now"
 *  - < 60min → "Nm ago" (N = minutes)
 *  - < 24h → "Nh ago" (N = hours)
 *  - < 30d → "Nd ago" (N = days)
 *  - else → date string "MMM d" (no year — we don't care about
 *    the year for "last ran" UX)
 */
private fun formatRelativeTime(timestampMs: Long): String {
    if (timestampMs <= 0L) return "Never"
    val now = System.currentTimeMillis()
    if (timestampMs > now) return "Never"
    val delta = now - timestampMs
    val minutes = delta / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 30 -> "${days}d ago"
        else -> {
            val date = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
            date.format(java.util.Date(timestampMs))
        }
    }
}
