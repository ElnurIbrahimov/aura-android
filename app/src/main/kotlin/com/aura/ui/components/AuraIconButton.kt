@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.aura.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.aura.ui.theme.AuraDimensions
import com.aura.ui.theme.AuraThemeTokens

/**
 * A 48dp semantic hit target containing a compact 40dp visual control.
 *
 * When [contentDescription] is non-null, it is applied to the outer
 * Box so TalkBack announces the button's purpose. The Icon inside
 * should NOT set its own contentDescription when this is used (double
 * announcement). If [contentDescription] is null, the content lambda
 * is responsible for providing accessibility info.
 */
@Composable
fun AuraIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = Color.Transparent,
    contentDescription: String? = null,
    /**
     * Container shape. Defaults to the rounded square used across
     * toolbars; pass [androidx.compose.foundation.shape.CircleShape] when
     * the button sits inside a pill, where a rounded square's corners
     * collide with the surrounding curve.
     */
    shape: Shape = RoundedCornerShape(AuraDimensions.controlRadius),
    /**
     * Optional secondary action on a long press.
     *
     * Null by default, so a button that does not want one keeps `clickable`'s ripple and
     * accessibility handling rather than `combinedClickable`'s.
     */
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = AuraThemeTokens.colors
    val baseModifier = if (contentDescription != null) {
        modifier.semantics { this.contentDescription = contentDescription }
    } else {
        modifier
    }
    Box(
        modifier = baseModifier
            .size(AuraDimensions.minimumTouchTarget)
            .then(
                if (onLongClick == null) {
                    Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                } else {
                    Modifier.combinedClickable(
                        enabled = enabled,
                        role = Role.Button,
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(AuraDimensions.iconButtonVisualSize),
            shape = shape,
            // A button drawn with no container while enabled should not
            // grow a filled grey slab the moment it is disabled — that
            // invents a control where the design had only an icon.
            color = when {
                enabled -> containerColor
                containerColor == Color.Transparent -> Color.Transparent
                else -> colors.actionDisabled
            },
        ) {
            Box(contentAlignment = Alignment.Center) { content() }
        }
    }
}
