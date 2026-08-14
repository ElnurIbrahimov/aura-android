package com.aura.ui.components

import com.aura.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.provenance.ConversationProvenance
import com.aura.ui.viewmodel.MemoryCorrectionViewModel
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aura.agent.RecallSummary

import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.theme.AuraSpacing
/**
 * A small chip rendered below an assistant turn that summarizes
 * what Aura recalled from its long-term stores for that turn.
 *
 * Format: "Used 3 memories · 1 hand" or "No memories used for this
 * answer" when recall was performed but found nothing.
 *
 * When the consult pass ran, the chip reads "Recalled 3 · 1 applied"
 * instead. "Used" was always more than the chip could support — recall
 * put the memories in the prompt and nothing observed whether the model
 * read them — so where a verdict exists it is stated, and where none
 * exists the older, vaguer wording stands rather than inventing one.
 *
 * Tapping the chip opens a bottom sheet listing the recalled
 * memory/hand IDs. The chat UI wires the sheet to the actual
 * content via [recalledMemoryContents] and [recalledHandContents]
 * so the model layer can lazy-load from the store.
 *
 * Hidden when [recall] is null (incognito mode or any turn where
 * memoryEnabled was false).
 */
@Composable
fun MemoryRecallCaption(
    recall: RecallSummary,
    conversationId: String = "",
    turnTimestamp: Long = 0L,
    /** What the user asked. Scopes an "irrelevant here" correction. */
    queryText: String = "",
    modifier: Modifier = Modifier,
) {
    var sheetOpen by remember { mutableStateOf(false) }
    val colors = AuraThemeTokens.colors

    // Part of the footer caption, not a row of its own.
    //
    // This was a filled Surface with a semibold label, on its own line, under a
    // row of filled suggestion chips, under a separate line holding the
    // timestamp — three stacked strips of chrome beneath every answer, two of
    // which were the same kind of thing. Provenance and the timestamp are both
    // metadata and now share one line; the suggestions are actions and sit
    // above it, attached to the message they follow.
    //
    // The dot carries the only signal worth raising: it takes the accent colour
    // when the consult pass actually applied something, and stays inert
    // otherwise, so the line earns a glance on the turns where something
    // happened and costs nothing on the rest.
    val applied = recall.consultedIds?.isNotEmpty() == true
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.clickable { sheetOpen = true },
    ) {
        Box(
            modifier = Modifier
                .size(AuraSpacing.small)
                .background(
                    color = if (applied) colors.actionPrimary else colors.textTertiary,
                    shape = CircleShape,
                ),
        )
        Spacer(modifier = Modifier.width(AuraSpacing.xxs))
        Text(
            text = recall.summary(),
            fontFamily = com.aura.ui.theme.InterDisplay,
            fontSize = 10.sp,
            color = colors.textTertiary,
        )
    }
    if (sheetOpen) {
        MemoryRecallSheet(
            recall = recall,
            provenance = ConversationProvenance(conversationId, turnTimestamp),
            queryText = queryText,
            onDismiss = { sheetOpen = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoryRecallSheet(
    recall: RecallSummary,
    provenance: ConversationProvenance,
    queryText: String,
    onDismiss: () -> Unit,
    viewModel: MemoryCorrectionViewModel = hiltViewModel(),
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(recall) { viewModel.load(recall.memoryIds, recall.handIds) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // Single LazyColumn - nesting scrollable LazyColumns inside a Column
        // of unbounded height (the bottom sheet) throws IllegalStateException
        // at measure time. All sections are items of one scroll container.
        LazyColumn(modifier = Modifier.padding(AuraSpacing.xxl2)) {
            item {
                Text(
                    text = recall.summary(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (state.items.isNotEmpty()) {
                    Text(
                        text = "Tap anything Aura got wrong.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AuraThemeTokens.colors.textSecondary,
                    )
                }
                Spacer(modifier = Modifier.height(AuraSpacing.sm))
            }
            state.report?.let { report ->
                item {
                    Surface(
                        color = AuraThemeTokens.colors.surface2,
                        shape = RoundedCornerShape(AuraSpacing.sm),
                        modifier = Modifier.fillMaxWidth().padding(bottom = AuraSpacing.sm),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(AuraSpacing.sm),
                        ) {
                            Text(
                                text = report,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            if (state.lastCorrectionId != null) {
                                TextButton(onClick = { viewModel.undo() }) { Text("Undo") }
                            }
                        }
                    }
                }
            }
            if (!state.loading && state.items.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.aura_looked_at_its_memories_for),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AuraThemeTokens.colors.textPrimary,
                    )
                }
            }
            items(state.items, key = { it.id }) { item ->
                CorrectableRow(
                    item = item,
                    // Null means no consult ran, which must not read as "this
                    // one didn't apply" — absence of a verdict is not a verdict.
                    consulted = recall.consultedIds?.contains(item.id) == true,
                    onNeverTrue = { viewModel.neverTrue(item.id, provenance) },
                    onNoLongerTrue = { viewModel.noLongerTrue(item.id, it, provenance) },
                    onIrrelevant = { viewModel.irrelevantHere(item.id, queryText, provenance) },
                    onBadAnswer = { viewModel.badAnswer(item.id, provenance) },
                    canScope = queryText.isNotBlank(),
                )
            }
            item { Spacer(modifier = Modifier.height(AuraSpacing.xxl2)) }
        }
    }
}

/**
 * One thing Aura used, and the ways it can be wrong.
 *
 * The choices are four sentences rather than a rating, because the distinction
 * between them is the entire feature: a mistake, a fact that expired, a fact
 * that surfaced in the wrong place, and a bad answer are four different
 * corrections with four different effects, and a thumbs-down cannot tell them
 * apart. The wording avoids naming the mechanism - the user is saying what is
 * true, not choosing a retraction policy.
 */
@Composable
private fun CorrectableRow(
    item: MemoryCorrectionViewModel.RecalledItem,
    onNeverTrue: () -> Unit,
    onNoLongerTrue: (String) -> Unit,
    onIrrelevant: () -> Unit,
    onBadAnswer: () -> Unit,
    canScope: Boolean,
    /** The consult pass judged this one to bear on the question. */
    consulted: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    var replacement by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    val corrected = item.correctionId != null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !corrected) { expanded = !expanded }
            .padding(vertical = AuraSpacing.small),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = if (item.isSkill) Icons.Filled.QuestionMark else Icons.Filled.Memory,
                contentDescription = null,
                tint = AuraThemeTokens.colors.textPrimary,
                modifier = Modifier.size(AuraSpacing.md).padding(top = AuraSpacing.tiny),
            )
            Spacer(modifier = Modifier.width(AuraSpacing.sm))
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        // Struck through in place, so the correction reads as
                        // something that happened to this line rather than as a
                        // message about it.
                        textDecoration = if (corrected) TextDecoration.LineThrough else null,
                        color = if (corrected) {
                            AuraThemeTokens.colors.textSecondary
                        } else {
                            AuraThemeTokens.colors.textPrimary
                        },
                        modifier = Modifier.weight(1f),
                    )
                    // Marks the ones the consult pass actually acted on. A word
                    // rather than a badge: this is the seam being shown, not a
                    // status worth a coloured dot, and every recalled row here
                    // is already something Aura had.
                    if (consulted) {
                        Text(
                            text = "applied",
                            style = MaterialTheme.typography.labelSmall,
                            color = AuraThemeTokens.colors.actionPrimary,
                            modifier = Modifier.padding(start = AuraSpacing.xs, top = AuraSpacing.tiny),
                        )
                    }
                }
                if (item.detail.isNotBlank()) {
                    Text(
                        text = item.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = AuraThemeTokens.colors.textSecondary,
                    )
                }
            }
        }
        if (expanded && !corrected) {
            Column(modifier = Modifier.padding(start = AuraSpacing.xl, top = AuraSpacing.xxs)) {
                if (item.isSkill) {
                    TextButton(onClick = onBadAnswer) { Text("This gave a bad answer") }
                } else if (editing) {
                    OutlinedTextField(
                        value = replacement,
                        onValueChange = { replacement = it },
                        label = { Text("What is true now?") },
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row {
                        TextButton(
                            enabled = replacement.isNotBlank(),
                            onClick = {
                                onNoLongerTrue(replacement)
                                editing = false
                            },
                        ) { Text("Save") }
                        TextButton(onClick = { editing = false }) { Text("Cancel") }
                    }
                } else {
                    TextButton(onClick = onNeverTrue) { Text("This was never true") }
                    TextButton(onClick = { editing = true }) { Text("This has changed") }
                    if (canScope) {
                        TextButton(onClick = onIrrelevant) { Text("Not relevant to what I asked") }
                    }
                }
            }
        }
    }
}

private fun RecallSummary.summary(): String {
    val m = memoryIds.size
    val h = handIds.size
    val hands = if (h == 0) "" else " · $h hand${if (h == 1) "" else "s"}"

    // When the consult pass ran, the honest verb is available and "Used" is
    // retired for this turn. "Used" was always a claim the chip could not
    // support: recall put the memories in the prompt and nothing observed
    // whether the model read them. Where a standing instruction was recalled,
    // something now has.
    consultedIds?.let { consulted ->
        val applied = if (consulted.isEmpty()) "none applied" else "${consulted.size} applied"
        return "Recalled $m · $applied$hands"
    }

    return when {
        m == 0 && h == 0 -> "No memories used for this answer"
        h == 0 -> "Used $m memor${if (m == 1) "y" else "ies"}"
        m == 0 -> "Triggered $h hand${if (h == 1) "" else "s"}"
        else -> "Used $m memor${if (m == 1) "y" else "ies"}$hands"
    }
}
