package com.aura.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
 * Three things were wrong with the first version, and they compounded:
 *
 *  - A plain [androidx.compose.foundation.layout.Row] cannot wrap. With three
 *    chips in 85% of the width, the last one got squeezed until its *text*
 *    wrapped, so a single chip stood at twice the height of its neighbours and
 *    the row read as broken rather than as a set.
 *  - The chips were filled surfaces at the same weight as the recall chip
 *    directly beneath them, so two different kinds of thing — an action you can
 *    take and a note about where the answer came from — looked identical and
 *    stacked into one grey jumble.
 *  - Nothing bounded the height, so uniformity was accidental.
 *
 * Now: [FlowRow] wraps whole chips onto a second line instead of squeezing
 * text, every chip is one line at a fixed minimum height, and they are outlined
 * rather than filled so they read as offers rather than as content. The
 * suggestion labels themselves were shortened in [FollowUpSuggestions] — the
 * layout should not have to rescue a two-line button.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FollowUpSuggestionChips(
    suggestions: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (suggestions.isEmpty()) return
    val colors = AuraThemeTokens.colors
    FlowRow(
        modifier = modifier
            .fillMaxWidth(0.85f)
            .padding(
                start = AuraSpacing.xl,
                end = AuraSpacing.md,
                top = AuraSpacing.xs,
                bottom = AuraSpacing.xxs,
            ),
        horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
    ) {
        for (s in suggestions) {
            Surface(
                color = androidx.compose.ui.graphics.Color.Transparent,
                shape = RoundedCornerShape(CHIP_HEIGHT / 2),
                border = BorderStroke(AuraSpacing.hairline, colors.borderDefault),
                modifier = Modifier.clickable { onPick(s) },
            ) {
                Box(
                    modifier = Modifier
                        .heightIn(min = CHIP_HEIGHT)
                        .padding(horizontal = AuraSpacing.sm),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = s,
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.textSecondary,
                        // One line, always. The height is what makes a row of
                        // these read as a set; a chip that grows breaks it.
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Tall enough to tap comfortably, short enough not to compete with the answer. */
private val CHIP_HEIGHT = 34.dp
