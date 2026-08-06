package com.aura.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import com.aura.ui.theme.AuraSpacing
import com.aura.ui.theme.AuraThemeTokens

/**
 * A text field drawn as a single hairline with the placeholder carrying
 * the label — the input style used across Home and the app's dialogs.
 *
 * Material's OutlinedTextField draws a full box plus a floating accent
 * label. One is fine; four stacked down a dialog make the form heavier
 * than anything it sits on top of, and the accent labels put brand colour
 * on every row of an ordinary form.
 *
 * @param minLines 1 keeps the field single-line; higher values allow
 *   multi-line entry and raise the ceiling to [maxLines].
 */
@Composable
fun AuraUnderlinedField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
    maxLines: Int = if (minLines > 1) 12 else 1,
) {
    val colors = AuraThemeTokens.colors
    Column(modifier = modifier) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.actionPrimary),
            minLines = minLines,
            maxLines = maxLines,
            singleLine = minLines == 1,
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.textTertiary,
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = AuraSpacing.xs),
        )
        HorizontalDivider(thickness = AuraSpacing.hairline, color = colors.borderDefault)
    }
}
