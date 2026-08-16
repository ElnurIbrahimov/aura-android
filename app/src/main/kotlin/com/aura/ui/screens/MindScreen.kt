package com.aura.ui.screens

import com.aura.ui.components.AuraCard
import com.aura.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aura.memory.CorrectionEntity
import com.aura.ui.components.AuraScreenShell
import com.aura.ui.evolution.beliefsSection
import com.aura.ui.theme.AuraSpacing
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.viewmodel.MindViewModel
import com.aura.ui.viewmodel.TasteProfileViewModel
import com.aura.ui.viewmodel.WorldModelViewModel

/**
 * One place that answers "what do you currently think about me, and why".
 *
 * Several destinations answered some part of that question and two answered the
 * same part: `BeliefsScreen` and `WorldModelScreen` both listed beliefs with
 * the same Verify and Retire actions. Nobody navigates that many screens on a
 * phone to assemble a picture of what an assistant believes about them, so the
 * picture never got assembled.
 *
 * The beliefs here come from `BeliefsViewModel`, not `WorldModelViewModel`.
 * They looked interchangeable and are not: that one also loads the supporting
 * evidence and the superseded chain — "previously: X" — so the shorter file was
 * the better one.
 *
 * Two things stay separate on purpose. The knowledge graph is an interactive
 * visualisation with its own search and node inspection; folding it into a
 * section would make both worse. Consolidation keeps its own screen because
 * accepting graph proposals and pruning routines is maintenance, not
 * reflection — what belongs here is the summary of what happened, and a way
 * through to the rest.
 *
 * One `LazyColumn`. Every section is a `LazyListScope` extension for that
 * reason: nesting scrollables throws at measure time.
 */
@Composable
fun MindScreen(
    onBack: () -> Unit = {},
    onOpenKnowledgeGraph: () -> Unit = {},
    onOpenConsolidation: () -> Unit = {},
    onProactiveAction: (com.aura.proactive.ProactiveAction) -> Unit = {},
    beliefsViewModel: com.aura.ui.evolution.BeliefsViewModel = hiltViewModel(),
    worldModelViewModel: WorldModelViewModel = viewModel(),
    tasteViewModel: TasteProfileViewModel = viewModel(),
    mindViewModel: MindViewModel = hiltViewModel(),
) {
    AuraScreenShell(
        title = "What Aura thinks",
        subtitle = "Beliefs, taste, corrections and open questions",
        action = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(AuraSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AuraSpacing.sm),
        ) {
            // Each section renders nothing when it has nothing, and the screen
            // as a whole says so once rather than four times.
            // First, deliberately. Everything below is present tense — what Aura
            // believes now, what it wants to ask now — and the one question none
            // of it answered was what actually moved.
            changesSection(mindViewModel)
            beliefsSection(beliefsViewModel)
            worldModelSection(
                worldModelViewModel,
                onProactiveAction,
                showEmptyState = false,
                includeBeliefs = false,
            )
            openQuestionsSection(mindViewModel)
            correctionsSection(mindViewModel)
            tasteSection(tasteViewModel, showEmptyState = false)
            consolidationSection(mindViewModel, onOpenConsolidation)
            item {
                AuraCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = AuraSpacing.md),
                ) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.knowledge_graph)) },
                        supportingContent = { Text(stringResource(R.string.everything_aura_has_connected_as_a)) },
                        modifier = Modifier.clickable(onClick = onOpenKnowledgeGraph),
                    )
                }
            }
        }
    }
}

/**
 * What changed in the last week.
 *
 * A read across stores that were all already keeping timestamps — corrections,
 * consolidations, beliefs, contradictions, world events — and which nothing had
 * ever read together. No new table, no model call, no write.
 */
