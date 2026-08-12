package com.aura.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.aura.ui.theme.AuraSpacing
import com.aura.ui.theme.AuraThemeTokens

/**
 * Aura asking you something.
 *
 * It appears under the composer once Aura has finished answering, which is the
 * only moment where a question of its own is not an interruption: you got what
 * you came for, and now it wants something back.
 *
 * **Deliberately a card rather than a line in the model's reply.** Telling the
 * model to ask its open question produces a question we cannot recognise in the
 * next turn — so the answer could not be attributed, the question would never
 * close, and it would be asked again forever. The whole point of the feature is
 * that curiosity here is a mechanism rather than a mood, and a prompt
 * instruction would make it a mood.
 *
 * Collapsed until tapped, because a text field permanently parked above the
 * composer is a demand rather than an offer.
 */
@Composable
fun OpenQuestionCard(
    question: String,
    onAnswer: (String) -> Unit,
    onNotNow: () -> Unit,
    onNeverAsk: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(question) { mutableStateOf(false) }
    var reply by remember(question) { mutableStateOf("") }
    val colors = AuraThemeTokens.colors

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = AuraSpacing.md, vertical = AuraSpacing.xxs),
        color = colors.surface1,
        shape = MaterialTheme.shapes.medium,
        border = androidx.compose.foundation.BorderStroke(AuraSpacing.hairline, colors.borderSubtle),
        onClick = { if (!expanded) expanded = true },
    ) {
        Column(modifier = Modifier.padding(horizontal = AuraSpacing.md, vertical = AuraSpacing.sm)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Filled.HelpOutline,
                    contentDescription = null,
                    tint = colors.assistantAccent,
                    modifier = Modifier.size(AuraSpacing.md),
                )
                Spacer(modifier = Modifier.width(AuraSpacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Aura has a question",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textSecondary,
                    )
                    Text(
                        text = question,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                    )
                }
            }

            if (expanded) {
                OutlinedTextField(
                    value = reply,
                    onValueChange = { reply = it },
                    placeholder = { Text("Your answer") },
                    singleLine = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = AuraSpacing.xs),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        enabled = reply.isNotBlank(),
                        onClick = { onAnswer(reply) },
                    ) { Text("Answer") }
                    TextButton(onClick = onNotNow) { Text("Not now") }
                    // Permanent, and the reason dismissed rows are kept and
                    // backed up: this subject is never raised again.
                    TextButton(onClick = onNeverAsk) {
                        Text("Never ask", color = colors.textSecondary)
                    }
                }
            }
        }
    }
}
