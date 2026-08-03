package com.aura.ui.screens.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.emotion.EmotionEngine
import com.aura.ui.theme.AuraSpacing
import com.aura.ui.theme.AuraThemeTokens

/**
 * Persistent agent presence on the Home screen: avatar, name, mood caption.
 * The surface color and pulse derive from the agent name and the latest
 * emotional snapshot so the entity feels alive before the user types.
 */
@Composable
fun AgentPresence(
    agentName: String?,
    memoryCallback: String?,
    emotionSnapshot: EmotionEngine.EmotionSnapshot?,
    modifier: Modifier = Modifier,
    affinityLevel: String = "",
    affinityProgress: Float = 0f,
) {
    val colors = AuraThemeTokens.colors
    val name = agentName ?: "Aura"
    val tint = rememberAgentColor(name)
    val pulse = rememberAgentPulse()

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .scale(1f + pulse * 0.04f)
                .alpha(0.9f + pulse * 0.1f),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .background(tint.copy(alpha = 0.12f), CircleShape)
                    .border(2.dp, tint.copy(alpha = 0.35f), CircleShape),
            )
            Text(
                text = name.take(1).uppercase(),
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = tint,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(AuraSpacing.sm))
        Text(
            text = name,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        val caption = rememberAgentCaption(emotionSnapshot, memoryCallback)
        if (caption != null) {
            Spacer(modifier = Modifier.height(AuraSpacing.xxs))
            Text(
                text = caption,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = AuraSpacing.lg),
            )
        }
        // Affinity progress bar
        if (affinityLevel.isNotBlank()) {
            Spacer(modifier = Modifier.height(AuraSpacing.sm))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AuraSpacing.xl),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = affinityLevel,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { affinityProgress },
                    modifier = Modifier
                        .weight(1f)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = tint,
                    trackColor = colors.surface2,
                )
            }
        }
    }
}

@Composable
private fun rememberAgentPulse(): Float {
    val infinite = rememberInfiniteTransition(label = "agent-pulse")
    return infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    ).value
}

private fun rememberAgentColor(name: String): Color {
    val palette = listOf(
        Color(0xFF0F766E), // teal-700 (brand)
        Color(0xFF2DD4BF), // teal-400
        Color(0xFF3B82F6), // blue
        Color(0xFFF59E0B), // amber
        Color(0xFFEC4899), // pink
        Color(0xFF06B6D4), // cyan
    )
    return palette[Math.floorMod(name.hashCode(), palette.size)]
}

private fun rememberAgentCaption(
    snapshot: EmotionEngine.EmotionSnapshot?,
    memoryCallback: String?,
): String? {
    if (!memoryCallback.isNullOrBlank()) return memoryCallback
    return snapshot?.let { s ->
        when {
            s.tension > 0.6f -> "Things feel intense right now."
            s.connection > 0.7f -> "Good to see you again."
            s.energy > 0.7f -> "Ready when you are."
            s.focus > 0.7f -> "Let's dig into something."
            else -> "What should we explore?"
        }
    } ?: "What should we explore?"
}
