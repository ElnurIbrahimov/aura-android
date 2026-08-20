package com.aura.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single owner of the user-facing DataStore ("aura_settings").
 *
 * Earlier revisions of the codebase had three separate
 * `Context.auraPrefs` extension declarations across this file,
 * [com.aura.FirstRunGate], and
 * [com.aura.ui.settings.SettingsViewModel], each using the same
 * `preferencesDataStore(name = "aura_settings")` delegate. That
 * happened to work because the AndroidX delegate is process-cached
 * by name, but the duplication hid a worse bug: [FirstRunGate]
 * used `booleanPreferencesKey` while [SettingsViewModel] used
 * `stringPreferencesKey` for the same logical `first_run_complete`
 * field, so writes from the VM never reached the gate's reader.
 *
 * Now consolidated here. Lives in `:aura-core/data` so the
 * [com.aura.backup.BackupManager] (also in `:aura-core`) can
 * inject it without a circular dependency.
 */
private val Context.auraPrefs by preferencesDataStore(name = "aura_settings")
internal val KEY_DEFAULT_MODEL = stringPreferencesKey("default_model")
internal val KEY_VISION_MODEL = stringPreferencesKey("vision_model")
internal val KEY_BACKGROUND_MODEL = stringPreferencesKey("background_model")
/** Written once, by [UserPreferences.seedBackgroundModelOnce] — see its KDoc. */
internal val KEY_BACKGROUND_MODEL_SEEDED = booleanPreferencesKey("background_model_seeded")
internal val KEY_DEEP_MODE_MODEL = stringPreferencesKey("deep_mode_model")
internal val KEY_MOA_REFERENCE_MODELS = stringPreferencesKey("moa_reference_models")
internal val KEY_MOA_AGGREGATOR_MODEL = stringPreferencesKey("moa_aggregator_model")
internal val KEY_STICKY_PROJECT = stringPreferencesKey("sticky_project_id")

/** Normalize a catalog model id without inventing or rewriting it. */
internal fun normalizeModelId(model: String): String = model.trim()

internal val KEY_APP_LOCK_ENABLED = booleanPreferencesKey("app_lock_enabled")
internal val KEY_FIRST_RUN_COMPLETE = booleanPreferencesKey("first_run_complete")
internal val KEY_LAST_SEEN_PROACTIVE_AT = longPreferencesKey("last_seen_proactive_at")
internal val KEY_LIVING_WORLD_LAST_SEEN = stringPreferencesKey("living_world_last_seen")
/**
 * Proactive worker gates. Both default to true so a fresh install
 * gets the morning brief + calendar monitor out of the box. The
 * Settings screen exposes toggles for each.
 */
internal val KEY_MORNING_BRIEF_ENABLED = booleanPreferencesKey("morning_brief_enabled")
internal val KEY_CALENDAR_MONITOR_ENABLED = booleanPreferencesKey("calendar_monitor_enabled")
/**
 * Instance keys ("eventId:begin") of calendar occurrences the
 * [com.aura.proactive.CalendarMonitor] has already announced.
 * Persisted so process death can't cause duplicate announcements;
 * pruned by the monitor to occurrences that began within 24 h.
 */
internal val KEY_ANNOUNCED_CALENDAR_INSTANCES = stringSetPreferencesKey("announced_calendar_instances")
internal val KEY_TTS_ENABLED = booleanPreferencesKey("tts_enabled")
internal val KEY_INCOGNITO_DEFAULT = booleanPreferencesKey("incognito_default")
internal val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
internal val KEY_CUSTOM_IDENTITY = stringPreferencesKey("custom_identity")
internal val KEY_SPECIALIST_OVERRIDES = stringPreferencesKey("specialist_overrides")
internal val KEY_MORNING_BRIEF_HOUR = intPreferencesKey("morning_brief_hour")
internal val KEY_SPECIALIST_TOOL_OVERRIDES = stringPreferencesKey("specialist_tool_overrides")
internal val KEY_EVOLUTION_ENABLED = booleanPreferencesKey("evolution_enabled")
internal val KEY_EVOLUTION_INTERVAL_HOURS = intPreferencesKey("evolution_interval_hours")
internal val KEY_EVOLUTION_ONBOARDING_SHOWN = booleanPreferencesKey("evolution_onboarding_shown")
internal val KEY_DAEMON_ENABLED = booleanPreferencesKey("daemon_enabled")
internal val KEY_DAEMON_INTERVAL_MINUTES = intPreferencesKey("daemon_interval_minutes")
internal val KEY_DREAM_ENABLED = booleanPreferencesKey("dream_enabled")
internal val KEY_LIVING_WORLD_ENABLED = booleanPreferencesKey("living_world_enabled")
internal val KEY_INTERRUPTION_POLICIES = stringPreferencesKey("interruption_policies")
internal val KEY_DREAM_LAST_RUN_AT = longPreferencesKey("dream_last_run_at")
internal val KEY_DREAM_LAST_RUN_STATS = stringPreferencesKey("dream_last_run_stats")
internal val KEY_DECAY_ENABLED = booleanPreferencesKey("decay_enabled")
internal val KEY_SMARTER_MEMORY_ENABLED = booleanPreferencesKey("smarter_memory_enabled")
internal val KEY_AGENT_ID = stringPreferencesKey("agent_id")
internal val KEY_TRIGGERS_ENABLED = booleanPreferencesKey("triggers_enabled")
internal val KEY_TRIGGERS_JSON = stringPreferencesKey("triggers_json")
internal val KEY_PLANNING_ENABLED = booleanPreferencesKey("planning_enabled")
internal val KEY_PROMPT_CACHING_ENABLED = booleanPreferencesKey("prompt_caching_enabled")
internal val KEY_SCREEN_CONTROL_ENABLED = booleanPreferencesKey("screen_control_enabled")
internal val KEY_APP_AWARENESS_ENABLED = booleanPreferencesKey("app_awareness_enabled")
internal val KEY_PLACE_LOG_ENABLED = booleanPreferencesKey("place_log_enabled")
// Automatic backup. The passphrase is NOT here — it lives in SecureDataStore,
// because this DataStore is plaintext and the passphrase is the only thing
// standing between a synced folder and everything Aura knows.
internal val KEY_AUTO_BACKUP_ENABLED = booleanPreferencesKey("auto_backup_enabled")
internal val KEY_BACKUP_FOLDER_URI = stringPreferencesKey("backup_folder_uri")
internal val KEY_LAST_BACKUP_AT = longPreferencesKey("last_backup_at")
internal val KEY_LAST_BACKUP_ERROR = stringPreferencesKey("last_backup_error")
internal val KEY_MCP_SERVERS_JSON = stringPreferencesKey("mcp_servers_json")
internal val KEY_IMAGE_MODEL = stringPreferencesKey("image_model")
internal val KEY_VIDEO_MODEL = stringPreferencesKey("video_model")
internal val KEY_VOICE_MODEL = stringPreferencesKey("voice_model")
internal val KEY_SMTP_HOST = stringPreferencesKey("smtp_host")
internal val KEY_SMTP_PORT = intPreferencesKey("smtp_port")
internal val KEY_SMTP_USERNAME = stringPreferencesKey("smtp_username")
internal val KEY_SMTP_PASSWORD = stringPreferencesKey("smtp_password")
internal val KEY_SMTP_FROM = stringPreferencesKey("smtp_from")

