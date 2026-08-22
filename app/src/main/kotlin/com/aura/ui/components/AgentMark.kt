package com.aura.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush as GfxBrush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One mark per agent, used everywhere an agent is shown.
 *
 * The picker used to draw the agent's **emoji** inside a circle. Three things
 * make that read as unfinished no matter which emoji is picked:
 *
 *  - Emoji are rendered by the *device*, not by this app. Samsung's set is not
 *    Google's, so the same agent looks different on different phones and there
 *    is no way to correct it from here.
 *  - Every emoji carries its own colours, which fight the accent palette rather
 *    than participating in it.
 *  - Their optical weight and baseline vary wildly — 🤖 fills its box, ✍️ sits
 *    small and low — so a row of them never aligns.
 *
 * And the chip in the chat header threw even that away: a single
 * `Icons.Filled.Person` for all seven agents, so the one place an agent is shown
 * *while you talk to it* said nothing about which one it was.
 *
 * The replacement is a system rather than a picture. A **squircle**, not a
 * circle — a circle reads as a user's photo, a squircle reads as an identity
 * that was designed. A **duotone field**, so the mark has depth instead of being
 * a flat swatch. A **hairline ring** that carries the accent without the weight
 * of a border. And an **outlined glyph** at a single stroke weight, tinted to
 * the accent, so seven marks side by side look like one family.
 *
 * User-created agents get a **monogram** in exactly the same treatment, so they
 * belong to the set instead of falling back to something visibly lesser.
 */
@Composable
fun AgentMark(
    agentName: String,
    accentIndex: Int,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    selected: Boolean = false,
) {
    val accent = agentAccent(accentIndex)
    val glyph = agentGlyph(agentName)

    Box(
        modifier = modifier
            .size(size)
            .background(
                // Top-lit, so the mark sits in the surface rather than on it.
                // A flat fill at one alpha is the thing that makes a tinted
                // circle look like a placeholder.
                brush = GfxBrush.verticalGradient(
                    listOf(accent.copy(alpha = 0.24f), accent.copy(alpha = 0.07f)),
                ),
                shape = squircle(size),
            )
            .border(
                BorderStroke(
                    width = 1.dp,
                    // Selection is carried by the ring, not by a checkmark
                    // stacked on top: the mark itself changes state.
                    color = accent.copy(alpha = if (selected) 0.85f else 0.32f),
                ),
                shape = squircle(size),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (glyph != null) {
            Icon(
                imageVector = glyph,
                contentDescription = null,
                tint = accent,
                // ~46% of the mark. Material's outlined glyphs are drawn to a
                // 24dp box with padding already in them, so filling the square
                // makes them look oversized next to a monogram.
                modifier = Modifier.size(size * 0.46f),
            )
        } else {
            Text(
                text = monogram(agentName),
                color = accent,
                fontSize = (size.value * 0.38f).sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * Corner radius as a fraction of size, not a fixed dp.
 *
 * A fixed radius that looks right at 40dp reads as a rounded rectangle at 24dp
 * and very nearly a circle at 96dp. Scaling it keeps the same silhouette at
 * every size the mark is used, which is what makes it a system.
 */
private fun squircle(size: Dp) = RoundedCornerShape(size * 0.32f)

/**
 * The built-in specialists, by the `name` they are seeded with.
 *
 * Outlined rather than filled: the outlined set shares a single stroke weight,
 * so seven of them in a column look deliberate. The filled set varies in mass
 * from glyph to glyph and reads as clip art at this size.
 *
 * Null for anything unrecognised — a user-created agent gets a monogram, which
 * is a better answer than a generic person icon shared with everyone else.
 */
private fun agentGlyph(name: String): ImageVector? = when (name.trim().lowercase()) {
    "general" -> Icons.Outlined.AutoAwesome
    "coder" -> Icons.Outlined.Terminal
    "researcher" -> Icons.Outlined.TravelExplore
    "writer" -> Icons.Outlined.EditNote
    "creative" -> Icons.Outlined.Brush
    "executive" -> Icons.Outlined.EventNote
    "phone_native" -> Icons.Outlined.Smartphone
    else -> null
}

/** First character, uppercased. Skips leading punctuation so "*Scout" is S, not *. */
private fun monogram(name: String): String =
    name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "A"

/**
 * A colour per agent, so the marks are not seven identical squares.
 *
 * [com.aura.agent.AgentEntity.color] is seeded as the specialist's *index*, not
 * an ARGB value — reading it as a colour would paint every mark near-black.
 * Treating it as what it is, an index into a palette, is why this takes an Int
 * and wraps.
 *
 * Ordered so that adjacent agents in the picker are adjacent in hue-distance
 * too: a list that runs blue, green, amber, violet never has two neighbours a
 * user has to look twice at.
 */
private val AGENT_ACCENTS = listOf(
    Color(0xFF6EA8FE), // general    — blue
    Color(0xFF7BD88F), // coder      — green
    Color(0xFFFFC663), // researcher — amber
    Color(0xFFB58BFF), // writer     — violet
    Color(0xFFFF8FA3), // creative   — rose
    Color(0xFF5BD6C8), // executive  — teal
    Color(0xFFFF9F6E), // phone      — coral
)

/**
 * How many accents there are to choose between.
 *
 * Exposed so the agent editor can offer exactly the palette [agentAccent]
 * wraps into, rather than hardcoding a number that goes stale the day a colour
 * is added.
 */
internal val AGENT_ACCENT_COUNT: Int = AGENT_ACCENTS.size

internal fun agentAccent(index: Int): Color =
    AGENT_ACCENTS[((index % AGENT_ACCENTS.size) + AGENT_ACCENTS.size) % AGENT_ACCENTS.size]
