package com.aura.ui.voice

import com.aura.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.aura.realtime.RealtimeAvailability
import com.aura.ui.theme.AuraSpacing
import com.aura.ui.theme.AuraThemeTokens

/**
 * Choose between a live call and push-to-talk.
 *
 * Both options are always shown, and the live one is **disabled with its reason
 * visible** rather than hidden when unavailable. A disabled row teaches that the
 * capability exists and what it needs; a hidden one teaches nothing, and the
 * user never discovers the feature they are paying an OpenAI key for.
 */
@Composable
fun LiveCallSheet(
    availability: RealtimeAvailability.Availability,
    onStartLiveCall: (model: String) -> Unit,
    onStartPushToTalk: () -> Unit,
) {
    val colors = AuraThemeTokens.colors
    Column(modifier = Modifier.fillMaxWidth().padding(AuraSpacing.medium)) {
        Text(
            text = "Voice",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(AuraSpacing.sm))

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.live_call), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = when (availability) {
                        is RealtimeAvailability.Availability.Ready ->
                            "Low latency, interruptible — ${availability.model.substringAfter(':')}"

                        is RealtimeAvailability.Availability.WouldSwitchModel ->
                            // Stated up front, before the call starts. A user
                            // chatting with Claude who taps this is moved to a
                            // different model with a different personality and
                            // no access to this conversation's memory. Hiding
                            // that would be a trust bug.
                            "Switches from ${availability.from.substringAfter(':')} to " +
                                "${availability.to.substringAfter(':')} for the call"

                        is RealtimeAvailability.Availability.Unavailable -> availability.reason
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textPrimary.copy(alpha = 0.6f),
                )
            }
            Button(
                enabled = availability !is RealtimeAvailability.Availability.Unavailable,
                onClick = {
                    when (availability) {
                        is RealtimeAvailability.Availability.Ready -> onStartLiveCall(availability.model)
                        is RealtimeAvailability.Availability.WouldSwitchModel -> onStartLiveCall(availability.to)
                        is RealtimeAvailability.Availability.Unavailable -> Unit
                    }
                },
            ) { Text(stringResource(R.string.call)) }
        }

        Spacer(modifier = Modifier.height(AuraSpacing.sm))

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.voice_mode), style = MaterialTheme.typography.bodyLarge)
                Text(
                    // Push-to-talk stays the default and keeps working with
                    // every provider. It is also the only option that works
                    // offline, since the platform recogniser can run on-device.
                    text = "Push to talk — works with any model, and offline",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textPrimary.copy(alpha = 0.6f),
                )
            }
            OutlinedButton(onClick = onStartPushToTalk) { Text(stringResource(R.string.start)) }
        }
    }
}

/**
 * The status line shown during a live call.
 *
 * The model name is always visible, for the same reason the sheet names the
 * switch: on a call where the assistant sounds different and remembers nothing,
 * the user deserves to know why without having to guess.
 */
@Composable
fun LiveCallStatus(
    phase: String,
    modelName: String,
    remainingSeconds: Long,
    echoCancellation: Boolean,
) {
    val colors = AuraThemeTokens.colors
    Column(
        modifier = Modifier.fillMaxWidth().padding(AuraSpacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AuraSpacing.xxs),
    ) {
        Text(phase, style = MaterialTheme.typography.titleSmall)
        Text(
            text = "$modelName · ${remainingSeconds / 60}:${"%02d".format(remainingSeconds % 60)} left",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textPrimary.copy(alpha = 0.6f),
        )
        if (!echoCancellation) {
            // Worth saying out loud: without the platform echo canceller the
            // assistant hears itself through the speaker and interrupts itself.
            // Headphones fix it, and the user cannot know that unless told.
            Text(
                text = "Echo cancellation unavailable on this device — use headphones",
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
            )
        }
    }
}
