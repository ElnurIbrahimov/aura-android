package com.aura.proactive

import android.content.Intent
import com.aura.data.UserPreferences
import com.aura.evolution.EvolutionScheduler
import com.aura.mcp.McpClientManager
import com.aura.mcp.McpServerConfig
import com.aura.mcp.McpToolBridge
import com.aura.memory.MemoryStore
import com.aura.security.SecureDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Auto-starts the proactive layer when the app process starts. The
 * AuraApp Application class calls bootstrap() in onCreate.
 *
 * Each proactive worker is gated on a [UserPreferences] flag read
 * at bootstrap time. Default behavior is unchanged (both workers
 * are on for a fresh install) — the toggles are opt-out. When a
 * toggle flips to false, the matching cancel path runs so any
 * in-flight work is removed and the user-visible signal
 * (notification, foreground service) goes away.
 *
 * Also runs a one-shot memory decay pass on startup. This is what the
 * Python codebase used to do via a daily cron; the Kotlin port skipped
 * it and [MemoryStore.runDecayPass] was orphan code for the v1 cut.
 * The cost is small (a single Room query of up to 10k rows + a few
 * in-memory computations) and the benefit is real (a memory that's
 * been unused for 60 days actually gets its score nudged down).
 */
@Singleton
class ProactiveBootstrap @Inject constructor(
    @ApplicationContext private val appContext: android.content.Context,
    private val scheduler: ProactiveScheduler,
    private val memoryStore: MemoryStore,
    private val userPreferences: UserPreferences,
    private val evolutionScheduler: EvolutionScheduler,
    private val mcpClientManager: McpClientManager,
    private val mcpToolBridge: McpToolBridge,
    private val secureDataStore: SecureDataStore,
    private val agentStore: com.aura.agent.AgentStore,
    private val conversationStore: com.aura.agent.ConversationStore,
) {
    /**
     * Internal scope used to fire-and-forget the startup decay
     * pass and the async gate reads. Keeping it scoped to the
     * singleton (SupervisorJob on IO) means we don't leak a
     * coroutine when the process is about to die and we don't
     * block the main thread on the DataStore / Room reads.
     */
    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    private var preferenceJob: kotlinx.coroutines.Job? = null

    fun start() {
        // Seed builtin agents on first run.
        scope.launch { agentStore.seedBuiltins() }
        // Soft-delete sweep on app start: hard-purges tombstones older
        // than the retention window. Cheap (indexed), no UI impact, no
        // need for a separate Worker.
        scope.launch {
            runCatching { conversationStore.purgeDeletedOlderThan() }
        }
        // Keep one long-lived reconciliation collector.
        // their persisted defaults immediately and every Settings mutation
        // thereafter, so schedules and the foreground service converge in
        // the active process instead of waiting for a restart.
        if (preferenceJob?.isActive != true) {
            preferenceJob = scope.launch {
                combine(
                    userPreferences.morningBriefEnabled,
                    userPreferences.calendarMonitorEnabled,
                    userPreferences.morningBriefHour,
                    userPreferences.evolutionEnabled,
                    userPreferences.evolutionIntervalHours,
                ) { morningBriefOn, calendarMonitorOn, briefHour, evolutionOn, evolutionInterval ->
                    ProactiveGates(morningBriefOn, calendarMonitorOn, briefHour, evolutionOn, evolutionInterval)
                }
                    .distinctUntilChanged()
                    .collect { gates ->
                        reconcile(gates)
                        reconcileEvolution(gates.evolutionOn, gates.evolutionIntervalHours)
                    }
            }
        }

        // Daemon reconciliation — separate flow to avoid 6-way combine limit
        scope.launch {
            userPreferences.daemonEnabled.distinctUntilChanged().collect { daemonOn ->
                reconcileDaemon(daemonOn)
            }
        }

        // Startup decay remains one-shot.
        // merely changes a schedule toggle or time.
        scope.launch {
            runCatching { memoryStore.runDecayPass() }
                .onFailure { error ->
                    try {
                        android.util.Log.w(
                            "ProactiveBootstrap",
                            "startup decay pass failed: ${error.message}",
                        )
                    } catch (_: RuntimeException) {
                        // android.util.Log is unavailable in pure JVM tests.
                    }
                }
        }

        // Reconnect MCP servers and register their tools into the ToolRegistry
        // so the agentic loop can see and call them.
        scope.launch {
            runCatching { reconnectMcpServers() }
                .onFailure { error ->
                    try {
                        android.util.Log.w(
                            "ProactiveBootstrap",
                            "MCP reconnect failed: ${error.message}",
                        )
                    } catch (_: RuntimeException) {}
                }
        }
    }

    private fun reconcile(gates: ProactiveGates) {
        val decisions = applyGates(gates.morningBriefOn, gates.calendarMonitorOn, gates.briefHour)
        try {
            if (decisions.calendarMonitorShouldRun) {
                CalendarMonitorService.start(appContext)
            } else {
                appContext.stopService(
                    Intent(appContext, CalendarMonitorService::class.java),
                )
            }
            val refresh = Intent(ACTION_REFRESH_WIDGET).apply {
                setPackage(appContext.packageName)
            }
            appContext.sendBroadcast(refresh)
        } catch (_: Throwable) {
            // Scheduling is best-effort; the next DataStore emission or
            // process launch reconciles it again.
        }
    }

    /**
     * Start or stop the periodic evolution worker based on the
     * [evolutionEnabled] preference. When enabled, schedules the
     * worker at [evolutionIntervalHours]-hour intervals. When
     * disabled, cancels any pending work so no background API
     * calls happen.
     */
    private fun reconcileEvolution(evolutionOn: Boolean, evolutionIntervalHours: Int) {
        try {
            if (evolutionOn) {
                evolutionScheduler.schedule(evolutionIntervalHours.toLong())
            } else {
                evolutionScheduler.cancel()
            }
        } catch (_: Throwable) {
        }
    }

    private fun reconcileDaemon(daemonOn: Boolean) {
        try {
            if (daemonOn) {
                DaemonScheduler.schedule(appContext)
            } else {
                DaemonScheduler.cancel(appContext)
            }
        } catch (_: Throwable) {
        }
    }

    /**
     * Reconnect persisted MCP servers and register their tools into
     * the [com.aura.agent.ToolRegistry] via [McpToolBridge]. Called
     * at startup and whenever the server list changes.
     *
     * Servers are stored as a JSON array in UserPreferences. Auth
     * tokens are NOT persisted here (they live in SecureDataStore if
     * needed — currently MCP servers use URL-embedded credentials
     * or no auth).
     */
    suspend fun reconnectMcpServers() {
        val jsonStr = userPreferences.mcpServersJson.first()
        if (jsonStr.isBlank()) return

        val servers = try {
            val json = Json { ignoreUnknownKeys = true }
            val arr = json.parseToJsonElement(jsonStr) as? JsonArray ?: return
            arr.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val config = json.decodeFromJsonElement(McpServerConfig.serializer(), obj)
                // Re-inject auth token from SecureDataStore (not stored in plain JSON)
                val token = runCatching { secureDataStore.getString("mcp_auth_${config.id}") }.getOrNull()
                if (token.isNullOrBlank()) config else config.copy(authToken = token)
            }
        } catch (e: Exception) {
            try {
                android.util.Log.w("ProactiveBootstrap", "Failed to parse MCP servers JSON: ${e.message}")
            } catch (_: RuntimeException) {}
            return
        }

        for (config in servers) {
            if (!config.enabled) continue
            runCatching {
                mcpClientManager.connect(config, config.authToken)
            }
        }

        // Register all discovered MCP tools into the ToolRegistry
        mcpToolBridge.syncTools(servers)
    }

    /**
     * Apply the morning-brief + calendar-monitor gates. Pure-Kotlin,
     * no Context, no Android framework — this is the seam the unit
     * tests exercise.
     *
     * Morning brief is scheduled (or cancelled) via the scheduler.
     * Calendar monitor is a foreground service, so its gating is a
     * decision rather than a call: the actual start/stop lives in
     * [start] because it needs a real Context. Returning the gate
     * decision lets the caller dispatch the FGS side effect without
     * duplicating the boolean math.
     */
    internal fun applyGates(morningBriefOn: Boolean, calendarMonitorOn: Boolean, briefHour: Int = 7): GatedDecisions {
        if (morningBriefOn) {
            scheduler.scheduleMorningBrief(briefHour)
            scheduler.scheduleDecay()
        } else {
            scheduler.cancelMorningBrief()
            scheduler.cancelDecay()
        }
        return GatedDecisions(
            morningBriefScheduled = morningBriefOn,
            calendarMonitorShouldRun = calendarMonitorOn,
        )
    }

    data class GatedDecisions(
        val morningBriefScheduled: Boolean,
        val calendarMonitorShouldRun: Boolean,
    )

    /** Snapshot of all proactive + evolution preferences for reconciliation. */
    data class ProactiveGates(
        val morningBriefOn: Boolean,
        val calendarMonitorOn: Boolean,
        val briefHour: Int,
        val evolutionOn: Boolean,
        val evolutionIntervalHours: Int,
    )

    companion object {
        /** Custom broadcast action the [com.aura.widget.AskAuraWidget] listens for. */
        const val ACTION_REFRESH_WIDGET = "com.aura.action.REFRESH_WIDGET"
    }
}