internal val KEY_EMBEDDING_MODEL = stringPreferencesKey("embedding_model")

internal val KEY_GOOGLE_CLIENT_ID = stringPreferencesKey("google_client_id")
internal val KEY_MICROSOFT_CLIENT_ID = stringPreferencesKey("microsoft_client_id")
internal val KEY_REASONING_ENABLED = booleanPreferencesKey("reasoning_enabled")
internal val KEY_REASONING_BUDGET = intPreferencesKey("reasoning_budget")
internal val KEY_COUNCIL_ENABLED = booleanPreferencesKey("council_enabled")
internal val KEY_COUNCIL_AUTO_APPLY = booleanPreferencesKey("council_auto_apply")
internal val KEY_COUNCIL_ACTIVITY_LEVEL = intPreferencesKey("council_activity_level")

@Singleton
class UserPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureDataStore: com.aura.security.SecureDataStore? = null,
) {
    val defaultModel: Flow<String?> = optionalModel(KEY_DEFAULT_MODEL)
    val visionModel: Flow<String?> = optionalModel(KEY_VISION_MODEL)
    val backgroundModel: Flow<String?> = optionalModel(KEY_BACKGROUND_MODEL)
    val deepModeModel: Flow<String?> = optionalModel(KEY_DEEP_MODE_MODEL)
    val moaReferenceModels: Flow<List<String>> = context.auraPrefs.data.map { prefs ->
        prefs[KEY_MOA_REFERENCE_MODELS]
            ?.lineSequence()
            ?.map(::normalizeModelId)
            ?.filter(String::isNotBlank)
            ?.distinct()
            ?.toList()
            ?: emptyList()
    }
    val moaAggregatorModel: Flow<String?> = optionalModel(KEY_MOA_AGGREGATOR_MODEL)

    /**
     * Whether the user has enabled biometric app lock. When true,
     * the main activity gates on a [androidx.biometric.BiometricPrompt]
     * challenge before showing the rest of the UI. Stored locally;
     * defaults to false (no lock) for a fresh install.
     */
    val appLockEnabled: Flow<Boolean> = context.auraPrefs.data.map { prefs ->
        prefs[KEY_APP_LOCK_ENABLED] ?: false
    }

    /**
     * Whether the user has completed first-run onboarding. Read by
     * [com.aura.FirstRunGate] to decide whether to show the wizard
     * vs. the main app. Written by
     * [com.aura.ui.settings.SettingsViewModel] after the user saves
     * their first API key.
     */
    val firstRunComplete: Flow<Boolean> = context.auraPrefs.data.map { prefs ->
        prefs[KEY_FIRST_RUN_COMPLETE] ?: false
    }

    /**
     * Last time the user opened the proactive history screen (or
     * dismissed the proactive event card on Home). Used by
     * [com.aura.proactive.ProactiveEvents] to compute the
     * "unread proactive events" badge on Home. Default 0L means
     * everything since the Unix epoch counts as unread on a fresh
     * install — which is the right behavior for the first session.
     */
    val lastSeenProactiveAt: Flow<Long> = context.auraPrefs.data.map { prefs ->
        prefs[KEY_LAST_SEEN_PROACTIVE_AT] ?: 0L
    }

    /**
     * The last tick the user saw, per world — `worldId=tick` CSV, the
     * [interruptionPolicies] codec. Read by the Living tab's "Since you left"
     * block; written when the tab is opened. Absent means 0: on a first visit
     * everything is news, which is the right first impression.
     */
    val livingWorldLastSeen: Flow<Map<String, Long>> = context.auraPrefs.data.map { prefs ->
        prefs[KEY_LIVING_WORLD_LAST_SEEN].orEmpty()
            .split(',')
            .mapNotNull { entry ->
                val parts = entry.split('=')
                if (parts.size == 2 && parts[0].isNotBlank()) {
                    parts[1].toLongOrNull()?.let { parts[0] to it }
                } else {
                    null
                }
            }
            .toMap()
    }

    suspend fun setLivingWorldLastSeen(worldId: String, tick: Long) {
        context.auraPrefs.edit { prefs ->
            val current = prefs[KEY_LIVING_WORLD_LAST_SEEN].orEmpty()
                .split(',')
                .mapNotNull { entry ->
                    val parts = entry.split('=')
                    if (parts.size == 2 && parts[0].isNotBlank()) {
                        parts[1].toLongOrNull()?.let { parts[0] to it }
                    } else {
                        null
                    }
                }
                .toMap()
                .toMutableMap()
            current[worldId] = tick
            prefs[KEY_LIVING_WORLD_LAST_SEEN] =
                current.entries.joinToString(",") { "${it.key}=${it.value}" }
        }
    }

    /**
     * Whether the user wants the daily morning brief. When false,
     * [com.aura.proactive.ProactiveBootstrap] skips scheduling the
     * WorkManager job and cancels any in-flight schedule. Default
     * true — opt-out, not opt-in.
     */
    val morningBriefEnabled: Flow<Boolean> = context.auraPrefs.data.map { prefs ->
        prefs[KEY_MORNING_BRIEF_ENABLED] ?: true
    }

    /**
     * Whether the user wants the calendar monitor foreground
     * service running. When false, [com.aura.proactive.ProactiveBootstrap]
     * does not start the service on app launch. The user must
     * toggle this off to dismiss the persistent notification.
     * Default true — opt-out.
     */
    val calendarMonitorEnabled: Flow<Boolean> = context.auraPrefs.data.map { prefs ->
        prefs[KEY_CALENDAR_MONITOR_ENABLED] ?: true
    }

    /**
     * Announced calendar instance keys ("eventId:begin") — the
     * calendar monitor's persisted dedup set. Not user-facing;
     * excluded from backup like other transient bookkeeping.
     */
    val announcedCalendarInstances: Flow<Set<String>> = context.auraPrefs.data.map { prefs ->
        prefs[KEY_ANNOUNCED_CALENDAR_INSTANCES] ?: emptySet()
    }

    /**
     * Whether TTS (text-to-speech) is enabled. The chat screen
     * auto-reads assistant responses aloud when this is true.
     * Defaults to true for a fresh install; persisted so the user's
     * mute preference survives app restarts.
     */
    val ttsEnabled: Flow<Boolean> = context.auraPrefs.data.map { prefs ->
        prefs[KEY_TTS_ENABLED] ?: true
    }

    /**
     * Whether incognito mode is on by default for new sessions.
     * The chat screen's incognito toggle still works on top of this
     * default — it's the starting value for each new app launch.
     * Defaults to false (off) for a fresh install. A privacy-conscious
     * user can set this to true in Settings so every session starts
     * in incognito without re-toggling.
     */
    val incognitoDefault: Flow<Boolean> = context.auraPrefs.data.map { prefs ->
        prefs[KEY_INCOGNITO_DEFAULT] ?: false
    }

    /**
     * Theme mode: "system" (follow system dark/light), "light", or
     * "dark". Defaults to "system" so a fresh install respects the
     * device's current setting. The user can override in Settings.
     */
    val themeMode: Flow<String> = context.auraPrefs.data.map { prefs ->
        prefs[KEY_THEME_MODE] ?: "system"
    }

    /**
     * User-provided custom system prompt / identity override. When
     * non-blank, the [com.aura.agent.Brain] prepends this to the
     * default [Brain.IDENTITY], giving the user a way to personalize
     * Aura's persona without editing code. Defaults to blank (use
     * built-in identity only).
     */
    val customIdentity: Flow<String> = context.auraPrefs.data.map { prefs ->
        prefs[KEY_CUSTOM_IDENTITY] ?: ""
    }

    /**
     * JSON map of specialist name → custom system prompt. When a
     * specialist is selected, its entry here overrides the built-in
     * [com.aura.agent.Specialist.systemPrompt]. Empty string or
     * missing key = use the built-in prompt.
     */
    val specialistOverrides: Flow<String> = context.auraPrefs.data.map { prefs ->
        prefs[KEY_SPECIALIST_OVERRIDES] ?: "{}"
    }

    /**
     * Hour of day (0-23) for the morning brief. Default 7 (7am).
     * The user can change this in Settings → Morning brief time.
     */
    val morningBriefHour: Flow<Int> = context.auraPrefs.data.map { prefs ->
        prefs[KEY_MORNING_BRIEF_HOUR] ?: 7
    }

    /**
     * JSON map of specialist name → set of allowed tool names.
     * When a specialist is selected, its toolsAllowed is replaced
     * with this set if present. Empty map = use built-in defaults.
     */
    val specialistToolOverrides: Flow<String> = context.auraPrefs.data.map { prefs ->
        prefs[KEY_SPECIALIST_TOOL_OVERRIDES] ?: "{}"
    }

    /** Whether the evolution loop is enabled. Defaults to false (opt-in). */
    val evolutionEnabled: Flow<Boolean> = context.auraPrefs.data.map { prefs ->
        prefs[KEY_EVOLUTION_ENABLED] ?: false
    }

    /** Hours between evolution runs. Default 24. */
    val evolutionIntervalHours: Flow<Int> = context.auraPrefs.data.map { prefs ->
        prefs[KEY_EVOLUTION_INTERVAL_HOURS] ?: 24
    }

    val daemonEnabled: Flow<Boolean> = context.auraPrefs.data.map { it[KEY_DAEMON_ENABLED] ?: false }

    /**
     * How often the daemon thinking worker runs, in minutes. Default 60 —
     * the old hardcoded 15 meant up to 96 LLM-invoking runs/day on any
     * network at any battery level. WorkManager floors periodic work at
     * 15 minutes, so values below that are coerced by the scheduler.
     */
    val daemonIntervalMinutes: Flow<Int> = context.auraPrefs.data.map {
        it[KEY_DAEMON_INTERVAL_MINUTES] ?: DEFAULT_DAEMON_INTERVAL_MINUTES
    }

    /**
     * Whether the dream consolidator is enabled. Default true (opt-out
     * matches Python's always-on behavior; user can flip off in
     * Settings). When false, the periodic worker is cancelled.
     */
    val dreamEnabled: Flow<Boolean> = context.auraPrefs.data.map { it[KEY_DREAM_ENABLED] ?: true }

    /**
     * Whether living worlds tick in the background. Default true, because a
     * world that only moves while its screen is open is not a living world —
     * it is an animation. Off cancels the periodic worker; existing worlds keep
     * their state and resume from their clock when it is turned back on, since
     * which ticks are due is derived from the wall clock rather than from
     * whether a worker ever ran.
     */
    val livingWorldEnabled: Flow<Boolean> = context.auraPrefs.data.map { it[KEY_LIVING_WORLD_ENABLED] ?: true }

    /**
     * Whether the memory decay worker runs every 6h. Default true.
     * When disabled, all memories retain their full decayScore
     * indefinitely — useful for users who want to preserve everything.
     */
    val decayEnabled: Flow<Boolean> = context.auraPrefs.data.map { it[KEY_DECAY_ENABLED] ?: true }

    /**
     * Whether to fetch the on-device embedding model. Default **false**, and the
     * odd one out in the other direction from [decayEnabled].
     *
     * Off is not a judgement about whether the model is worth having — the eval
     * says it is, by +0.311 nDCG@10 on paraphrase queries. Off is because
     * turning it on downloads 137 MB, and that is a cost paid before any of the
     * benefit arrives. An on-by-default flag would mean an app update helping
     * itself to a nine-figure byte count on next launch.
     *
     * Switching it off again deletes the model. That is the only coherent
     * reading: leaving a complete model on disk while the toggle says off would
     * mean [com.aura.memory.onnx.RoutedEmbedder] keeps using it and the setting
     * controls nothing. `rebuildEmbeddings` converts the corpus back, the same
     * path that converted it in the first place.
     */
    val smarterMemoryEnabled: Flow<Boolean> =
        context.auraPrefs.data.map { it[KEY_SMARTER_MEMORY_ENABLED] ?: false }
val agentId: Flow<String?> = context.auraPrefs.data.map { it[KEY_AGENT_ID] }
    /** Whether the trigger worker runs every 15m. Default true (opt-out). */
    val triggersEnabled: Flow<Boolean> = context.auraPrefs.data.map { it[KEY_TRIGGERS_ENABLED] ?: true }

    /** JSON list of user-defined [com.aura.triggers.Trigger]. */
    val triggers: Flow<List<com.aura.triggers.Trigger>> = context.auraPrefs.data.map { prefs ->
        prefs[KEY_TRIGGERS_JSON]?.let { json ->
            runCatching { kotlinx.serialization.json.Json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(com.aura.triggers.Trigger.serializer()), json) }
                .onFailure { android.util.Log.w("UserPreferences", "failed to decode triggers: ${it.message}", it) }
                .getOrDefault(emptyList())
        } ?: emptyList()
    }

    suspend fun setTriggersEnabled(enabled: Boolean) {
        context.auraPrefs.edit { it[KEY_TRIGGERS_ENABLED] = enabled }
    }

    suspend fun setTriggers(triggers: List<com.aura.triggers.Trigger>) {
        context.auraPrefs.edit { prefs ->
            val json = kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(com.aura.triggers.Trigger.serializer()), triggers)
            prefs[KEY_TRIGGERS_JSON] = json
        }
    }

    /** Append a trigger, replacing any existing trigger with the same id. */
    suspend fun addOrReplaceTrigger(trigger: com.aura.triggers.Trigger) {
        val updated = triggers.first().toMutableList()
        updated.removeAll { it.id == trigger.id }
        updated.add(trigger)
        setTriggers(updated)
    }

    /** Remove a trigger by id. */
    suspend fun removeTrigger(id: kotlin.String) {
        val updated = triggers.first().filter { it.id != id }
        setTriggers(updated)
    }

    /**
     * Whether the agentic loop makes a separate "planning" LLM call before
     * answering. Default **false** — this is opt-in.
     *
     * When on, every user message longer than ~20 chars costs an extra
     * round-trip (capped at 15s) before the first token of the real answer
     * appears. The plan is injected as a system prefix to improve tool
     * selection. That trade — up to 15s of added latency plus a second
     * billed call on every turn — is only worth it for tool-heavy work, so
     * the user opts in rather than paying it by default.
     */
    val planningEnabled: Flow<Boolean> = context.auraPrefs.data.map { it[KEY_PLANNING_ENABLED] ?: false }

    suspend fun setPlanningEnabled(enabled: Boolean) {
        context.auraPrefs.edit { it[KEY_PLANNING_ENABLED] = enabled }
    }

    /**
     * Ask providers to cache the fixed part of the prompt across the steps of a
     * turn.
     *
     * Defaults **on**, unlike [planningEnabled]. Planning costs a round-trip
     * before the user sees a token, so it has to be opted into; caching costs
     * nothing, adds no latency, and only ever reduces the bill. What it can do
     * is expose a provider-side bug, or interact badly with an endpoint that
     * mishandles a `cache_control` key it does not know — so it ships with a
     * switch rather than as an unconditional behaviour.
     *
     * Turning it off restores byte-for-byte the request shape that shipped
     * before caching existed.
     */
    val promptCachingEnabled: Flow<Boolean> = context.auraPrefs.data.map { it[KEY_PROMPT_CACHING_ENABLED] ?: true }

    suspend fun setPromptCachingEnabled(enabled: Boolean) {
        context.auraPrefs.edit { it[KEY_PROMPT_CACHING_ENABLED] = enabled }
    }

    /**
     * Master switch for reading and controlling the screen of other apps.
     *
     * **Defaults OFF, and must stay that way.** This is the most invasive
     * capability in the app: once the accessibility service is enabled, every
     * window event in every app passes through this process. Off means the
     * tools are hidden from the model AND the bridge refuses — two independent
     * gates on purpose, because a tool absent from the schema can still be
     * invoked by a model that remembers it from earlier in the conversation.
     *
     * Turning this on does not grant anything by itself; Android still requires
     * the user to enable the service in system settings.
     */
    val screenControlEnabled: Flow<Boolean> = context.auraPrefs.data.map { it[KEY_SCREEN_CONTROL_ENABLED] ?: false }

    /**
     * Whether Aura may look at which app is in the foreground.
     *
     * Off by default and independent of Android's usage-access grant, so
     * turning the feature off silences it even while the system permission
     * stays granted. The two conditions are deliberately separate: revoking a
     * special permission is buried several screens deep in system settings, and
     * "stop doing this" should be one tap inside Aura.
     */
    val appAwarenessEnabled: Flow<Boolean> = context.auraPrefs.data.map { it[KEY_APP_AWARENESS_ENABLED] ?: false }

    suspend fun setAppAwarenessEnabled(enabled: Boolean) {
        context.auraPrefs.edit { it[KEY_APP_AWARENESS_ENABLED] = enabled }
    }

    /**
     * Whether Aura may keep a coarse record of where the user has been.
     *
     * **Off by default, and the only one of the background switches that is.**
     * Dreams, decay, triggers and the calendar monitor are opt-out because they
     * read what Aura already has; this one starts collecting something new about
     * the user's life, and the default for that is no.
     */
    val placeLogEnabled: Flow<Boolean> = context.auraPrefs.data.map { it[KEY_PLACE_LOG_ENABLED] ?: false }

    suspend fun setPlaceLogEnabled(enabled: Boolean) {
        context.auraPrefs.edit { it[KEY_PLACE_LOG_ENABLED] = enabled }
    }

    suspend fun setScreenControlEnabled(enabled: Boolean) {
        context.auraPrefs.edit { it[KEY_SCREEN_CONTROL_ENABLED] = enabled }
    }

    // ---- Automatic backup ----
    //
    // `allowBackup="false"` in the manifest is deliberate: Android's cloud backup
    // would hand the whole memory store to Google. The consequence was that the
    // only copy of everything Aura knows lived on one phone, behind a button
    // somebody had to remember to press.

    /**
     * Off until a folder and a passphrase both exist — see `BackupWorker`, which
     * treats a missing either as "not configured" rather than as a failure.
     */
    val autoBackupEnabled: Flow<Boolean> = context.auraPrefs.data.map { it[KEY_AUTO_BACKUP_ENABLED] ?: false }

    /**
     * A SAF tree URI the user picked, held by a persisted permission grant.
     *
     * A folder rather than a fixed path so it can be one their own cloud already
     * syncs — which is the part that makes this survive the device, and the part
     * no code here can do.
     */
    val backupFolderUri: Flow<String?> = context.auraPrefs.data.map {
        it[KEY_BACKUP_FOLDER_URI]?.takeIf(String::isNotBlank)
    }

    /** 0 when none has ever succeeded. */
    val lastBackupAt: Flow<Long> = context.auraPrefs.data.map { it[KEY_LAST_BACKUP_AT] ?: 0L }

    /**
     * Why the last attempt failed, or blank.
     *
     * Kept because a backup is the one background job whose silent failure is
     * unrecoverable: every other worker gets another go tomorrow and loses
     * nothing, and this one loses the week it was supposed to protect.
     */
    val lastBackupError: Flow<String> = context.auraPrefs.data.map { it[KEY_LAST_BACKUP_ERROR] ?: "" }

    suspend fun setAutoBackupEnabled(enabled: Boolean) {
        context.auraPrefs.edit { it[KEY_AUTO_BACKUP_ENABLED] = enabled }
    }

    suspend fun setBackupFolderUri(uri: String?) {
        context.auraPrefs.edit { prefs ->
            if (uri.isNullOrBlank()) prefs.remove(KEY_BACKUP_FOLDER_URI) else prefs[KEY_BACKUP_FOLDER_URI] = uri
        }
    }

    /** Records the outcome of one run. Success clears the error; failure keeps the old timestamp. */
    suspend fun recordBackupOutcome(at: Long, error: String = "") {
        context.auraPrefs.edit { prefs ->
            if (error.isBlank()) {
                prefs[KEY_LAST_BACKUP_AT] = at
                prefs.remove(KEY_LAST_BACKUP_ERROR)
            } else {
                prefs[KEY_LAST_BACKUP_ERROR] = error.take(MAX_BACKUP_ERROR)
            }
        }
    }

    /**
     * Wall-clock millis of the last successful dream cycle. 0 = never.
     * Surfaced as "Last ran: 2 days ago" in Settings → Memory.
     */
    val dreamLastRunAt: Flow<Long> = context.auraPrefs.data.map { it[KEY_DREAM_LAST_RUN_AT] ?: 0L }

    /**
     * One-line stats from the last cycle ("3 summaries, 1 cluster, 240 chars saved").
     * Surfaced in Settings → Memory below the "last ran" stat.
     */
    val dreamLastRunStats: Flow<String> = context.auraPrefs.data.map { it[KEY_DREAM_LAST_RUN_STATS] ?: "" }

    suspend fun setDreamEnabled(enabled: Boolean) {
        context.auraPrefs.edit { it[KEY_DREAM_ENABLED] = enabled }
    }

    suspend fun setLivingWorldEnabled(enabled: Boolean) {
        context.auraPrefs.edit { it[KEY_LIVING_WORLD_ENABLED] = enabled }
    }

    /**
     * Per-category interruption policy, as `type=POLICY` pairs.
     *
     * Absent means EARNED, which is the default and the reason this needs no
     * configuring: the ledger decides unless the user says otherwise. Stored as
     * one string rather than a key per category so adding a ninth finding type
     * needs no new preference key.
     */
    val interruptionPolicies: Flow<Map<String, String>> = context.auraPrefs.data.map { prefs ->
        decodePolicyMap(prefs[KEY_INTERRUPTION_POLICIES].orEmpty())
    }

    suspend fun setInterruptionPolicy(type: String, policy: String) {
        context.auraPrefs.edit { prefs ->
            val current = prefs[KEY_INTERRUPTION_POLICIES].orEmpty()
                .split(',')
                .mapNotNull { entry ->
                    val parts = entry.split('=')
                    if (parts.size == 2 && parts[0].isNotBlank()) parts[0] to parts[1] else null
                }
                .toMap()
                .toMutableMap()
            // EARNED is the default, so it is stored as absence rather than as a
            // value — the map only ever holds deliberate overrides.
            if (policy == "EARNED") current.remove(type) else current[type] = policy
            prefs[KEY_INTERRUPTION_POLICIES] = encodePolicyMap(current)
        }
    }


    /**
     * Record the result of a successful dream cycle. Called by
     * [com.aura.dream.DreamWorker] after `runCycle` returns. The
     * stats string is what the user sees in Settings.
     */
    suspend fun recordDreamRun(report: com.aura.dream.DreamCycleReport) {
        context.auraPrefs.edit { prefs ->
            prefs[KEY_DREAM_LAST_RUN_AT] = System.currentTimeMillis()
            prefs[KEY_DREAM_LAST_RUN_STATS] = report.statsLine()
        }
    }

    val evolutionOnboardingShown: Flow<Boolean> = context.auraPrefs.data.map { prefs ->
        prefs[KEY_EVOLUTION_ONBOARDING_SHOWN] ?: false
    }

    suspend fun setDefaultModel(model: String?) = setOptionalModel(KEY_DEFAULT_MODEL, model)
    suspend fun setVisionModel(model: String?) = setOptionalModel(KEY_VISION_MODEL, model)
    suspend fun setBackgroundModel(model: String?) = setOptionalModel(KEY_BACKGROUND_MODEL, model)

    /**
     * Give background work a model, once, if nothing else has.
     *
     * `backgroundModel` has no default and was only ever written from Settings
     * or a restored backup, so on any install that never visited
     * Settings → AI & Models it stayed null — and five subsystems hard-return on
     * exactly that: [com.aura.curiosity.QuestionAuthor],
     * [com.aura.curiosity.SelfServeResearcher], [com.aura.proactive.DaemonWorker],
     * [com.aura.proactive.IdleTimePreparationEngine] and
     * [com.aura.proactive.MorningBriefBuilder]. Each returned quietly and looked
     * exactly like a feature with nothing to say. `BackgroundHealth` lists this
     * switch first for that reason; naming the problem is not fixing it.
     *
     * Not solved by falling back to the conversation model at the call sites:
     * [com.aura.providers.ModelRoleRouter.explicit] exists precisely so an
     * unattended caller does not silently spend the expensive model, and that
     * distinction is worth keeping. A seeded value is visible and editable in
     * Settings; a fallback is neither.
     *
     * One writer, called from both onboarding and the startup backfill, because
     * two copies of "seed it if unset" is how two answers start disagreeing.
     *
     * The flag, not the emptiness of the field, is what makes this once: someone
     * who deliberately clears the background model must find it still cleared.
     *
     * @return the model it seeded, or null if it did nothing.
     */
    suspend fun seedBackgroundModelOnce(): String? {
        val prefs = context.auraPrefs.data.first()
        if (prefs[KEY_BACKGROUND_MODEL_SEEDED] == true) return null

        val already = prefs[KEY_BACKGROUND_MODEL]?.let(::normalizeModelId)?.takeIf(String::isNotBlank)
        val chat = prefs[KEY_DEFAULT_MODEL]?.let(::normalizeModelId)?.takeIf(String::isNotBlank)

        // Nothing to seed from yet. Deliberately does NOT mark itself done:
        // onboarding can run before a model is chosen, and the startup backfill
        // has to still be able to do the work on the next launch.
        if (already == null && chat == null) return null

        context.auraPrefs.edit { edit ->
            edit[KEY_BACKGROUND_MODEL_SEEDED] = true
            if (already == null && chat != null) edit[KEY_BACKGROUND_MODEL] = chat
        }
        return if (already == null) chat else null
    }
    suspend fun setDeepModeModel(model: String?) = setOptionalModel(KEY_DEEP_MODE_MODEL, model)
    suspend fun setMoaReferenceModels(models: List<String>) {
        val value = models.map(::normalizeModelId)
            .filter(String::isNotBlank)
            .distinct()
            .take(4)
            .joinToString("\n")
        context.auraPrefs.edit { prefs ->
            if (value.isBlank()) prefs.remove(KEY_MOA_REFERENCE_MODELS)
            else prefs[KEY_MOA_REFERENCE_MODELS] = value
        }
    }
    suspend fun setMoaAggregatorModel(model: String?) =
        setOptionalModel(KEY_MOA_AGGREGATOR_MODEL, model)

    suspend fun setAppLockEnabled(enabled: Boolean) {
        context.auraPrefs.edit { it[KEY_APP_LOCK_ENABLED] = enabled }
    }

    suspend fun setFirstRunComplete(complete: Boolean) {
        context.auraPrefs.edit { it[KEY_FIRST_RUN_COMPLETE] = complete }
    }

    suspend fun setLastSeenProactiveAt(timestamp: Long) {
        context.auraPrefs.edit { it[KEY_LAST_SEEN_PROACTIVE_AT] = timestamp }
    }

    suspend fun setMorningBriefEnabled(enabled: Boolean) {
        context.auraPrefs.edit { it[KEY_MORNING_BRIEF_ENABLED] = enabled }
    }

    suspend fun setCalendarMonitorEnabled(enabled: Boolean) {
        context.auraPrefs.edit { it[KEY_CALENDAR_MONITOR_ENABLED] = enabled }
    }

    suspend fun setAnnouncedCalendarInstances(keys: Set<String>) {
        context.auraPrefs.edit { it[KEY_ANNOUNCED_CALENDAR_INSTANCES] = keys }
    }

    suspend fun setTtsEnabled(enabled: Boolean) {
        context.auraPrefs.edit { it[KEY_TTS_ENABLED] = enabled }
    }

    suspend fun setIncognitoDefault(enabled: Boolean) {
        context.auraPrefs.edit { it[KEY_INCOGNITO_DEFAULT] = enabled }
    }

    suspend fun setThemeMode(mode: String) {
        context.auraPrefs.edit { it[KEY_THEME_MODE] = mode }
    }

    suspend fun setCustomIdentity(identity: String) {
        context.auraPrefs.edit { it[KEY_CUSTOM_IDENTITY] = identity }
    }

    suspend fun setSpecialistOverrides(json: String) {
        context.auraPrefs.edit { it[KEY_SPECIALIST_OVERRIDES] = json }
    }

    suspend fun setSpecialistToolOverrides(json: String) {
        context.auraPrefs.edit { it[KEY_SPECIALIST_TOOL_OVERRIDES] = json }
    }

    suspend fun setEvolutionEnabled(enabled: Boolean) {
        context.auraPrefs.edit { it[KEY_EVOLUTION_ENABLED] = enabled }
    }

    suspend fun setEvolutionIntervalHours(hours: Int) {
        context.auraPrefs.edit { it[KEY_EVOLUTION_INTERVAL_HOURS] = hours.coerceIn(1, 168) }
    }

    suspend fun setDaemonEnabled(enabled: Boolean) {
        context.auraPrefs.edit { prefs -> prefs[KEY_DAEMON_ENABLED] = enabled }
    }

    suspend fun setDaemonIntervalMinutes(minutes: Int) {
        context.auraPrefs.edit { prefs ->
            prefs[KEY_DAEMON_INTERVAL_MINUTES] = minutes.coerceIn(15, 24 * 60)
        }
    }

    suspend fun setSmarterMemoryEnabled(enabled: Boolean) {
        context.auraPrefs.edit { prefs -> prefs[KEY_SMARTER_MEMORY_ENABLED] = enabled }
    }

    suspend fun setDecayEnabled(enabled: Boolean) {
        context.auraPrefs.edit { prefs -> prefs[KEY_DECAY_ENABLED] = enabled }
    }

    suspend fun setAgentId(agentId: String?) {
        context.auraPrefs.edit { prefs ->
            if (agentId == null) {
                prefs.remove(KEY_AGENT_ID)
            } else {
                prefs[KEY_AGENT_ID] = agentId
            }
        }
    }

    suspend fun setEvolutionOnboardingShown(shown: Boolean) {
        context.auraPrefs.edit { it[KEY_EVOLUTION_ONBOARDING_SHOWN] = shown }
    }

    /** Persisted MCP server configs as JSON. Empty string = no servers. */
    val mcpServersJson: Flow<kotlin.String> = context.auraPrefs.data.map { prefs ->
        prefs[KEY_MCP_SERVERS_JSON] ?: ""
    }

    suspend fun setMcpServersJson(json: kotlin.String) {
        context.auraPrefs.edit { it[KEY_MCP_SERVERS_JSON] = json }
    }

    /**
     * Which model generates images, as `prefix:model` — the same shape the chat
     * picker uses, e.g. `agnes:agnes-image-2.1-flash`.
     *
     * Null means "use the first discovered backend for this capability", so
     * image generation works with no configuration at all. The setting is an
     * override, not a prerequisite. A bare model name with no prefix is read as
     * OpenAI, which is what every value written before providers were
     * routable meant.
     */
    val imageModel: Flow<kotlin.String?> = context.auraPrefs.data.map { prefs ->
        prefs[KEY_IMAGE_MODEL]?.takeIf { it.isNotBlank() }
    }

    suspend fun setImageModel(model: kotlin.String) {
        context.auraPrefs.edit { it[KEY_IMAGE_MODEL] = model }
    }

    /** Which model generates video. Same `prefix:model` shape as [imageModel]. */
    val videoModel: Flow<kotlin.String?> = context.auraPrefs.data.map { prefs ->
        prefs[KEY_VIDEO_MODEL]?.takeIf { it.isNotBlank() }
    }

    suspend fun setVideoModel(model: kotlin.String) {
        context.auraPrefs.edit { it[KEY_VIDEO_MODEL] = model }
    }

    /** Which model speaks (text-to-speech). Same `prefix:model` shape as [imageModel]. */
    val voiceModel: Flow<kotlin.String?> = context.auraPrefs.data.map { prefs ->
        prefs[KEY_VOICE_MODEL]?.takeIf { it.isNotBlank() }
    }

    suspend fun setVoiceModel(model: kotlin.String) {
        context.auraPrefs.edit { it[KEY_VOICE_MODEL] = model }
    }

    suspend fun setMorningBriefHour(hour: Int) {
        context.auraPrefs.edit { it[KEY_MORNING_BRIEF_HOUR] = hour.coerceIn(0, 23) }
    }

    /**
     * Suspend helper to read the current first-run flag as a
     * one-shot. Used by callers that need a synchronous-style read.
     */
    suspend fun isFirstRunComplete(): Boolean = firstRunComplete.first()

    private fun optionalModel(key: Preferences.Key<String>): Flow<String?> =
        context.auraPrefs.data.map { prefs ->
            prefs[key]?.let(::normalizeModelId)?.takeIf(String::isNotBlank)
        }


    val smtpHost: Flow<String> = context.auraPrefs.data.map { it[KEY_SMTP_HOST] ?: "" }
    val smtpPort: Flow<Int> = context.auraPrefs.data.map { it[KEY_SMTP_PORT] ?: 587 }
    val smtpUsername: Flow<String> = context.auraPrefs.data.map { it[KEY_SMTP_USERNAME] ?: "" }
    /** SMTP password is stored in SecureDataStore (encrypted via Android Keystore). */
    val smtpPassword: Flow<String> = kotlinx.coroutines.flow.flow {
        emit(secureDataStore?.getString("smtp_password") ?: "")
    }
    val smtpFrom: Flow<String> = context.auraPrefs.data.map { it[KEY_SMTP_FROM] ?: "" }

    suspend fun setSmtpConfig(host: String, port: Int, username: String, password: String, from: String) {
        context.auraPrefs.edit { prefs ->
            prefs[KEY_SMTP_HOST] = host.trim()
            prefs[KEY_SMTP_PORT] = port.coerceIn(1, 65535)
            prefs[KEY_SMTP_USERNAME] = username.trim()
            // Password goes to SecureDataStore, not plain DataStore.
            prefs.remove(KEY_SMTP_PASSWORD)
            prefs[KEY_SMTP_FROM] = from.trim().ifBlank { username.trim() }
        }
        // Only update SecureDataStore when a password is provided.
        // When password is blank (e.g. backup restore where password
        // isn't in the backup JSON), preserve the existing stored password.
        if (password.isNotBlank()) {
            secureDataStore?.putString("smtp_password", password)
        }
        // Explicit clear: caller passes password = " " (single space) to
        // signal "remove existing password". This avoids wiping the stored
        // password when the caller just doesn't have one to pass (backup).
        // (No explicit clear needed yet — the UI can call secureDataStore
        // directly if a "clear password" action is ever needed.)
    }

    // ---- Google / Microsoft OAuth client IDs ----

    val googleClientId: Flow<String> = context.auraPrefs.data.map { it[KEY_GOOGLE_CLIENT_ID] ?: "" }
    val microsoftClientId: Flow<String> = context.auraPrefs.data.map { it[KEY_MICROSOFT_CLIENT_ID] ?: "" }

    suspend fun setGoogleClientId(id: kotlin.String) {
        context.auraPrefs.edit { it[KEY_GOOGLE_CLIENT_ID] = id.trim() }
    }

    suspend fun setMicrosoftClientId(id: kotlin.String) {
        context.auraPrefs.edit { it[KEY_MICROSOFT_CLIENT_ID] = id.trim() }
    }

    // ---- Reasoning / Extended Thinking ----

    /** Whether extended thinking is always on. Default: true (maximum reasoning). */
    val reasoningEnabled: Flow<Boolean> = context.auraPrefs.data.map { it[KEY_REASONING_ENABLED] ?: true }

    /** Thinking budget in tokens. Default: 32000 (maximum). */
    val reasoningBudget: Flow<Int> = context.auraPrefs.data.map { it[KEY_REASONING_BUDGET] ?: 32000 }

    suspend fun setReasoningEnabled(enabled: kotlin.Boolean) {
        context.auraPrefs.edit { it[KEY_REASONING_ENABLED] = enabled }
    }

    suspend fun setReasoningBudget(budget: kotlin.Int) {
        context.auraPrefs.edit { it[KEY_REASONING_BUDGET] = budget.coerceIn(0, 128_000) }
    }

    private suspend fun setOptionalModel(key: Preferences.Key<String>, model: kotlin.String?) {
        val normalized = model?.let(::normalizeModelId)?.takeIf(kotlin.String::isNotBlank)
        context.auraPrefs.edit { prefs ->
            if (normalized == null) prefs.remove(key) else prefs[key] = normalized
        }
    }

    // ---- Sticky project ----

    /**
     * The project a new conversation inherits.
     *
     * A preference rather than derived from the most recent conversation,
     * because "the last project I worked on" and "the project of the last
     * conversation I happened to open" are different things — reading history
     * would silently re-point the sticky project every time an old chat was
     * opened to look something up.
     *
     * Null means unattributed, which stays a first-class state: not every
     * conversation belongs to a project, and forcing one would put the weather
     * into ARC-AGI-2's ledger.
     */
    val stickyProjectId: Flow<kotlin.String?> = context.auraPrefs.data.map { prefs ->
        prefs[KEY_STICKY_PROJECT]?.takeIf(kotlin.String::isNotBlank)
    }

    suspend fun setStickyProjectId(projectId: kotlin.String?) {
        context.auraPrefs.edit { prefs ->
            val clean = projectId?.trim()?.takeIf(kotlin.String::isNotEmpty)
            if (clean == null) prefs.remove(KEY_STICKY_PROJECT) else prefs[KEY_STICKY_PROJECT] = clean
        }
    }

    // ---- Model role routing ----

    private val KEY_CREATIVE_DRAFT_MODEL = stringPreferencesKey("creative_draft_model")
    private val KEY_CREATIVE_CRITIC_MODEL = stringPreferencesKey("creative_critic_model")
    private val KEY_PLANNER_MODEL = stringPreferencesKey("planner_model")
    private val KEY_VERIFIER_MODEL = stringPreferencesKey("verifier_model")
    private val KEY_FAST_MODEL = stringPreferencesKey("fast_model")
    private val KEY_REASONING_MODEL = stringPreferencesKey("reasoning_model")
    private val KEY_EVOLUTION_MODEL = stringPreferencesKey("evolution_model")

    /** Flow of the user's preferred model for a [com.aura.providers.ModelRole]. */
    fun forRole(role: com.aura.providers.ModelRole): Flow<kotlin.String?> = when (role) {
        com.aura.providers.ModelRole.CONVERSATION -> defaultModel
        com.aura.providers.ModelRole.BACKGROUND -> backgroundModel
        com.aura.providers.ModelRole.DEEP_RESEARCH -> deepModeModel
        com.aura.providers.ModelRole.FAST -> optionalModel(KEY_FAST_MODEL)
        com.aura.providers.ModelRole.REASONING -> optionalModel(KEY_REASONING_MODEL)
        com.aura.providers.ModelRole.CREATIVE_DRAFT -> optionalModel(KEY_CREATIVE_DRAFT_MODEL)
        com.aura.providers.ModelRole.CREATIVE_CRITIC -> optionalModel(KEY_CREATIVE_CRITIC_MODEL)
        com.aura.providers.ModelRole.PLANNER -> optionalModel(KEY_PLANNER_MODEL)
        com.aura.providers.ModelRole.VERIFIER -> optionalModel(KEY_VERIFIER_MODEL)
        com.aura.providers.ModelRole.EMBEDDING -> optionalModel(KEY_EMBEDDING_MODEL)
        com.aura.providers.ModelRole.EVOLUTION -> optionalModel(KEY_EVOLUTION_MODEL)
    }

    /** Set the model for a [com.aura.providers.ModelRole]. Null clears it. */
    suspend fun setRoleModel(role: com.aura.providers.ModelRole, model: kotlin.String?) {
        when (role) {
            com.aura.providers.ModelRole.CONVERSATION -> setDefaultModel(model)
            com.aura.providers.ModelRole.BACKGROUND -> setBackgroundModel(model)
            com.aura.providers.ModelRole.DEEP_RESEARCH -> setDeepModeModel(model)
            com.aura.providers.ModelRole.FAST -> setOptionalModel(KEY_FAST_MODEL, model)
            com.aura.providers.ModelRole.REASONING -> setOptionalModel(KEY_REASONING_MODEL, model)
            com.aura.providers.ModelRole.CREATIVE_DRAFT -> setOptionalModel(KEY_CREATIVE_DRAFT_MODEL, model)
            com.aura.providers.ModelRole.CREATIVE_CRITIC -> setOptionalModel(KEY_CREATIVE_CRITIC_MODEL, model)
            com.aura.providers.ModelRole.PLANNER -> setOptionalModel(KEY_PLANNER_MODEL, model)
            com.aura.providers.ModelRole.VERIFIER -> setOptionalModel(KEY_VERIFIER_MODEL, model)
            com.aura.providers.ModelRole.EMBEDDING -> setOptionalModel(KEY_EMBEDDING_MODEL, model)
            com.aura.providers.ModelRole.EVOLUTION -> setOptionalModel(KEY_EVOLUTION_MODEL, model)
        }
    }

    /**
     * Whether the council debates during daemon runs. Default FALSE —
     * a multi-agent LLM debate firing on a background schedule is a real
     * cost/battery center the user should opt INTO, not out of. (Was
     * default-on until the P0 sweep.)
     */
    val councilEnabled: Flow<Boolean> = context.auraPrefs.data.map { it[KEY_COUNCIL_ENABLED] ?: false }

    /**
     * Whether council interventions are auto-applied without user
     * approval. Default false — all interventions require explicit
     * approval. Only enable if you trust the council completely.
     */
    val councilAutoApply: Flow<Boolean> = context.auraPrefs.data.map { it[KEY_COUNCIL_AUTO_APPLY] ?: false }

    /**
     * Council activity level (1-5). Controls how many findings are
     * debated per session and how many agents participate.
     * 1 = minimal (1 finding, 3 agents), 5 = active (3 findings, 5 agents).
     * Default 3.
     */
    val councilActivityLevel: Flow<Int> = context.auraPrefs.data.map { it[KEY_COUNCIL_ACTIVITY_LEVEL] ?: 3 }

    suspend fun setCouncilEnabled(enabled: Boolean) {
        context.auraPrefs.edit { it[KEY_COUNCIL_ENABLED] = enabled }
    }

    suspend fun setCouncilAutoApply(enabled: Boolean) {
        context.auraPrefs.edit { it[KEY_COUNCIL_AUTO_APPLY] = enabled }
    }

    suspend fun setCouncilActivityLevel(level: Int) {
        context.auraPrefs.edit { it[KEY_COUNCIL_ACTIVITY_LEVEL] = level.coerceIn(1, 5) }
    }

    companion object {
        /**
         * The `key=value,key=value` codec [interruptionPolicies] parses.
         *
         * Extracted because the inline writer shipped rendering a literal
         * `${'$'}{it.key}` — the template escape leaked into the string — so
         * every save stored one junk entry and every explicit ALWAYS/NEVER
         * choice quietly fell back to EARNED on the next read. A codec that
         * cannot be called from a plain test is a codec whose round trip is
         * never checked.
         */
        internal fun encodePolicyMap(policies: Map<kotlin.String, kotlin.String>): kotlin.String =
            policies.entries.joinToString(",") { entry -> entry.key + "=" + entry.value }

        internal fun decodePolicyMap(stored: kotlin.String): Map<kotlin.String, kotlin.String> =
            stored.split(',')
                .mapNotNull { entry ->
                    val parts = entry.split('=')
                    if (parts.size == 2 && parts[0].isNotBlank()) parts[0] to parts[1] else null
                }
                .toMap()

        const val DEFAULT_DAEMON_INTERVAL_MINUTES = 60

        /** One line for the Settings row, not a stack trace. */
        const val MAX_BACKUP_ERROR = 200

        /** SecureDataStore key for the backup passphrase — see [BackupWorker]. */
        const val BACKUP_PASSPHRASE_KEY = "backup_passphrase"
    }
}