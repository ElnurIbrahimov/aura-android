package com.aura.proactive

import android.util.Log
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
 * (notification, scheduled worker) goes away.
 *
 * Also runs a one-shot memory decay pass on startup. This is what the
 * Python codebase used to do via a daily cron; the Kotlin port skipped
 * it and [MemoryStore.runDecayPass] was orphan code for the v1 cut.
 * The cost is small (a single Room query of up to 10k rows + a few
 * in-memory computations) and the benefit is real (a memory that's
 * been unused for 60 days actually gets its score nudged down).
 */
private const val TAG = "ProactiveBootstrap"

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
    private val emotionEngine: com.aura.emotion.EmotionEngine? = null,
    private val narrativeSelf: com.aura.consciousness.NarrativeSelf? = null,
    private val agentStore: com.aura.agent.AgentStore,
    private val conversationStore: com.aura.agent.ConversationStore,
    private val integrationTokenStore: com.aura.integrations.IntegrationTokenStore? = null,
    // Appended, not inserted: ProactiveBootstrapTest constructs this class
    // positionally with every argument, so a parameter added mid-list is a
    // compile break for reasons unrelated to what the test checks.
    private val intrinsicMotivation: com.aura.consciousness.IntrinsicMotivation? = null,
    private val theoryOfMind: com.aura.consciousness.TheoryOfMind? = null,
    /**
     * A [javax.inject.Provider], not the manager itself. `BackupManager` pulls
     * in roughly fifty DAOs across eleven databases; injecting it directly here
     * would build that entire graph on every cold start so that a file that is
     * normally absent could be stat-ed. The Provider defers construction to the
     * one launch in a thousand where the marker actually exists.
     */
    private val backupManager: javax.inject.Provider<com.aura.backup.BackupManager>? = null,
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

    /**
     * @param scope the scope every bootstrap coroutine is launched in.
     *   Production callers use the default (SupervisorJob on IO); unit
     *   tests pass a `TestScope`/`backgroundScope` so the reconciliation
     *   flows run on virtual time instead of real IO threads (the old
     *   tests polled with `Thread.sleep`, which was flaky on cold CI
     *   runners).
     */
    fun start(scope: kotlinx.coroutines.CoroutineScope = this.scope) {
        // A restore that never finished leaves a marker in filesDir. Peek, do
        // not consume: the user-facing message is owned by the Backup section of
        // Settings, and clearing the marker here would mean the one place that
        // can explain the state never sees it. This log exists so a bug report
        // filed before the user ever opens Settings still carries the fact.
        backupManager?.let { provider ->
            scope.launch {
                runCatching { provider.get().peekInterruptedRestore() }
                    .onFailure { Log.w(TAG, "restore marker check failed: ${it.message}", it) }
                    .getOrNull()
                    ?.let { pending ->
                        Log.w(
                            TAG,
                            "a restore from \"${pending.sourceVersion}\" (${pending.mode}) started at " +
                                "${pending.startedAt} and never completed; database may be part-restored",
                        )
                    }
            }
        }
        // Load persisted emotion state so it survives cold starts.
        emotionEngine?.let { engine ->
            scope.launch { runCatching { engine.load() }.onFailure { Log.w("Bootstrap", "emotion load failed: ${it.message}", it) } }
        }
        // Load persisted narrative self so it survives cold starts.
        narrativeSelf?.let { ns ->
            scope.launch { runCatching { ns.load() }.onFailure { Log.w("Bootstrap", "narrative load failed: ${it.message}", it) } }
        }
        // Same for the other two consciousness components. Both held their
        // state in memory only until 2026-08-08, so every cold start wiped the
        // drive timestamps and the user model — see the class KDocs.
        intrinsicMotivation?.let { im ->
            scope.launch { runCatching { im.load() }.onFailure { Log.w("Bootstrap", "motivation load failed: ${it.message}", it) } }
        }
        theoryOfMind?.let { tom ->
            scope.launch { runCatching { tom.load() }.onFailure { Log.w("Bootstrap", "theory-of-mind load failed: ${it.message}", it) } }
        }
        // Seed builtin agents on first run, then repair the descriptions of
        // installs seeded before they were written by hand — a no-op once done.
        scope.launch {
            agentStore.seedBuiltins()
            runCatching { agentStore.refreshBuiltinDescriptions() }
                .onFailure { Log.w("Bootstrap", "builtin description refresh failed: ${it.message}", it) }
            // After the refresh, not before: this repairs agent_state rows, and
            // the refresh is what used to destroy them (REPLACE on a CASCADE
            // parent — fixed, but installs carrying the damage still need the
            // rows back). Also covers user-created agents, which never got a
            // state row at all because `create` never called ensureState.
            runCatching { agentStore.ensureAllAgentStates() }
                .onFailure { Log.w("Bootstrap", "agent state repair failed: ${it.message}", it) }
        }
        // Check Google/Microsoft integration connection state.
        scope.launch {
            runCatching { integrationTokenStore?.checkConnectionState() }
                .onFailure { e ->
                    android.util.Log.w("ProactiveBootstrap", "integration token state check failed: ${e.message}", e)
                }
        }
        // Soft-delete sweep on app start: hard-purges tombstones older
        // than the retention window. Cheap (indexed), no UI impact, no
        // need for a separate Worker.
        scope.launch {
            runCatching { conversationStore.purgeDeletedOlderThan() }.onFailure { android.util.Log.w(TAG, "Purge deleted conversations failed", it) }
        }
        // Keep one long-lived reconciliation collector.
        // their persisted defaults immediately and every Settings mutation
        // thereafter, so the worker schedules converge in the active
        // process instead of waiting for a restart.
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

        // Daemon reconciliation — separate flow to avoid 6-way combine limit.
        // Combined with the interval so an interval change reschedules live.
        scope.launch {
            kotlinx.coroutines.flow.combine(
                userPreferences.daemonEnabled,
                userPreferences.daemonIntervalMinutes,
            ) { on, interval -> on to interval }
                .distinctUntilChanged()
                .collect { (daemonOn, interval) ->
                    reconcileDaemon(daemonOn, interval)
                }
        }

        // Dream reconciliation — separate flow for the same reason:
        // the 5-way combine above is already at the overload limit.
        // dreamEnabled flips drive scheduleDream / cancelDream.
        scope.launch {
            userPreferences.dreamEnabled.distinctUntilChanged().collect { dreamOn ->
                reconcileDream(dreamOn)
            }
        }

        // Living world reconciliation — its own flow, same reason as dream.
        // A live collector rather than a start-up read, so flipping the toggle
        // reschedules in the running process instead of at next launch.
        scope.launch {
            userPreferences.livingWorldEnabled.distinctUntilChanged().collect { worldsOn ->
                reconcileLivingWorld(worldsOn)
            }
        }

        // Re-embed reconciliation. Queued unconditionally on start: the check
        // is one COUNT and the worker exits immediately when it is zero, which
        // is the normal case. There is no user-facing toggle because there is
        // no coherent "off" — leaving a corpus half-embedded by two different
        // models is not a state anyone would choose, it is just broken recall.
        scope.launch {
            runCatching { com.aura.memory.ReembedWorker.enqueue(appContext) }
                .onFailure { android.util.Log.w("ProactiveBootstrap", "reembed enqueue failed", it) }
        }

        // Decay reconciliation — separate flow for the same reason.
        // decayEnabled gates both the periodic schedule and the
        // startup decay pass.
        scope.launch {
            userPreferences.decayEnabled.distinctUntilChanged().collect { decayOn ->
                if (decayOn) scheduler.scheduleDecay() else scheduler.cancelDecay()
            }
        }

        // Startup decay pass — only if decay is enabled.
        scope.launch {
            val decayOn = userPreferences.decayEnabled.first()
            if (decayOn) {
                runCatching { memoryStore.runDecayPass() }
                    .onFailure { error ->
                        android.util.Log.w(
                            "ProactiveBootstrap",
                            "startup decay pass failed: ${error.message}",
                            error,
                        )
                    }
            }
        }

        // Trigger engine reconciliation — separate flow.
        scope.launch {
            userPreferences.triggersEnabled.distinctUntilChanged().collect { triggersOn ->
                if (triggersOn) com.aura.triggers.TriggerWorker.schedule(appContext)
                else androidx.work.WorkManager.getInstance(appContext).cancelUniqueWork("trigger-engine")
            }
        }

        // Reconnect MCP servers and register their tools into the ToolRegistry
        // so the agentic loop can see and call them.
        scope.launch {
            runCatching { reconnectMcpServers() }
                .onFailure { error ->
                    android.util.Log.w(
                        "ProactiveBootstrap",
                        "MCP reconnect failed: ${error.message}",
                        error,
                    )
                }
        }
    }

    private fun reconcile(gates: ProactiveGates) {
        applyGates(gates.morningBriefOn, gates.calendarMonitorOn, gates.briefHour)
        try {
            val refresh = Intent(ACTION_REFRESH_WIDGET).apply {
                setPackage(appContext.packageName)
            }
            appContext.sendBroadcast(refresh)
        } catch (e: Throwable) {
            android.util.Log.w("ProactiveBootstrap", "widget refresh broadcast failed: ${e.message}")
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
        } catch (e: Throwable) {
            android.util.Log.w("ProactiveBootstrap", "evolution reconcile failed: ${e.message}")
        }
    }

    private fun reconcileDaemon(daemonOn: Boolean, intervalMinutes: Int) {
        try {
            if (daemonOn) {
                DaemonScheduler.schedule(appContext, intervalMinutes)
            } else {
                DaemonScheduler.cancel(appContext)
            }
        } catch (e: Throwable) {
            android.util.Log.w("ProactiveBootstrap", "daemon reconcile failed: ${e.message}")
        }
    }

    /**
     * Start or stop the periodic dream-consolidation worker. Mirrors
     * [reconnectMcpServers] in the sense that both run a one-shot
     * pass on app start (the MCP pass reconnects; the dream pass
     * runs the cycle immediately if the user previously had it on).
     * The periodic schedule is then kicked off by
     * [ProactiveScheduler.scheduleDream].
     */
    private fun reconcileLivingWorld(worldsOn: Boolean) {
        try {
            if (worldsOn) {
                com.aura.creative.livingworld.LivingWorldScheduler.schedule(appContext)
            } else {
                com.aura.creative.livingworld.LivingWorldScheduler.cancel(appContext)
            }
        } catch (e: Throwable) {
            android.util.Log.w("ProactiveBootstrap", "living world reconcile failed: ${e.message}")
        }
    }

    private fun reconcileDream(dreamOn: Boolean) {
        try {
            if (dreamOn) {
                scheduler.scheduleDream()
            } else {
                scheduler.cancelDream()
            }
        } catch (e: Throwable) {
            android.util.Log.w("ProactiveBootstrap", "dream reconcile failed: ${e.message}")
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
                val token = runCatching { secureDataStore.getString("mcp_auth_${config.id}") }.onFailure { Log.w("ProactiveBootstrap", "runCatching failed: ${it.message}", it) }.getOrNull()
                if (token.isNullOrBlank()) config else config.copy(authToken = token)
            }
        } catch (e: Exception) {
            android.util.Log.w("ProactiveBootstrap", "Failed to parse MCP servers JSON: ${e.message}", e)
            return
        }

        for (config in servers) {
            if (!config.enabled) continue
            runCatching {
                mcpClientManager.connect(config, config.authToken)
            }.onFailure { android.util.Log.w(TAG, "MCP connect failed for ${config.id}", it) }
        }

        // Register all discovered MCP tools into the ToolRegistry
        mcpToolBridge.syncTools(servers)
    }

    /**
     * Apply the morning-brief + calendar-monitor gates. Pure-Kotlin,
     * no Context, no Android framework — this is the seam the unit
     * tests exercise.
     *
     * Both features are WorkManager jobs now: the morning brief runs
     * daily, the calendar check every 15 minutes ([CalendarCheckWorker]
     * replaced the old permanent foreground service). Each gate
     * schedules or cancels its worker via the scheduler.
     */
    internal fun applyGates(morningBriefOn: Boolean, calendarMonitorOn: Boolean, briefHour: Int = 7): GatedDecisions {
        if (morningBriefOn) {
            scheduler.scheduleMorningBrief(briefHour)
        } else {
            scheduler.cancelMorningBrief()
        }
        if (calendarMonitorOn) {
            scheduler.scheduleCalendarChecks()
        } else {
            scheduler.cancelCalendarChecks()
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