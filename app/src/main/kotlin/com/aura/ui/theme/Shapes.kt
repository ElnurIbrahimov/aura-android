package com.aura.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Aura shapes — 1:1 with Aura Web's CSS radius scale.
 *   --radius-sm: 8
 *   --radius-md: 12
 *   --radius-lg: 24
 *   --radius-full: 9999
 */
val AuraShapes = Shapes(
    extraSmall = RoundedCornerShape(AuraSpacing.small),
    small = RoundedCornerShape(AuraSpacing.xs),
    medium = RoundedCornerShape(AuraSpacing.sm),
    large = RoundedCornerShape(AuraSpacing.md),
    extraLarge = RoundedCornerShape(AuraSpacing.lg),
)
