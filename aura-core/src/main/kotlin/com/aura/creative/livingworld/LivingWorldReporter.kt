package com.aura.creative.livingworld

import android.util.Log
import com.aura.proactive.ProactiveEventBus
import com.aura.proactive.ProactiveEvents
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Narrates what the tick produced and tells the user something happened.
 *
 * Kept out of both the runner (which must stay free of the network) and the
 * worker (which must stay a thin shell), so the decision of *when the world
 * speaks* has somewhere to live that a test can drive.
 *
 * Delivery goes through [ProactiveEvents.record] and nothing else. The bus's
 * `emit` looks equivalent and is not: it has `replay = 0`, so an emission from
 * a background worker with no live collector is dropped and never persisted.
 * `DaemonWorker` does exactly that at five sites and those insights are simply
 * gone. `record` inserts to Room first and re-emits afterwards.
 */
@Singleton
class LivingWorldReporter @Inject constructor(
    private val store: LivingWorldStore,
    private val narrator: WorldNarrator,
    private val tickBus: WorldTickBus? = null,
    private val proactiveEvents: ProactiveEvents? = null,
) {

    /**
     * @return how many worlds produced something worth reporting.
     */
    suspend fun reportAll(now: Long = System.currentTimeMillis()): Int {
        val worlds = runCatching { store.running() }
            .onFailure { Log.w(TAG, "could not list worlds to report on: ${it.message}", it) }
            .getOrDefault(emptyList())

        var reported = 0
        for (world in worlds) {
            if (report(world, now)) reported++
        }
        return reported
    }

    suspend fun report(world: LivingWorldEntity, now: Long = System.currentTimeMillis()): Boolean {
        tickBus?.progress(world.id, world.currentTick, WorldTickBus.PHASE_NARRATING)
        val narrated = runCatching { narrator.narratePending(world, now) }
            .onFailure { Log.w(TAG, "narration failed for ${world.id}: ${it.message}", it) }
            .getOrDefault(0)
        tickBus?.clear(world.id)
        if (narrated <= 0) return false

        // The report quotes the world's own prose rather than restating it.
        // A notification that says "3 things happened" and nothing else asks
        // the user to go and find out, which is a worse offer than silence.
        val latest = runCatching { store.recentEvents(world.id, RECENT_FOR_REPORT) }
            .onFailure { Log.w(TAG, "report body read failed: ${it.message}", it) }
            .getOrDefault(emptyList())
            .filter { it.narratedAt >= now - RECENT_WINDOW_MS && it.narration.isNotBlank() }

        val body = latest.firstOrNull()?.narration?.take(MAX_BODY_CHARS)
            ?: latest.firstOrNull()?.summary
            ?: return false

        val projectWorld = store.decode(world.stateJson)
        val title = titleFor(projectWorld, narrated)

        runCatching {
            proactiveEvents?.record(
                ProactiveEventBus.Event.LivingWorldReport(
                    worldId = world.id,
                    title = title,
                    body = body,
                    timestamp = now,
                ),
            )
        }.onFailure { Log.w(TAG, "recording world report failed: ${it.message}", it) }
        return true
    }

    private fun titleFor(state: WorldState, narrated: Int): String {
        val powers = state.entities.count { it.kind == WorldSeeder.KIND_FACTION && it.diedAtTick == 0L }
        return when {
            narrated == 1 -> "Something happened in your world"
            powers > 0 -> "$narrated things happened among $powers powers"
            else -> "$narrated things happened in your world"
        }
    }

    companion object {
        private const val TAG = "LivingWorldReporter"
        private const val RECENT_FOR_REPORT = 8
        private const val RECENT_WINDOW_MS = 60 * 60 * 1000L
        private const val MAX_BODY_CHARS = 400
    }
}
