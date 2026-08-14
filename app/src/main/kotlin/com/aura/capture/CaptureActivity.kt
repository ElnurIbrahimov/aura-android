package com.aura.capture

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.MainActivity
import com.aura.ui.theme.AuraSpacing
import com.aura.ui.theme.AuraTheme
import com.aura.ui.theme.AuraThemeTokens
import dagger.hilt.android.AndroidEntryPoint

/**
 * Write a thought into Aura without opening Aura.
 *
 * Reached from three places, none of which require the app to be running: the
 * text-selection toolbar in any app (`ACTION_PROCESS_TEXT`), a quick-settings
 * tile, and a launcher long-press shortcut. It also receives shared text, which
 * previously ended as a draft in a composer and was lost if you backed out.
 *
 * ## Two modes, decided by whether text arrived
 *
 * **Text already chosen** — from a selection, a share, or the tile's clipboard
 * hand-off — is written *immediately*, before anything is drawn. The user
 * already made the decision; asking them to confirm it is the friction this
 * exists to remove. Undo covers the mistake case, and is cheap because
 * [CaptureViewModel] knows exactly which row it wrote.
 *
 * **No text** — the tile and shortcut opened cold — shows a field with the
 * keyboard already up.
 *
 * ## Deliberately absent
 *
 * No model, no network, no `ChatViewModel`, no verified-model precondition, no
 * spinner. This activity cannot fail for a reason outside the device. "Ask
 * about this" hands off to the chat only if the user asks for it, so asking
 * stops being the price of remembering.
 */
@AndroidEntryPoint
class CaptureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDimAmount(0.5f)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)

        val incoming = extractText(intent)

        setContent {
            AuraTheme {
                CaptureSheet(
                    incoming = incoming,
                    onAsk = { text ->
                        startActivity(
                            Intent(this, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                putExtra("openChat", true)
                                putExtra("chatPrefillDraft", text)
                            },
                        )
                        finish()
                    },
                    onDone = ::finish,
                )
            }
        }
    }

    /**
     * The text this launch carried, if any.
     *
     * `ACTION_PROCESS_TEXT` is the selection toolbar. `ACTION_SEND` is the share
     * sheet — the subject is folded in because a shared article is a title plus
     * a link and the title is the part worth remembering.
     */
    private fun extractText(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_PROCESS_TEXT ->
                intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()

            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
                when {
                    subject.isNotBlank() && text.isNotBlank() -> "$subject\n\n$text"
                    else -> text.ifBlank { subject }
                }
            }

            else -> intent.getStringExtra(EXTRA_TEXT)
        }?.trim()?.takeIf { it.isNotEmpty() }
    }

    companion object {
        /** For callers inside the app — the tile hands text over this way. */
        const val EXTRA_TEXT = "com.aura.CAPTURE_TEXT"
    }
}

@Composable
private fun CaptureSheet(
    incoming: String?,
    onAsk: (String) -> Unit,
    onDone: () -> Unit,
    viewModel: CaptureViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf(incoming.orEmpty()) }
    val focus = remember { FocusRequester() }

    // Written before the first frame settles when the text was already chosen.
    // remember(incoming) rather than LaunchedEffect(Unit) so a recreation does
    // not write the same thought twice — storeIfAbsent would dedup it, but that
    // would report Duplicate and hide Undo for a row this launch did create.
    var submitted by remember(incoming) { mutableStateOf(false) }
    LaunchedEffect(incoming) {
        if (!incoming.isNullOrBlank() && !submitted) {
            submitted = true
            viewModel.capture(incoming)
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(AuraSpacing.md),
        shape = MaterialTheme.shapes.large,
        tonalElevation = AuraSpacing.xs,
    ) {
        Column(
            modifier = Modifier.padding(AuraSpacing.xxl2),
            verticalArrangement = Arrangement.spacedBy(AuraSpacing.sm),
        ) {
            when (val s = state) {
                is CaptureViewModel.State.Composing -> {
                    Text("Capture a thought", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        placeholder = { Text("Aura will remember this") },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { viewModel.capture(draft) }),
                    )
                    // Straight to the keyboard. A capture surface that needs a
                    // tap before you can type has given the friction back.
                    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs, Alignment.End),
                    ) {
                        TextButton(onClick = onDone) { Text("Cancel") }
                        Button(
                            onClick = { viewModel.capture(draft) },
                            enabled = draft.isNotBlank(),
                        ) { Text("Save") }
                    }
                }

                is CaptureViewModel.State.Saved -> Confirmation(
                    headline = "Saved",
                    body = s.text,
                    onUndo = viewModel::undo,
                    onAsk = { onAsk(s.text) },
                    onDone = onDone,
                )

                is CaptureViewModel.State.Duplicate -> Confirmation(
                    // Not an error, and not a new row either — so no Undo, which
                    // would delete something this capture did not write.
                    headline = "Already remembered",
                    body = s.text,
                    onUndo = null,
                    onAsk = { onAsk(s.text) },
                    onDone = onDone,
                )

                is CaptureViewModel.State.Failed -> {
                    Text("Couldn't save that", style = MaterialTheme.typography.titleMedium)
                    Text(
                        s.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = AuraThemeTokens.colors.textSecondary,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs, Alignment.End),
                    ) {
                        TextButton(onClick = onDone) { Text("Close") }
                        Button(onClick = { viewModel.capture(draft) }) { Text("Try again") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Confirmation(
    headline: String,
    body: String,
    onUndo: (() -> Unit)?,
    onAsk: () -> Unit,
    onDone: () -> Unit,
) {
    Text(headline, style = MaterialTheme.typography.titleMedium)
    Text(
        body,
        style = MaterialTheme.typography.bodyMedium,
        color = AuraThemeTokens.colors.textSecondary,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AuraSpacing.xs, Alignment.End),
    ) {
        if (onUndo != null) {
            TextButton(onClick = onUndo) { Text("Undo") }
        }
        TextButton(onClick = onAsk) { Text("Ask about this") }
        Button(onClick = onDone) { Text("Done") }
    }
}
