package com.aura.ui.voice

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.ui.theme.AuraThemeTokens

/**
 * Voice Call Screen — a full-screen phone-call-style UI for
 * continuous voice mode. Natural turn-taking with visual state
 * feedback.
 *
 * States:
 * - LISTENING: animated waveform, "Listening…" label, mic on
 * - THINKING: pulsing glow, "Thinking…" label
 * - SPEAKING: pulsing glow, "Speaking…" label
 *
 * Controls:
 * - Mute button: toggles STT (user can hear but not respond)
 * - End Call button: red FAB, stops everything, returns to chat
 * - Call timer: shows duration
 *
 * Replaces the ContinuousVoiceOverlay for extended voice sessions.
 * The overlay is kept for quick voice input; this screen is for
 * full conversations.
 */
@Composable
fun VoiceCallScreen(
    state: ContinuousVoiceState,
    callDurationMs: Long,
    isMuted: Boolean,
    onToggleMute: () -> Unit,
    onEndCall: () -> Unit,
) {
    val colors = AuraThemeTokens.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(colors.surface0, colors.background),
                ),
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Spacer(modifier = Modifier.height(60.dp))

        // Agent avatar with breathing glow
        Box(
            modifier = Modifier
                .size(160.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Glow rings
            val glowAlpha = when (state.phase) {
                VoiceModeState.LISTENING -> 0.3f
                VoiceModeState.THINKING -> 0.15f
                VoiceModeState.SPEAKING -> 0.25f
                else -> 0.1f
            }
            val infinite = rememberInfiniteTransition(label = "voice-glow")
            val pulse by infinite.animateFloat(
                initialValue = 0.8f,
                targetValue = 1.2f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2000),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "pulse",
            )
            Box(
                modifier = Modifier
                    .size((140 * pulse).dp)
                    .clip(CircleShape)
                    .background(colors.actionPrimary.copy(alpha = glowAlpha * pulse)),
            )
            // Avatar circle
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(colors.actionPrimary.copy(alpha = 0.8f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "A",
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }
        }

        // State label
        val stateLabel = when (state.phase) {
            VoiceModeState.LISTENING -> "Listening…"
            VoiceModeState.THINKING -> "Thinking…"
            VoiceModeState.SPEAKING -> "Speaking…"
            VoiceModeState.IDLE -> "Ready"
        }
        Text(
            text = stateLabel,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
        )

        // Partial transcript (if listening)
        if (state.phase == VoiceModeState.LISTENING && state.partialTranscript.isNotBlank()) {
            Text(
                text = state.partialTranscript,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
            )
        }

        // Call duration
        val seconds = (callDurationMs / 1000).toInt()
        val mins = seconds / 60
        val secs = seconds % 60
        Text(
            text = "%02d:%02d".format(mins, secs),
            style = MaterialTheme.typography.labelLarge,
            color = colors.textSecondary,
        )

        Spacer(modifier = Modifier.weight(1f))

        // Control buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 48.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Mute button
            FloatingActionButton(
                onClick = onToggleMute,
                modifier = Modifier.size(64.dp),
                containerColor = if (isMuted) colors.surface2 else colors.surface1,
            ) {
                Icon(
                    imageVector = if (isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    contentDescription = if (isMuted) "Unmute" else "Mute",
                    tint = if (isMuted) colors.textSecondary else colors.textPrimary,
                )
            }

            // End Call button (red)
            FloatingActionButton(
                onClick = onEndCall,
                modifier = Modifier.size(72.dp),
                containerColor = Color(0xFFEF4444),
            ) {
                Icon(
                    imageVector = Icons.Filled.CallEnd,
                    contentDescription = "End call",
                    tint = Color.White,
                )
            }
        }
    }
}