package com.aura.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.aura.ui.theme.AuraDimensions
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.theme.AuraSpacing

/**
 * The app's card. Use this rather than Material's `Card`.
 *
 * Material3's `Card` defaults to `surfaceContainerLow` — a light flat grey —
 * which sits wrong on Aura's near-black ground and reads as borrowed UI. This
 * one takes [AuraThemeTokens], so a card looks like part of the app without
 * every call site having to remember the tokens.
 *
 * It existed with **zero callers** until 2026-08-17 while eighteen sites drew a
 * bare `Card {}` and got the grey. `AuraCardIsUsedTest` now fails the build if a
 * new one appears, because a shared component nothing calls is just a file.
 */
@Composable
fun AuraCard(
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
    containerColor: Color = Color.Unspecified,
    contentPadding: PaddingValues = PaddingValues(AuraDimensions.cardPadding),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = AuraThemeTokens.colors
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(AuraDimensions.cardRadius),
        color = if (containerColor == Color.Unspecified) {
            if (elevated) colors.surface2 else colors.surface1
        } else {
            containerColor
        },
        border = BorderStroke(AuraSpacing.hairline, colors.borderSubtle),
        tonalElevation = if (elevated) AuraSpacing.tiny else 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content,
        )
    }
}
