package com.aura.ui.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.creative.livingworld.ActorEffect
import com.aura.creative.livingworld.LivingWorldEntity
import com.aura.creative.livingworld.LivingWorldRunner
import com.aura.creative.livingworld.LivingWorldStore
import com.aura.creative.livingworld.PlayerMoves
import com.aura.creative.livingworld.PlayerView
import com.aura.creative.livingworld.SimEntity
import com.aura.creative.livingworld.WorldClock
import com.aura.creative.livingworld.WorldState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One holding, as a line of text. Already believed rather than true. */
@Immutable
data class HoldingUi(val key: String, val amount: String)

/** Somebody the seat can see, and what they are thought to have. */
@Immutable
data class PresenceUi(val id: String, val name: String, val kind: String, val holdings: List<HoldingUi>)

/**
 * One offered move.
 *
 * [id] rather than a list index, because the state behind the screen is
 * republished on every step and an index would quietly point at a different
 * move than the one that was tapped.
 */
@Immutable
data class MoveUi(
    val id: String,
    val verb: String,
    val label: String,
    val detail: String,
    val blockedBy: String,
) {
    val legal: Boolean get() = blockedBy.isEmpty()
}

/** One option while taking a seat. */
@Immutable
data class SeatChoiceUi(val id: String, val name: String)

@Immutable
data class PlayUiState(
    val loading: Boolean = true,
    val missing: Boolean = false,
    val busy: Boolean = false,
    val dayLabel: String = "",
    val behindDays: Long = 0L,
    /** Where your character stands. Blank when nowhere in particular. */
    val placeName: String = "",
    val seated: Boolean = false,
    val youName: String = "",
    val houseName: String = "",
    /** Your own holdings, which read true. */
    val holdings: List<HoldingUi> = emptyList(),
    /** Believed, not true — and deliberately carrying no mark saying so. */
    val here: List<PresenceUi> = emptyList(),
    val elsewhere: List<PresenceUi> = emptyList(),
    val moves: List<MoveUi> = emptyList(),
    val log: List<String> = emptyList(),
    /** Non-empty only while unseated: characters first, then houses. */
    val choices: List<SeatChoiceUi> = emptyList(),
    val choosingHouse: Boolean = false,
    val note: String = "",
)

/**
 * The seat's screen state.
 *
 * Everything the player is shown comes through [PlayerView] and everything they
 * can do comes through [PlayerMoves], which is not tidiness — it is the whole
 * guarantee. The view is the only thing here that has ever touched
 * [WorldState], and it hands over believed numbers with nothing attached
 * saying which are wrong.
 *
 * So this class never sees ground truth for anyone but the player's own side,
 * and could not leak it if it wanted to.
 */
