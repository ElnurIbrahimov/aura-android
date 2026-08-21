package com.aura.ui.screens.creative

import com.aura.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.aura.creative.livingworld.WorldClock
import com.aura.creative.livingworld.WorldEngine
import com.aura.creative.livingworld.WorldSetup
import com.aura.ui.theme.AuraSpacing
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.viewmodel.CreativeStudioUiState
import com.aura.ui.viewmodel.CreativeStudioViewModel
import com.aura.ui.viewmodel.LivingEventUi
import com.aura.ui.viewmodel.LivingFactionUi
import com.aura.ui.viewmodel.LivingLiveUi
import com.aura.ui.viewmodel.WorldBranchUi

/**
 * The Living tab: a world that runs whether or not anyone is looking at it.
 *
 * A `LazyListScope` extension for the same reason the manuscript is one — a
 * year of world history is thousands of rows, and inside a single `item { }`
 * Compose would measure all of them every frame.
 *
 * Nothing here polls. The world row and its events are Room-backed flows, so a
 * tick committed by a worker while this screen was closed is simply present
 * when it opens.
 */
internal fun LazyListScope.livingWorldSection(
    state: CreativeStudioUiState,
    viewModel: CreativeStudioViewModel,
) {
    val project = state.selectedProject ?: return
    val world = state.livingWorld

    if (world == null) {
        item(key = "living-start") { StartWorldCard(state, viewModel) }
        return
    }

    item(key = "living-branches") {
        BranchRow(world.branches, world.branchName, viewModel)
    }

    if (world.divergence.isNotEmpty()) {
        item(key = "living-diff") {
            LivingCard(title = stringResource(R.string.timelines_compared)) {
                world.divergence.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = AuraThemeTokens.colors.textSecondary,
                    )
                }
            }
        }
    }

    if (world.sinceYouLeft.isNotEmpty()) {
        item(key = "living-since") {
            LivingCard(title = stringResource(R.string.since_you_left)) {
                Text(
                    stringResource(R.string.since_you_left_summary, world.daysAway, world.sinceYouLeft.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textSecondary,
                )
                world.sinceYouLeft.forEach { event ->
                    Text(
                        "• " + (event.narration.ifBlank { event.summary }),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    item(key = "living-now") {
        WorldNowCard(
            world.currentTick,
            world.worldEpochMs,
            world.sessionTicksBurned,
            world.live,
            project.world.storyCursorTick,
            viewModel,
        )
    }

    item(key = "living-factions") {
        LivingCard(title = "Who holds what") {
            if (world.factions.isEmpty()) {
                Text(
                    "No factions are left standing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textSecondary,
                )
            } else {
                world.factions.forEachIndexed { index, faction ->
                    if (index > 0) HorizontalDivider(color = AuraThemeTokens.colors.borderSubtle)
                    FactionRow(faction)
                }
            }
        }
    }

    if (world.events.isEmpty()) {
        item(key = "living-empty") {
            LivingCard(title = "Nothing has happened yet") {
                Text(
                    "The world advances one day every hour. Come back later, or catch it up now.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textSecondary,
                )
            }
        }
        return
    }

    item(key = "living-history-header") {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = AuraSpacing.xxl2),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "History · ${world.eventCount} events",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            FilterChip(
                selected = world.showNotable,
                onClick = viewModel::toggleNotableMoments,
                label = { Text(stringResource(R.string.biggest_moments)) },
            )
        }
    }

    items(
        if (world.showNotable) world.notableMoments else world.events,
        key = { it.id },
    ) { event ->
        EventRow(event, world.narrating == event.id, world.hasGenesis, viewModel)
    }
}

/**
 * Every timeline as a chip, the fork button beside them, and — off the root —
 * the comparison ask. A fork is named by its author: the name seasons the
 * salt, so the same name at the same moment recreates the same world.
 */
@Composable
private fun BranchRow(
    branches: List<WorldBranchUi>,
    currentName: String,
    viewModel: CreativeStudioViewModel,
) {
    var forkDialog by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = AuraSpacing.xxl2),
        horizontalArrangement = Arrangement.spacedBy(AuraSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(AuraSpacing.sm),
        ) {
            branches.forEach { branch ->
                FilterChip(
                    selected = branch.selected,
                    onClick = { viewModel.selectWorldBranch(branch.branchId) },
                    label = { Text(branch.name) },
                )
            }
        }
        if (currentName != "main") {
            TextButton(onClick = viewModel::compareWithRoot) {
                Text(stringResource(R.string.compare_with_main))
            }
        }
        TextButton(onClick = { forkDialog = true }) {
            Text(stringResource(R.string.fork_timeline))
        }
    }
    if (forkDialog) {
        ForkTimelineDialog(
            onConfirm = { name ->
                forkDialog = false
                viewModel.forkLivingWorldNow(name)
            },
            onDismiss = { forkDialog = false },
        )
    }
}

@Composable
private fun ForkTimelineDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fork_timeline)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.fork_name_hint)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.fork_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_edit)) }
        },
    )
}

