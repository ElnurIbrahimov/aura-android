package com.aura.ui.screens.hands

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aura.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.hands.record.RecordedStep
import com.aura.ui.components.AuraCard
import com.aura.ui.components.AuraEmptyState
import com.aura.ui.components.AuraScreenShell
import com.aura.ui.theme.AuraSpacing
import com.aura.ui.theme.AuraThemeTokens

/**
 * Where a demonstration becomes a Hand.
 *
 * The screen exists because the capture cannot be trusted on its own. Diffing two screens
 * rarely proves which element was tapped — tapping "Send" usually leaves "Send" exactly where
 * it was — so most recordings arrive with questions, and this is where they are answered.
 * A step with an unanswered question refuses to compile, so nothing can be saved by ignoring
 * the prompts.
 */
@Composable
fun RecordedHandReviewScreen(
    onBack: () -> Unit = {},
    onSaved: () -> Unit = {},
    viewModel: RecordedHandViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = AuraThemeTokens.colors

    if (state.saved) onSaved()

    AuraScreenShell(
        title = "Record a hand",
        subtitle = if (state.recording) {
            "Recording. Go and do the thing, then come back and stop."
        } else {
            "Check what Aura saw, then save it"
        },
        action = { TextButton(onClick = onBack) { Text(stringResource(R.string.record_close)) } },
    ) { padding: PaddingValues ->
        Column(
            modifier = Modifier.fillMaxWidth().padding(padding),
            verticalArrangement = Arrangement.spacedBy(AuraSpacing.sm),
        ) {
            if (state.recording) {
                AuraCard {
                    Text(
                        if (state.boundPackage.isBlank()) {
                            "Waiting for you to open an app."
                        } else {
                            "Recording ${state.boundPackage} — ${state.liveStepCount} steps so far."
                        },
                        color = colors.textPrimary,
                    )
                    TextButton(onClick = viewModel::stopRecording) { Text(stringResource(R.string.record_stop)) }
                }
                return@Column
            }

            if (!state.reviewing) {
                AuraEmptyState(
                    title = "Show Aura once",
                    message = "Start recording, do the task in the other app, then come back. " +
                        "Aura writes down what it can and asks about the rest.",
                    actionLabel = "Start recording",
                    onAction = viewModel::startRecording,
                )
                return@Column
            }

            OutlinedTextField(
                value = state.draft.name,
                onValueChange = viewModel::setName,
                singleLine = true,
                label = { Text(stringResource(R.string.record_name_hand)) },
                modifier = Modifier.fillMaxWidth(),
            )

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
            ) {
                items(state.draft.steps.size, key = { it }) { index ->
                    val step = state.draft.steps[index]
                    AuraCard {
                        Text("${index + 1}. ${describe(step)}", color = colors.textPrimary)

                        // The ambiguous case, which is the common one rather than the edge.
                        if (step.ambiguous) {
                            Text(
                                "Which one did you tap?",
                                color = colors.textSecondary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs)) {
                                step.candidates.forEach { candidate ->
                                    AssistChip(
                                        onClick = { viewModel.resolve(index, candidate) },
                                        label = { Text(candidate.text ?: candidate.contentDescription ?: "unnamed") },
                                    )
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs)) {
                            if (step.kind == RecordedStep.Kind.TYPE && step.text?.startsWith("{{") != true) {
                                TextButton(onClick = { viewModel.makeVariable(index, "text$index") }) {
                                    Text(stringResource(R.string.record_ask_each_time))
                                }
                            }
                            TextButton(onClick = { viewModel.remove(index) }) { Text(stringResource(R.string.record_remove_step)) }
                        }
                    }
                }
            }

            TextButton(onClick = viewModel::save, enabled = state.draft.canSave) { Text(stringResource(R.string.record_save_hand)) }
        }
    }
}

private fun describe(step: RecordedStep): String = when (step.kind) {
    RecordedStep.Kind.TAP -> "Tap ${step.label.ifBlank { "something" }}"
    RecordedStep.Kind.TYPE -> "Type ${step.text.orEmpty()}"
    RecordedStep.Kind.SCROLL -> "Scroll ${step.direction?.name?.lowercase() ?: ""}"
    RecordedStep.Kind.BACK -> "Go back"
}