private fun LazyListScope.changesSection(viewModel: MindViewModel) {
    item {
        val changes by viewModel.changes.collectAsStateWithLifecycle()
        if (changes.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(AuraSpacing.xs)) {
                SectionHeading("Since last week", top = false)
                changes.forEach { change ->
                    ListItem(
                        overlineContent = { Text(changeLabel(change.kind)) },
                        headlineContent = { Text(change.headline) },
                        supportingContent = if (change.detail.isNotBlank()) {
                            { Text(change.detail, maxLines = 2) }
                        } else {
                            null
                        },
                        trailingContent = { Text(relativeAge(change.at)) },
                    )
                }
            }
        }
    }
}

private fun changeLabel(kind: com.aura.changelog.Change.Kind): String = when (kind) {
    com.aura.changelog.Change.Kind.CORRECTION -> "You corrected it"
    com.aura.changelog.Change.Kind.CONSOLIDATION -> "Worked out overnight"
    com.aura.changelog.Change.Kind.BELIEF -> "Belief"
    com.aura.changelog.Change.Kind.CONTRADICTION -> "Contradiction"
    com.aura.changelog.Change.Kind.WORLD_EVENT -> "Noticed"
}

private fun relativeAge(at: Long): String {
    val hours = (System.currentTimeMillis() - at) / (60L * 60 * 1000)
    return when {
        hours < 1 -> "now"
        hours < 24 -> "${hours}h"
        else -> "${hours / 24}d"
    }
}

/** What Aura wants to ask. Read-only here; answering happens in chat. */
private fun LazyListScope.openQuestionsSection(viewModel: MindViewModel) {
    item {
        val questions by viewModel.questions.collectAsStateWithLifecycle()
        if (questions.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(AuraSpacing.xs)) {
                SectionHeading("Wants to ask you")
                for (question in questions) {
                    AuraCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            Text(question.question, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "It'll ask next time you're in chat",
                                style = MaterialTheme.typography.labelSmall,
                                color = AuraThemeTokens.colors.textSecondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Every time the user has said Aura was wrong.
 *
 * Nothing has ever shown these. A retraction's only visible effect was a memory
 * quietly ceasing to appear, which is indistinguishable from Aura simply not
 * recalling it — so the one feature that depends on the user trusting it worked
 * gave them no way to check.
 */
private fun LazyListScope.correctionsSection(viewModel: MindViewModel) {
    item {
        val corrections by viewModel.corrections.collectAsStateWithLifecycle()
        if (corrections.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(AuraSpacing.xs)) {
                SectionHeading("Corrections you've made (${corrections.size})")
                for (correction in corrections) {
                    AuraCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    correctionLabel(correction.kind),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (correction.queryText.isNotBlank()) {
                                    Text(
                                        "for questions like \"${correction.queryText}\"",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AuraThemeTokens.colors.textSecondary,
                                        textDecoration = TextDecoration.None,
                                    )
                                }
                            }
                            TextButton(onClick = { viewModel.undoCorrection(correction.id) }) { Text(stringResource(R.string.undo)) }
                        }
                    }
                }
            }
        }
    }
}

/** The nightly work, summarised. The full detail keeps its own screen. */
private fun LazyListScope.consolidationSection(viewModel: MindViewModel, onOpenAll: () -> Unit) {
    item {
        val summaries by viewModel.summaries.collectAsStateWithLifecycle()
        Column(verticalArrangement = Arrangement.spacedBy(AuraSpacing.xs)) {
            SectionHeading("What it worked out overnight")
            if (summaries.isEmpty()) {
                Text(
                    "Nothing consolidated yet. Aura does this overnight while charging.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textSecondary,
                )
            } else {
                for (summary in summaries) {
                    AuraCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            summary.compressedText,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                TextButton(onClick = onOpenAll) { Text(stringResource(R.string.see_all_and_pending_proposals)) }
            }
        }
    }
}

private fun correctionLabel(kind: String): String = when (kind) {
    CorrectionEntity.NEVER_TRUE -> "Retracted — never true"
    CorrectionEntity.NO_LONGER_TRUE -> "Updated — no longer true"
    CorrectionEntity.IRRELEVANT_HERE -> "Demoted — not relevant there"
    CorrectionEntity.BAD_ANSWER -> "Reported a bad answer"
    else -> kind
}