@Composable
private fun WorldNowCard(
    currentTick: Long,
    worldEpochMs: Long,
    sessionTicksBurned: Long,
    live: LivingLiveUi?,
    storyCursorTick: Long,
    viewModel: CreativeStudioViewModel,
) {
    // Read the clock at composition rather than taking a precomputed value from
    // state. How far behind the world is changes with time passing, not with
    // anything writing to the database, so a stored figure would be stale the
    // moment it was written.
    val now = System.currentTimeMillis()
    val behind = WorldClock.behind(currentTick, worldEpochMs, now, sessionTicksBurned = sessionTicksBurned)
    val nextInMs = WorldClock.msUntilNextTick(currentTick, worldEpochMs, now, sessionTicksBurned = sessionTicksBurned)

    LivingCard(title = WorldClock.label(currentTick)) {
        Text(
            when {
                behind > 1L -> "$behind days have passed that the world has not caught up to yet."
                behind == 1L -> "A day has passed that the world has not caught up to yet."
                else -> "Next day in ${formatDuration(nextInMs)}."
            },
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textSecondary,
        )
        if (live != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(AuraSpacing.md), strokeWidth = AuraSpacing.hairline * 2)
                Text(
                    if (live.phase == "narrating") {
                        "Writing up what happened…"
                    } else {
                        "Catching up · ${live.remaining} day(s) to go"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textSecondary,
                    modifier = Modifier.padding(start = AuraSpacing.sm),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            OutlinedButton(onClick = viewModel::catchUpLivingWorld, enabled = live == null) {
                Text(if (behind > 0L) "Catch up now" else "Advance now")
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when {
                    storyCursorTick < 0L -> stringResource(R.string.story_unpinned)
                    storyCursorTick == currentTick -> stringResource(R.string.story_pinned_here)
                    else -> stringResource(R.string.story_pin_stale)
                },
                style = MaterialTheme.typography.bodySmall,
                color = AuraThemeTokens.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = viewModel::pinStoryCursor,
                enabled = storyCursorTick != currentTick,
            ) {
                Text(stringResource(R.string.pin_story_here))
            }
        }
    }
}

@Composable
private fun StartWorldCard(state: CreativeStudioUiState, viewModel: CreativeStudioViewModel) {
    val project = state.selectedProject
    val factionCount = project?.world?.factions?.size ?: 0

    LivingCard(title = "Start this world") {
        Text(
            "The factions, places and rivalries in your world bible become a simulation that runs on its own — " +
                "one world day every hour, whether the app is open or not.",
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textSecondary,
        )

        if (factionCount < 2) {
            Text(
                "Add at least two factions in the World tab first. A world needs someone to disagree.",
                style = MaterialTheme.typography.bodySmall,
                color = AuraThemeTokens.colors.error,
            )
            return@LivingCard
        }

        // The world bible carries no numbers at all — no troops, no treasuries,
        // no capacities. Rather than invent them behind the author's back, ask
        // for the three that decide whether the world feels tense or sleepy.
        Text(
            "Your world bible has no quantities in it, so pick the starting numbers. " +
                "Land is finite and shared: one faction only gains it by taking it from another.",
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textSecondary,
        )

        var land by remember { mutableStateOf("120") }
        var stores by remember { mutableStateOf("50") }
        var drain by remember { mutableStateOf("0.3") }

        NumberField(land, { land = it }, "Total land, shared between all factions")
        NumberField(stores, { stores = it }, "Starting food stores, each")
        NumberField(drain, { drain = it }, "Food eaten per day, each")

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = {
                    viewModel.startLivingWorld(
                        WorldSetup(
                            territoryTotalMilli = toMilli(land, fallback = 120_000L),
                            startingGrainMilli = toMilli(stores, fallback = 50_000L),
                            grainCapacityMilli = toMilli(stores, fallback = 50_000L) * 2,
                            grainFlowPerTickMilli = -toMilli(drain, fallback = 300L),
                        ),
                    )
                },
            ) { Text(stringResource(R.string.start_the_world)) }
        }
    }
}

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun FactionRow(faction: LivingFactionUi) {
    Column(
        Modifier.padding(vertical = AuraSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                faction.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${units(faction.territoryMilli)} land",
                style = MaterialTheme.typography.bodySmall,
                color = AuraThemeTokens.colors.textSecondary,
                textAlign = TextAlign.End,
            )
        }
        Text(
            "food ${units(faction.grainMilli)} · coin ${units(faction.coinMilli)} · might ${units(faction.mightMilli)}" +
                if (faction.resents.isNotBlank()) " · resents ${faction.resents}" else "",
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textSecondary,
        )
    }
}

