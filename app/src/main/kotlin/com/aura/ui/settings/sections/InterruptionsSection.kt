package com.aura.ui.settings.sections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.aura.proactive.InterruptionPolicy
import com.aura.proactive.InterruptionVerdict
import com.aura.ui.settings.SettingsSection
import com.aura.ui.theme.AuraSpacing
import com.aura.ui.theme.AuraThemeTokens

/**
 * Which kinds of suggestion have earned the right to interrupt, and why.
 *
 * The explanation ships with the notification rather than after it. A
 * notification whose reason you cannot read is exactly what this design exists
 * to avoid, so every category states its standing as a sentence built from the
 * same rows the decision was made on.
 *
 * Three states rather than a switch, defaulting to **Earned** so nothing needs
 * configuring — the ledger decides unless you say otherwise. The override is
 * there because the ledger measures what worked, and that is not the same as
 * what you want: a category Aura cannot measure would otherwise be silenced
 * forever even if you'd like to hear from it.
 */
@Composable
fun InterruptionsSection(
    verdicts: List<InterruptionVerdict>,
    policies: Map<String, InterruptionPolicy>,
    onSetPolicy: (String, InterruptionPolicy) -> Unit,
) {
    SettingsSection(
        icon = Icons.Filled.NotificationsActive,
        title = "Interruptions",
        subtitle = "What has earned the right to notify you",
        initialExpanded = false,
    ) {
        Text(
            "Everything starts in the app and silent. A kind of suggestion earns a notification " +
                "only once enough of them have actually led somewhere — and loses it again if they stop.",
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textSecondary,
        )

        if (verdicts.isEmpty()) {
            Text(
                "Nothing measured yet.",
                style = MaterialTheme.typography.bodySmall,
                color = AuraThemeTokens.colors.textSecondary,
                modifier = Modifier.padding(top = AuraSpacing.sm),
            )
            return@SettingsSection
        }

        for (verdict in verdicts) {
            HorizontalDivider(
                color = AuraThemeTokens.colors.borderSubtle,
                modifier = Modifier.padding(vertical = AuraSpacing.sm),
            )
            Column(verticalArrangement = Arrangement.spacedBy(AuraSpacing.xs)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        readableName(verdict.type.wire),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (verdict.mayInterrupt) "Notifying" else "In-app",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (verdict.mayInterrupt) {
                            AuraThemeTokens.colors.actionPrimary
                        } else {
                            AuraThemeTokens.colors.textSecondary
                        },
                    )
                }
                Text(
                    verdict.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs)) {
                    val current = policies[verdict.type.wire] ?: InterruptionPolicy.EARNED
                    for (option in InterruptionPolicy.entries) {
                        FilterChip(
                            selected = current == option,
                            onClick = { onSetPolicy(verdict.type.wire, option) },
                            label = {
                                Text(
                                    when (option) {
                                        InterruptionPolicy.ALWAYS -> "Always"
                                        InterruptionPolicy.NEVER -> "Never"
                                        InterruptionPolicy.EARNED -> "Earned"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun readableName(wire: String): String = when (wire) {
    "stale_memories" -> "Fading memories"
    "stuck_tasks" -> "Stuck tasks"
    "relationship_gap" -> "Quiet stretches"
    "deadline_approaching" -> "Today's events"
    "contradiction_alert" -> "Graph conflicts"
    "stress_correlation" -> "Tension"
    "pattern_alert" -> "Conversation patterns"
    "priority_shift" -> "Priority pile-ups"
    else -> wire
}
