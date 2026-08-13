package com.aura.health

import android.util.Log
import com.aura.data.UserPreferences
import com.aura.kg.KnowledgeGraphDao
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether Aura's background life is actually happening.
 *
 * Answers the question that has never been answerable: *if nothing seems to be
 * happening, is that because there is nothing to do, because something is
 * switched off, or because it is broken?* Those three produce an identical
 * app, and the difference between them is most of what a person needs to know.
 *
 * The switch list matters as much as the run log. Most of Aura's background
 * work no-ops on a missing precondition, and one preference in particular —
 * the background model — gates eight separate subsystems at once.
 */
@Singleton
class BackgroundHealth @Inject constructor(
    private val recorder: WorkerRunRecorder,
    private val userPreferences: UserPreferences,
    private val knowledgeGraphDao: KnowledgeGraphDao? = null,
) {

    data class Switch(val name: String, val on: Boolean, val note: String = "")

    data class Snapshot(
        val runs: List<WorkerRunEntity> = emptyList(),
        val switches: List<Switch> = emptyList(),
        /** Node and edge counts — the number that decides how fast Aura stays. */
        val graphNodes: Int = 0,
        val graphEdges: Int = 0,
    )

    suspend fun snapshot(): Snapshot = Snapshot(
        runs = recorder.latestPerWorker(),
        switches = switches(),
        graphNodes = count { knowledgeGraphDao?.nodeCount() },
        graphEdges = count { knowledgeGraphDao?.edgeCount() },
    )

    /** Everything the recent run history knows, newest first. */
    suspend fun history(limit: Int = 50): List<WorkerRunEntity> = recorder.recent(limit)

    private suspend fun switches(): List<Switch> = buildList {
        add(
            Switch(
                "Background model",
                on = !flag { userPreferences.backgroundModel.first() }.isNullOrBlank(),
                // The single highest-leverage setting in the app, and the one
                // whose absence is completely silent.
                note = "Needed by the daemon, dream questions, research, the " +
                    "morning brief and the memory write gate",
            ),
        )
        add(Switch("Dreams", bool { userPreferences.dreamEnabled.first() }, "Overnight, while charging"))
        add(Switch("Daemon", bool { userPreferences.daemonEnabled.first() }))
        add(Switch("Evolution", bool { userPreferences.evolutionEnabled.first() }))
        add(Switch("Memory decay", bool { userPreferences.decayEnabled.first() }))
        add(Switch("Calendar monitor", bool { userPreferences.calendarMonitorEnabled.first() }))
        add(Switch("Triggers", bool { userPreferences.triggersEnabled.first() }))
        add(Switch("Morning brief", bool { userPreferences.morningBriefEnabled.first() }))
        add(Switch("App awareness", bool { userPreferences.appAwarenessEnabled.first() }))
        add(
            Switch(
                "Place log",
                bool { userPreferences.placeLogEnabled.first() },
                // The only background switch that is off by default: it is the
                // only one that collects something new rather than reading what
                // Aura already has.
                "Coarse, ~100m, 90-day retention. Also needs location permission",
            ),
        )
    }

    private suspend fun bool(read: suspend () -> Boolean): Boolean =
        runCatching { read() }.onFailure { Log.w(TAG, "switch read failed", it) }.getOrDefault(false)

    private suspend fun flag(read: suspend () -> String?): String? =
        runCatching { read() }.onFailure { Log.w(TAG, "flag read failed", it) }.getOrNull()

    private suspend fun count(read: suspend () -> Int?): Int =
        runCatching { read() ?: 0 }.onFailure { Log.w(TAG, "count failed", it) }.getOrDefault(0)

    private companion object {
        const val TAG = "BackgroundHealth"
    }
}
