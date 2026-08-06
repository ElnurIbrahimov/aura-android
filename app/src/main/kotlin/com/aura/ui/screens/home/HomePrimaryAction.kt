package com.aura.ui.screens.home

import com.aura.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.aura.ui.theme.AuraSpacing
import com.aura.ui.theme.AuraThemeTokens

private val quickPrompts = listOf(
    "Plan my day" to "Help me plan my day",
    "What's next?" to "What should I focus on next?",
    "Remind me" to "Create a reminder",
)

/**
 * The ask-Aura entry point.
 *
 * Previously a bordered Surface wrapping an OutlinedTextField wrapping a
 * row of outlined chips — three nested rectangles for one text input. The
 * repeated container is what made Home read as a form rather than a page.
 * Here the box is gone entirely: a single hairline under the field carries
 * the affordance, and the quick prompts are plain text. Separation comes
 * from spacing.
 */
@Composable
fun HomePrimaryAction(
    onAskAura: (String) -> Unit,
    label: String = "Ask Aura",
    modifier: Modifier = Modifier,
) {
    val colors = AuraThemeTokens.colors
    var draft by remember { mutableStateOf("") }

    fun send() {
        val prompt = draft.trim()
        if (prompt.isNotEmpty()) {
            onAskAura(prompt)
            draft = ""
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AuraSpacing.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = AuraSpacing.sm)
                        .testTag("home-ask-input"),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.merge(
                        MaterialTheme.typography.bodyLarge.copy(color = colors.textPrimary),
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.actionPrimary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                )
                if (draft.isEmpty()) {
                    Text(
                        text = stringResource(R.string.what_do_you_need),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.textTertiary,
                        modifier = Modifier.padding(vertical = AuraSpacing.sm),
                    )
                }
            }
            IconButton(
                onClick = { send() },
                enabled = draft.isNotBlank(),
                modifier = Modifier.testTag("home-ask-send"),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Ask Aura",
                    // The accent appears only once the button can actually
                    // do something — a live control, not a permanent slab.
                    tint = if (draft.isNotBlank()) colors.actionPrimary else colors.textTertiary,
                )
            }
        }
        HorizontalDivider(thickness = AuraSpacing.hairline, color = colors.borderDefault)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
        ) {
            for (prompt in quickPrompts) {
                TextButton(
                    onClick = { onAskAura(prompt.second) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = AuraSpacing.xs,
                        vertical = 0.dp,
                    ),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.textSecondary),
                ) {
                    Text(prompt.first, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
