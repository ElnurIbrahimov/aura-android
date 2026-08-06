package com.aura.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.aura.ui.theme.AuraSpacing
import com.aura.ui.theme.AuraThemeTokens

/**
 * The app's overflow menu.
 *
 * Material's default DropdownMenu draws on its own `surfaceContainer`
 * colour with square-ish corners and 48dp rows, which on these screens
 * came out as a pale grey slab of widely-spaced items that matched
 * nothing else in the app. This wraps it with the app's own surface,
 * radius and border so every "⋮" opens the same object.
 */
@Composable
fun AuraDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = AuraThemeTokens.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = RoundedCornerShape(AuraSpacing.md),
        containerColor = colors.surface2,
        border = androidx.compose.foundation.BorderStroke(AuraSpacing.hairline, colors.borderSubtle),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
        content = content,
    )
}

/**
 * One row of an [AuraDropdownMenu].
 *
 * [destructive] tints both label and icon with the error colour — used
 * for actions that discard something, so they never read like the
 * ordinary items above them.
 */
@Composable
fun AuraDropdownItem(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    supporting: String? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = AuraThemeTokens.colors
    val tint = if (destructive) colors.error else colors.textPrimary
    DropdownMenuItem(
        text = {
            Text(
                text = if (supporting != null) "$label · $supporting" else label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) tint else colors.textTertiary,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) tint else colors.textTertiary,
                modifier = Modifier.size(AuraSpacing.lg),
            )
        },
        onClick = onClick,
        enabled = enabled,
        // Tighter than Material's default. Nine items at the stock height
        // filled most of the screen and read as a wall rather than a menu.
        contentPadding = PaddingValues(horizontal = AuraSpacing.md, vertical = AuraSpacing.xxs),
        modifier = Modifier.padding(horizontal = AuraSpacing.xxs),
    )
}
