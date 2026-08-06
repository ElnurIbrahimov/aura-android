package com.aura.ui.components
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.theme.AuraSpacing

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDeleteContainer(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        },
    )
    LaunchedEffect(state.currentValue) {
        if (state.currentValue == SwipeToDismissBoxValue.EndToStart) {
            state.reset()
        }
    }
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        backgroundContent = {
            val alpha by animateFloatAsState(
                targetValue = if (state.dismissDirection != null) 1f else 0f,
                label = "swipe-bg",
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Clipped to the same radius as the row in front of it.
                    // As a square rect it showed through the row's rounded
                    // corners as four red slivers on every item, even at
                    // rest — the list looked permanently mid-swipe.
                    .clip(RoundedCornerShape(AuraSpacing.medium))
                    .background(AuraThemeTokens.colors.error.copy(alpha = alpha * 0.5f))
                    .padding(horizontal = AuraSpacing.lg),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = AuraThemeTokens.colors.error,
                )
            }
        },
        content = { content() },
    )
}