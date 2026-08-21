package com.aura.ui.screens.game

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aura.R
import com.aura.ui.components.AuraLoadingState
import com.aura.ui.components.AuraScreenShell
import com.aura.ui.theme.AuraSpacing
import com.aura.ui.theme.AuraThemeTokens
import com.aura.ui.viewmodel.HoldingUi
import com.aura.ui.viewmodel.MoveUi
import com.aura.ui.viewmodel.PlayUiState
import com.aura.ui.viewmodel.PlayViewModel
import com.aura.ui.viewmodel.PresenceUi

/**
 * A seat inside a living world.
 *
 * The living-world section of the creative studio is a *spectator* surface: it
 * reads `WorldState` and renders truth, which is right for watching and fatal
 * for playing. This screen renders a `PlayerView` instead, so the numbers it
 * shows about anyone but your own house are what your house *believes*.
 *
 * Nothing here marks a believed number as believed. That is not an omission —
 * a badge saying "you may be out of date about this" would hand the player the
 * one fact the fog exists to withhold, and every greyed-out button would become
 * a free reading of ground truth. Which is also why the disabled moves below
 * carry reasons drawn only from things the player legitimately knows: where
 * they are standing, what they hold, who is in the room.
 */
@Composable
fun PlayScreen(onBack: () -> Unit, viewModel: PlayViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    AuraScreenShell(
        title = state.dayLabel.ifBlank { stringResource(R.string.play_title) },
        subtitle = subtitleFor(state),
        action = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.play_back))
            }
        },
    ) { padding ->
        when {
            state.loading -> AuraLoadingState()
            state.missing -> PlayCard(stringResource(R.string.play_gone)) {
                Text(stringResource(R.string.play_gone_detail), style = MaterialTheme.typography.bodySmall)
            }

            !state.seated -> LazyColumn(
                contentPadding = padding,
                verticalArrangement = Arrangement.spacedBy(AuraSpacing.md),
            ) {
                item(key = "seat") { SeatCard(state, viewModel) }
            }

            else -> LazyColumn(
                contentPadding = padding,
                verticalArrangement = Arrangement.spacedBy(AuraSpacing.md),
            ) {
                item(key = "house") { HouseCard(state, viewModel) }
                item(key = "here") { RoomCard(stringResource(R.string.play_here), state.here, state.placeName) }
                item(key = "moves") { MovesCard(state, viewModel) }
                if (state.elsewhere.isNotEmpty()) {
                    item(key = "elsewhere") {
                        RoomCard(stringResource(R.string.play_elsewhere), state.elsewhere, "")
                    }
                }
                if (state.log.isNotEmpty()) {
                    item(key = "log") { LogCard(state.log) }
                }
            }
        }
    }
}

@Composable
private fun subtitleFor(state: PlayUiState): String = when {
    state.missing || state.loading -> stringResource(R.string.play_subtitle_default)
    !state.seated -> stringResource(R.string.play_subtitle_unseated)
    state.placeName.isNotBlank() -> stringResource(R.string.play_subtitle_at, state.youName, state.placeName)
    else -> stringResource(R.string.play_subtitle_nowhere, state.youName)
}