@Composable
private fun EventRow(
    event: LivingEventUi,
    narrating: Boolean,
    hasGenesis: Boolean,
    viewModel: CreativeStudioViewModel,
) {
    var forkHere by remember { mutableStateOf(false) }
    if (forkHere) {
        ForkTimelineDialog(
            onConfirm = { name ->
                forkHere = false
                viewModel.forkLivingWorldAt(event.tick, name)
            },
            onDismiss = { forkHere = false },
        )
    }
    Surface(
        color = AuraThemeTokens.colors.surface1,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(AuraSpacing.hairline, AuraThemeTokens.colors.borderSubtle),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AuraSpacing.xxl2),
    ) {
        Column(
            Modifier.padding(AuraSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AuraSpacing.xs),
        ) {
            Text(
                WorldClock.label(event.tick),
                style = MaterialTheme.typography.labelSmall,
                color = AuraThemeTokens.colors.textSecondary,
            )
            Text(
                event.summary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (
                    event.kind == WorldEngine.KIND_CLAIM_WON ||
                    event.kind == WorldEngine.KIND_BELIEF_REVEAL
                ) FontWeight.SemiBold else FontWeight.Normal,
            )
            if (event.narration.isNotBlank()) {
                Text(
                    event.narration,
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraThemeTokens.colors.textSecondary,
                )
            } else if (narrating) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(AuraSpacing.md), strokeWidth = AuraSpacing.hairline * 2)
                    Text(
                        "Writing…",
                        style = MaterialTheme.typography.bodySmall,
                        color = AuraThemeTokens.colors.textSecondary,
                        modifier = Modifier.padding(start = AuraSpacing.sm),
                    )
                }
            } else if (event.kind != WorldEngine.KIND_QUIET_INTERVAL) {
                // On demand rather than up front. Narrating a year of history
                // eagerly would cost a fortune to produce text nobody asked to
                // read; one press is the cheapest possible way to make an old
                // moment readable.
                TextButton(
                    onClick = { viewModel.narrateEvent(event.id) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) { Text(stringResource(R.string.tell_me_about_this)) }
            }
            if (hasGenesis && event.kind != WorldEngine.KIND_QUIET_INTERVAL) {
                TextButton(
                    onClick = { forkHere = true },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) { Text(stringResource(R.string.fork_from_here)) }
            }
        }
    }
}

@Composable
private fun LivingCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = AuraThemeTokens.colors.surface1,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(AuraSpacing.hairline, AuraThemeTokens.colors.borderSubtle),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AuraSpacing.xxl2),
    ) {
        Column(
            Modifier.padding(AuraSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AuraSpacing.sm),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

/** Milliunits are the engine's currency; people are not expected to read them. */
private fun units(milli: Long): String = "%.1f".format(milli / 1000.0)

private fun toMilli(text: String, fallback: Long): Long {
    val parsed = text.trim().toDoubleOrNull() ?: return fallback
    val milli = (parsed * 1000.0).toLong()
    return if (milli > 0L) milli else fallback
}

private fun formatDuration(ms: Long): String {
    val minutes = ms / 60_000L
    return when {
        minutes >= 60L -> "${minutes / 60}h ${minutes % 60}m"
        minutes >= 1L -> "${minutes}m"
        else -> "under a minute"
    }
}