@HiltViewModel
class PlayViewModel @Inject constructor(
    private val store: LivingWorldStore,
    private val runner: LivingWorldRunner,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val worldId: String = savedStateHandle.get<String>("worldId").orEmpty()

    private val _state = MutableStateFlow(PlayUiState())
    val state: StateFlow<PlayUiState> = _state.asStateFlow()

    /** The moves currently on offer, so a tap can be resolved back to an effect. */
    private var offered: List<PlayerMoves.Move> = emptyList()

    /** Half a seat: the character chosen while the house is still being picked. */
    private var pendingCharacterId: String = ""

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val world = runCatching { store.byId(worldId) }.getOrNull()
            if (world == null) {
                _state.value = PlayUiState(loading = false, missing = true)
                return@launch
            }
            val worldState = runCatching { store.decode(world.stateJson) }.getOrNull()
            if (worldState == null) {
                _state.value = PlayUiState(loading = false, missing = true)
                return@launch
            }
            _state.value = project(world, worldState, note = _state.value.note)
        }
    }

    /** Take a seat, in two steps: who you are, then what you command. */
    fun choose(id: String) {
        if (!_state.value.choosingHouse) {
            pendingCharacterId = id
            viewModelScope.launch {
                val world = runCatching { store.byId(worldId) }.getOrNull() ?: return@launch
                val worldState = runCatching { store.decode(world.stateJson) }.getOrNull() ?: return@launch
                _state.value = _state.value.copy(
                    choosingHouse = true,
                    choices = worldState.living().filter { it.kind == KIND_FACTION }
                        .map { SeatChoiceUi(it.id, it.name) },
                )
            }
            return
        }
        viewModelScope.launch {
            runCatching { store.seat(worldId, pendingCharacterId, id, System.currentTimeMillis()) }
            pendingCharacterId = ""
            _state.value = _state.value.copy(choosingHouse = false)
            refresh()
        }
    }

    /**
     * Submit a move into the next tick, and advance it.
     *
     * An unknown id is dropped rather than guessed at: the only way to get one
     * is a tap that raced a refresh, and acting on a stale offer would spend a
     * tick on something the player did not choose.
     */
    fun play(moveId: String) {
        val move = offered.firstOrNull { idOf(it) == moveId } ?: return
        val effect = move.effect ?: return
        step(listOf(effect))
    }

    /**
     * Let a day pass without doing anything.
     *
     * The world runs at one tick per real hour, which is right for something
     * ambient and impossible to evaluate an evening of. This is the deliberate
     * half: the tick is burned, so the world really is a day further along and
     * ambient time carries on from there rather than pausing.
     */
    fun waitADay() = step(emptyList())

    private fun step(actions: List<ActorEffect>) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, note = "")
        viewModelScope.launch {
            runCatching { runner.step(worldId, actions) }
                .onFailure { _state.value = _state.value.copy(busy = false, note = "The day would not turn.") }
                .onSuccess { _state.value = _state.value.copy(busy = false) }
            refresh()
        }
    }

    private suspend fun project(world: LivingWorldEntity, worldState: WorldState, note: String): PlayUiState {
        val now = System.currentTimeMillis()
        val log = runCatching { store.recentEvents(world.id, LOG_LINES) }.getOrDefault(emptyList())
            .map { it.narration.ifBlank { it.summary } }
            .filter { it.isNotBlank() }

        val base = PlayUiState(
            loading = false,
            dayLabel = WorldClock.label(world.currentTick),
            behindDays = WorldClock.behind(world.currentTick, world.worldEpochMs, now, world.sessionTicksBurned),
            log = log,
            note = note,
        )

        if (world.playerCharacterId.isBlank() || world.playerFactionId.isBlank()) {
            offered = emptyList()
            val characters = worldState.living().filter { it.kind == KIND_CHARACTER }
            return base.copy(
                seated = false,
                choosingHouse = false,
                choices = characters.map { SeatChoiceUi(it.id, it.name) },
            )
        }

        val view = PlayerView.of(worldState, world.playerCharacterId, world.playerFactionId)
        offered = PlayerMoves.available(view)

        val places = worldState.living().filter { it.kind == KIND_LOCATION }.associateBy(SimEntity::id)
        val visible = view.others.filter { it.kind != KIND_LOCATION }
        return base.copy(
            seated = view.self != null,
            youName = view.self?.name.orEmpty(),
            houseName = view.faction?.name.orEmpty(),
            placeName = places[view.locationId]?.name.orEmpty(),
            holdings = view.faction?.stocks.orEmpty().map { HoldingUi(it.key, milli(it.amountMilli)) },
            here = visible.filter { it.canObserve }.map(::presence),
            elsewhere = visible.filterNot { it.canObserve }.map(::presence),
            moves = offered.map {
                MoveUi(
                    id = idOf(it),
                    verb = it.verb,
                    label = it.label,
                    detail = it.detail,
                    blockedBy = it.blockedBy,
                )
            },
        )
    }

    private fun presence(entity: PlayerView.SeenEntity) = PresenceUi(
        id = entity.id,
        name = entity.name,
        kind = entity.kind,
        holdings = entity.stocks.map { HoldingUi(it.key, milli(it.amountMilli)) },
    )

    private fun idOf(move: PlayerMoves.Move) = move.verb + "|" + move.label

    private fun milli(value: Long): String {
        val whole = value / 1_000L
        val fraction = (value % 1_000L) / 100L
        return if (fraction == 0L) whole.toString() else "$whole.$fraction"
    }

    private companion object {
        const val KIND_CHARACTER = "character"
        const val KIND_FACTION = "faction"
        const val KIND_LOCATION = "location"
        const val LOG_LINES = 12
    }
}
