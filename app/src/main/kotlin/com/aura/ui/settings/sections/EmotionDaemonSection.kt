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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aura.emotion.EmotionEngine
import com.aura.ui.settings.SettingsSection
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.theme.AuraSpacing

@Composable
fun EmotionDaemonSection(
    emotionSnapshot: EmotionEngine.EmotionSnapshot?,
    daemonEnabled: Boolean,
    daemonThoughtsCount: Int,
    onSetDaemonEnabled: (Boolean) -> Unit,
) {
    SettingsSection(
        emoji = "\uD83E\uDDF7",
        title = "Emotion & Daemon",
        subtitle = "Emotional state tracking and background thinking",
        initialExpanded = false,
    ) {
        Text(
            text = stringResource(R.string.aura_tracks_the_emotional_tone_of),
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
        )
        Spacer(modifier = Modifier.height(AuraSpacing.sm))

        // Emotional state display
        Text(
            text = stringResource(R.string.current_emotional_state),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(AuraSpacing.small))

        if (emotionSnapshot != null) {
            EmotionBar("Tension", emotionSnapshot.tension, "Calm", "Stressed")
            Spacer(modifier = Modifier.height(AuraSpacing.xxs))
            EmotionBar("Connection", emotionSnapshot.connection, "Distant", "Warm")
            Spacer(modifier = Modifier.height(AuraSpacing.xxs))
            EmotionBar("Energy", emotionSnapshot.energy, "Slow", "Energetic")
            Spacer(modifier = Modifier.height(AuraSpacing.xxs))
            EmotionBar("Focus", emotionSnapshot.focus, "Casual", "Focused")

            Spacer(modifier = Modifier.height(AuraSpacing.xs))
            Text(
                text = "Updated ${formatRelativeTimeLong(emotionSnapshot.updatedAt)}",
                style = MaterialTheme.typography.labelSmall,
                color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.5f),
            )
        } else {
            Text(
                text = stringResource(R.string.no_emotional_data_yet_start_a),
                style = MaterialTheme.typography.bodySmall,
                color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.5f),
                modifier = Modifier.padding(vertical = AuraSpacing.xs),
            )
        }

        Spacer(modifier = Modifier.height(AuraSpacing.md))

        // Daemon toggle
        Surface(
            color = AuraThemeTokens.colors.surface1,
            shape = RoundedCornerShape(AuraSpacing.medium),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(AuraSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.background_thinking_daemon),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = if (daemonEnabled)
                            "On - Aura reviews recent context every ~8 min and posts insights to proactive history"
                        else
                            "Off - no background thinking",
                        style = MaterialTheme.typography.bodySmall,
                        color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.6f),
                    )
                    if (daemonEnabled && daemonThoughtsCount > 0) {
                        Spacer(modifier = Modifier.height(AuraSpacing.xxs))
                        Text(
                            text = "$daemonThoughtsCount daemon thoughts recorded. See Proactive History for the full list.",
                            style = MaterialTheme.typography.labelSmall,
                            color = AuraThemeTokens.colors.actionPrimary,
                        )
                    }
                }
                Switch(checked = daemonEnabled, onCheckedChange = onSetDaemonEnabled)
            }
        }
    }
}

@Composable
private fun EmotionBar(
    label: String,
    value: Float,
    lowLabel: String,
    highLabel: String,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(R.string.s_0f).format(value * 100),
                style = MaterialTheme.typography.labelMedium,
                color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.7f),
            )
        }
        Spacer(modifier = Modifier.height(AuraSpacing.tiny))
        LinearProgressIndicator(
            progress = { value.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(AuraSpacing.xxs),
            color = AuraThemeTokens.colors.actionPrimary,
            trackColor = AuraThemeTokens.colors.surface1,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(lowLabel, style = MaterialTheme.typography.labelSmall, color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.4f))
            Text(highLabel, style = MaterialTheme.typography.labelSmall, color = AuraThemeTokens.colors.textPrimary.copy(alpha = 0.4f))
        }
    }
}

// Settings-panel relative time: verbose "5 min ago" / "3h ago" format
// for the emotion/daemon status. Distinct from the chat-screen's brief
// `formatRelativeTime` in com.aura.ui.util.TimeFormat (which says "5m").
// Kept separate because the two surfaces call for different tone: the
// chat shows seconds-old context ("just now"), settings shows
// "updated 3 hours ago" — the latter reads more naturally in verbose form.
private fun formatRelativeTimeLong(timestampMs: Long): String {
    val diff = System.currentTimeMillis() - timestampMs
    return when {
        diff < 60_000 -> "just now"
        diff < 3_600_000 -> "${diff / 60_000} min ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        else -> "${diff / 86_400_000}d ago"
    }
}