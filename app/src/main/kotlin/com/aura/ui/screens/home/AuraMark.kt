package com.aura.ui.screens.home

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ripple
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Aura's identity mark: a soft light source, not a letter in a circle.
 *
 * The previous mark was the first letter of the agent's name on a tinted
 * disc, in a colour picked by `palette[name.hashCode() % 6]` — so the
 * product's brand colour was whatever `"Aura".hashCode()` happened to
 * land on (amber), sitting next to a teal-accented UI. A monogram disc is
 * also the exact shape of a contacts-app placeholder, which reads as
 * "avatar not set" rather than as a logo.
 *
 * A glow is the literal referent of the name, so the mark is drawn rather
 * than lettered: a warm-cored radial bloom inside two thin rings. It
 * carries no text, so it never changes when the assistant is renamed, and
 * it reads at any size.
 *
 * The mark is a control, not decoration. A presence that ignores touch
 * doesn't read as present — so pressing it draws the light inward and
 * dims it, and releasing lets it spring back out past its resting size.
 * The recoil is what sells it as a physical object rather than a button
 * with a state change.
 *
 * @param breath 0..1 from the caller's idle pulse; scales the bloom and
 *   counter-moves the ring opacity so the mark never blinks in lockstep.
 * @param intensity 0..1 vitality, wired to the emotion snapshot's energy.
 *   A listless Aura is visibly dimmer than an energised one.
 * @param onClick when non-null the mark becomes clickable and exposes
 *   itself to accessibility as a button.
 */
@Composable
fun AuraMark(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    core: Color = Color(0xFF5EEAD4),
    mid: Color = Color(0xFF14807A),
    breath: Float = 0f,
    intensity: Float = 1f,
    contentDescription: String = "Aura",
    onClick: (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    // Low stiffness + low damping gives a visible overshoot on release, so
    // the light springs back rather than snapping. This is the whole
    // interaction — it has to feel elastic or it feels like nothing.
    val press by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "aura-mark-press",
    )

    val clickModifier = if (onClick != null) {
        Modifier
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = false, color = core),
                role = Role.Button,
                onClickLabel = contentDescription,
                onClick = onClick,
            )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .size(size)
            .then(clickModifier)
            // Contract under the finger, then overshoot on release.
            .scale(1f - press * 0.07f),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val radius = this.size.minDimension / 2f
            // Offset the bloom's centre slightly up-left so the sphere reads
            // as lit from a single source. A centred gradient looks like a
            // flat button; an off-centre one looks like an object.
            val lightSource = Offset(
                x = this.size.width * 0.42f,
                y = this.size.height * 0.40f,
            )
            // Pressing pulls the light in and dims it — the glow retreats
            // under the touch instead of lighting up like a highlight.
            val vitality = 0.55f + intensity.coerceIn(0f, 1f) * 0.45f
            val bloom = radius * (0.86f + breath * 0.10f) * (1f - press * 0.22f)
            val alphaScale = vitality * (1f - press * 0.30f)
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.00f to core.copy(alpha = 0.92f * alphaScale),
                        0.28f to mid.copy(alpha = 0.55f * alphaScale),
                        0.68f to mid.copy(alpha = 0.16f * alphaScale),
                        1.00f to Color.Transparent,
                    ),
                    center = lightSource,
                    radius = bloom,
                ),
                radius = bloom,
                center = lightSource,
            )
            // Two hairline rings: the inner one sits just off the bloom's
            // edge and the outer one further out, which gives the mark a
            // defined silhouette against the background without a hard
            // border. They brighten as the bloom retreats, so the shell
            // stays put while the light inside moves.
            drawCircle(
                color = core.copy(alpha = (0.34f - breath * 0.10f) + press * 0.26f),
                radius = radius * 0.90f,
                style = Stroke(width = 1.2.dp.toPx()),
            )
            drawCircle(
                color = mid.copy(alpha = (0.42f - breath * 0.12f) + press * 0.20f),
                radius = radius * 0.99f,
                style = Stroke(width = 1.0.dp.toPx()),
            )
        }
    }
}
