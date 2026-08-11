package com.aura.creative.livingworld

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** What a world is doing right now, for a screen that happens to be open. */
data class LiveTick(
    val worldId: String,
    val currentTick: Long,
    val targetTick: Long,
    /** `catching_up` or `narrating`. */
    val phase: String,
) {
    val remaining: Long get() = (targetTick - currentTick).coerceAtLeast(0L)
}

/**
 * The in-memory half of the Living tab's state — "what is happening" beside
 * Room's "what happened". Same division as
 * [com.aura.creative.longform.LongformProgressBus], and a `@Singleton` for the
 * same reason: the worker's Hilt graph and the ViewModel's are different
 * graphs, and one shared instance is the whole trick.
 *
 * **Keyed by world, unlike the long-form bus**, which holds a single global
 * slot and disambiguates at the consumer. There that limitation is theoretical;
 * here it is routine — a user with three projects has three worlds, the
 * periodic ticker walks all of them in one slice, and a single slot would mean
 * each overwrote the last while every open screen watched the wrong one.
 *
 * Deliberately not persisted. A hard kill loses the live *view* and never the
 * *work*, because every committed tick is already a row.
 */
@Singleton
class WorldTickBus @Inject constructor() {

    private val _live = MutableStateFlow<Map<String, LiveTick>>(emptyMap())

    fun live(worldId: String): Flow<LiveTick?> =
        _live.map { it[worldId] }.distinctUntilChanged()

    fun begin(worldId: String, currentTick: Long, targetTick: Long, phase: String = PHASE_CATCHING_UP) {
        _live.update { current ->
            // Bounded: a `begin` whose `clear` never arrives — the process was
            // killed mid-slice — must not accumulate forever.
            val trimmed = if (current.size >= MAX_TRACKED && !current.containsKey(worldId)) {
                current - current.keys.first()
            } else {
                current
            }
            trimmed + (worldId to LiveTick(worldId, currentTick, targetTick, phase))
        }
    }

    fun progress(worldId: String, currentTick: Long, phase: String = PHASE_CATCHING_UP) {
        _live.update { current ->
            val existing = current[worldId] ?: return@update current
            current + (worldId to existing.copy(currentTick = currentTick, phase = phase))
        }
    }

    fun clear(worldId: String) {
        _live.update { it - worldId }
    }

    companion object {
        const val PHASE_CATCHING_UP = "catching_up"
        const val PHASE_NARRATING = "narrating"
        private const val MAX_TRACKED = 8
    }
}
