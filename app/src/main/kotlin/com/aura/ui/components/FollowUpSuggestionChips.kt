package com.aura.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aura.ui.theme.AuraSpacing
import com.aura.ui.theme.AuraThemeTokens

/**
 * Follow-up suggestions below an assistant turn.
 *
 * ## Alignment
 *
 * These sit at the message's own left edge and nowhere else. `ChatTimeline`
 * applies 16dp of content padding and `MessageBubble` adds 16dp of its own, so
 * the text starts 32dp from the screen. This used to add `AuraSpacing.xl` (32dp)
 * *inside* the timeline's padding and land at 48 — a 16dp step that made the
 * chips read as belonging to nothing, since neither the message above nor the
 * input below shared that edge. Matching `MessageBubble`'s 16dp is what makes
 * the group look attached to the turn it follows.
 *
 * ## One row, always
 *
 * Wrapping was the previous attempt at the too-narrow problem and it trades one
 * ugly outcome for another: three chips become two on one line and a single
 * orphan on the next. A horizontally scrolled row cannot produce an orphan, and
 * it is what every chat client that ships suggestion chips does, for this
 * reason. Combined with the short labels enforced in [FollowUpSuggestions], the
 * common case is that all three fit and nothing scrolls at all.
 *
 * ## Filled, not outlined
 *
 * A hairline outline on a near-black background reads as a disabled control.
 * Everything else in this app that can be tapped — the model selector, the
 * input field, the nav bar — is a filled rounded surface, so these are too.
 */
@Composable
fun FollowUpSuggestionChips(
    suggestions: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (suggestions.isEmpty()) return
    val colors = AuraThemeTokens.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(
                start = AuraSpacing.md,
                end = AuraSpacing.md,
                top = AuraSpacing.xs,
                bottom = AuraSpacing.xxs,
            ),
        horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
    ) {
        for (s in suggestions) {
            Surface(
                color = colors.surface1,
                shape = RoundedCornerShape(CHIP_HEIGHT / 2),
                modifier = Modifier.clickable { onPick(s) },
            ) {
                Box(
                    modifier = Modifier
                        .heightIn(min = CHIP_HEIGHT)
                        .padding(horizontal = AuraSpacing.md),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = s,
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.textPrimary,
                        // One line, always. A chip that grows to two breaks the
                        // row's shared height, which is what makes a set of them
                        // read as a set.
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Tall enough to tap comfortably, short enough not to compete with the answer. */
private val CHIP_HEIGHT = 36.dp