@Composable
private fun SeatCard(state: PlayUiState, viewModel: PlayViewModel) {
    val title = if (state.choosingHouse) {
        stringResource(R.string.play_seat_house)
    } else {
        stringResource(R.string.play_seat_character)
    }
    PlayCard(title) {
        if (state.note.isNotBlank()) {
            Text(
                state.note,
                style = MaterialTheme.typography.bodySmall,
                color = AuraThemeTokens.colors.textSecondary,
            )
        }
        if (state.choices.isEmpty()) {
            Text(stringResource(R.string.play_seat_nobody), style = MaterialTheme.typography.bodySmall)
            return@PlayCard
        }
        Text(
            if (state.choosingHouse) {
                stringResource(R.string.play_seat_house_detail)
            } else {
                stringResource(R.string.play_seat_character_detail)
            },
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textSecondary,
        )
        for (choice in state.choices) {
            OutlinedButton(
                onClick = { viewModel.choose(choice.id) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(choice.name)
            }
        }
    }
}

@Composable
private fun HouseCard(state: PlayUiState, viewModel: PlayViewModel) {
    PlayCard(state.houseName.ifBlank { stringResource(R.string.play_title) }) {
        Text(stringResource(R.string.play_house_detail), style = MaterialTheme.typography.bodySmall, color = AuraThemeTokens.colors.textSecondary)
        Holdings(state.holdings)
        if (state.behindDays > 0L) {
            HorizontalDivider(color = AuraThemeTokens.colors.borderSubtle)
            Text(
                stringResource(R.string.play_behind, state.behindDays),
                style = MaterialTheme.typography.bodySmall,
                color = AuraThemeTokens.colors.textSecondary,
            )
        }
        if (state.note.isNotBlank()) {
            Text(state.note, style = MaterialTheme.typography.bodySmall, color = AuraThemeTokens.colors.textSecondary)
        }
        HorizontalDivider(color = AuraThemeTokens.colors.borderSubtle)
        LeaveRow(enabled = !state.busy, onLeave = viewModel::leaveSeat)
    }
}

/**
 * Stepping out, behind one confirmation.
 *
 * Inline rather than a dialog: an `AlertDialog` here would be the heavier
 * thing for a choice that is not destructive — the world survives leaving it,
 * and you can sit back down. The confirmation exists only because losing your
 * seat to a mis-tap in the middle of a session is a genuinely annoying way to
 * be interrupted.
 */
@Composable
private fun LeaveRow(enabled: Boolean, onLeave: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    if (!confirming) {
        TextButton(onClick = { confirming = true }, enabled = enabled) {
            Text(stringResource(R.string.play_leave))
        }
        return
    }
    Text(
        stringResource(R.string.play_leave_confirm),
        style = MaterialTheme.typography.bodySmall,
        color = AuraThemeTokens.colors.textSecondary,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(AuraSpacing.sm)) {
        TextButton(onClick = { confirming = false }) {
            Text(stringResource(R.string.play_leave_stay))
        }
        TextButton(
            onClick = {
                confirming = false
                onLeave()
            },
            enabled = enabled,
        ) {
            Text(stringResource(R.string.play_leave_go))
        }
    }
}

/**
 * Who is present, and what they are thought to hold.
 *
 * "Thought to" is doing real work and is deliberately not said anywhere on
 * screen. These numbers came out of the belief table and can be wrong by any
 * amount; a world where they happen to be right renders identically.
 */
@Composable
private fun RoomCard(title: String, people: List<PresenceUi>, placeName: String) {
    PlayCard(if (placeName.isBlank()) title else "$title — $placeName") {
        if (people.isEmpty()) {
            Text(stringResource(R.string.play_room_empty), style = MaterialTheme.typography.bodySmall, color = AuraThemeTokens.colors.textSecondary)
            return@PlayCard
        }
        for (person in people) {
            Text(person.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Holdings(person.holdings)
        }
    }
}

@Composable
private fun Holdings(holdings: List<HoldingUi>) {
    if (holdings.isEmpty()) {
        Text(stringResource(R.string.play_holds_nothing), style = MaterialTheme.typography.bodySmall, color = AuraThemeTokens.colors.textSecondary)
        return
    }
    for (holding in holdings) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(holding.key, style = MaterialTheme.typography.bodySmall, color = AuraThemeTokens.colors.textSecondary)
            Text(holding.amount, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MovesCard(state: PlayUiState, viewModel: PlayViewModel) {
    PlayCard(stringResource(R.string.play_moves)) {
        for (move in state.moves) {
            MoveRow(move, enabled = !state.busy, onPlay = { viewModel.play(move.id) })
        }
        HorizontalDivider(color = AuraThemeTokens.colors.borderSubtle)
        Text(
            stringResource(R.string.play_wait_detail),
            style = MaterialTheme.typography.bodySmall,
            color = AuraThemeTokens.colors.textSecondary,
        )
        OutlinedButton(
            onClick = { viewModel.waitADay() },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.play_wait))
        }
    }
}

@Composable
private fun MoveRow(move: MoveUi, enabled: Boolean, onPlay: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(AuraSpacing.xxs)) {
        Button(
            onClick = onPlay,
            enabled = enabled && move.legal,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(move.label)
        }
        val note = move.blockedBy.ifBlank { move.detail }
        if (note.isNotBlank()) {
            Text(note, style = MaterialTheme.typography.bodySmall, color = AuraThemeTokens.colors.textSecondary)
        }
    }
}

/** A plain loop: the log is short and bounded, and a nested scroller would fight the page. */
@Composable
private fun LogCard(log: List<String>) {
    PlayCard(stringResource(R.string.play_log)) {
        for (line in log) {
            Text(line, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PlayCard(title: String, content: @Composable ColumnScope.() -> Unit) {
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
