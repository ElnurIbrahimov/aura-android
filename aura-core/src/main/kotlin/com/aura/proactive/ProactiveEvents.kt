package com.aura.proactive

import android.util.Log
import com.aura.data.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Proactive event store. Owns the latest + history of proactive
 * events and exposes the unread-count for the Home badge.
 *
 * @param scope Internal coroutine scope for `init` and the
 *   unread-count flow. Defaults to a SupervisorJob on
 *   Dispatchers.Default for production. Tests inject
 *   `TestScope`/`StandardTestDispatcher` so the internal launches
 *   drain on `advanceUntilIdle()`.
 *
 * Singleton-scoped so the internal SupervisorJob scope lives for the
 * app lifetime, not per-Activity-creation. Without @Singleton each
 * HomeViewModel or ProactiveHistoryViewModel instantiation would leak
 * a new SupervisorJob that never closes.
 */
@Singleton
class ProactiveEvents(
    private val bus: ProactiveEventBus,
    private val dao: ProactiveEventDao,
    private val interactionDao: ProactiveInteractionDao,
    private val evolutionHooks: com.aura.evolution.EvolutionHooks? = null,
    private val userPreferences: UserPreferences,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    /** JSON codec for serializing the structured brief context into the
     *  `body` column of the MorningBriefStructured event row. */
    private val briefContextJson: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Inject constructor(
        bus: ProactiveEventBus,
        dao: ProactiveEventDao,
        interactionDao: ProactiveInteractionDao,
        evolutionHooks: com.aura.evolution.EvolutionHooks? = null,
        userPreferences: UserPreferences,
    ) : this(bus, dao, interactionDao, evolutionHooks, userPreferences, CoroutineScope(SupervisorJob() + Dispatchers.Default))

    private val _latest = MutableStateFlow<ProactiveEventBus.Event?>(null)
    val latest: StateFlow<ProactiveEventBus.Event?> = _latest.asStateFlow()

    private val _history = MutableStateFlow<List<ProactiveEventBus.Event>>(emptyList())
    val history: StateFlow<List<ProactiveEventBus.Event>> = _history.asStateFlow()

    /**
     * Refresh trigger — bumped after every event write and after
     * [markSeen] so the unread-count flow re-queries Room.
     */
    private val _refreshTick = MutableStateFlow(0L)

    /**
     * Number of proactive events emitted since the user last opened
     * the history screen (or dismissed the Home card). Drives the
     * "📬 N today" badge on Home.
     *
     * Implementation: combine the refresh tick (bumped on every
     * event write and after [markSeen]) with the persisted
     * `lastSeenProactiveAt` and re-query the SQL count whenever
     * either changes. The query is wrapped in `runCatching` so a
     * transient DB error doesn't crash the home screen — a missed
     * count update is non-fatal; the next event will re-query.
     */
    val unreadCount: StateFlow<Int> = combine(
        _refreshTick,
        userPreferences.lastSeenProactiveAt,
    ) { _, lastSeenAt -> lastSeenAt }
        .let { combined ->
            // Re-query the SQL count whenever either source changes.
            // Run as a child of [scope] (configurable for tests) so
            // the SQL query executes on the test dispatcher too.
            val countFlow = MutableStateFlow(0)
            scope.launch {
                combined.collect { lastSeenAt ->
                    countFlow.value = runCatching { dao.countSince(lastSeenAt) }
                        .onFailure { android.util.Log.w("ProactiveEvents", "unreadCount query failed: ${it.message}") }
                        .getOrDefault(0)
                }
            }
            countFlow.asStateFlow()
        }

    init {
        // Load persisted history first, then collect new events
        scope.launch {
            runCatching {
                val persisted = dao.recent(100)
                _history.value = persisted.mapNotNull { it.toEvent() }
                // Re-run the unread count after loading history (lastSeenAt
                // is already flowing, but the initial value of
                // countFlow is 0 — this is the first "real" computation).
                _refreshTick.value = System.currentTimeMillis()
                // Bounded retention: drop events older than 30 days so the
                // table doesn't grow forever. 30 days is wide enough to keep
                // a month's worth of morning briefs/calendar warnings visible
                // in the Proactive history screen while keeping the table
                // small. Errors are swallowed — cleanup is best-effort.
                val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
                dao.deleteOlderThan(cutoff)
            }.onFailure { android.util.Log.w("ProactiveEvents", "history load/retention failed", it) }
        }
        scope.launch {
            bus.events.collect { event ->
                // Skip events that were already persisted — the
                // re-emission below gives the event its DB id, and
                // collecting it again would insert a duplicate row
                // and re-emit, creating an infinite insert→emit→insert
                // feedback loop. Only fresh events (id == 0L) from
                // producers should be persisted.
                if (event.id != 0L) return@collect
                _latest.value = event
                _history.value = (_history.value + event).takeLast(100)
                // Persist to Room
                val insertedId = runCatching {
                    dao.insert(event.toEntity())
                }.getOrDefault(-1L)

                // Bump the tick so the unread-count flow re-queries.
                _refreshTick.value = event.timestamp
                // Re-emit with the persisted id so UI can record interactions.
                if (insertedId > 0L) {
                    bus.tryEmit(event.withId(insertedId))
                }
            }
        }
    }

    /**
     * Cancel all internal coroutines. Call from tests' tearDown
     * to prevent the SupervisorJob scope from leaking background
     * work into the next test (causes UncaughtExceptionsBeforeTest
     * on cold CI runners).
     */
    fun cancel() {
        scope.cancel()
    }

    fun dismiss() {
        _latest.value = null
    }

    /**
     * Mark all currently-unread proactive events as seen. Called
     * when the user opens the proactive history screen or taps the
     * Home card to view all events. Persists `now` as the new
     * last-seen-at so the next unread count returns 0.
     */
    fun markSeen() {
        scope.launch {
            userPreferences.setLastSeenProactiveAt(System.currentTimeMillis())
            _refreshTick.value = System.currentTimeMillis()
        }
    }

    /**
     * Record a user interaction with a proactive event. Called by the
     * notification/history UI when the user dismisses, taps, snoozes, or
     * acts on a proactive card. The action history feeds the policy engine.
     */
    suspend fun recordInteraction(
        eventId: Long,
        eventType: String,
        action: String,
        feedback: String = "",
    ) {
        runCatching {
            interactionDao.insert(
                ProactiveInteractionEntity(
                    eventId = eventId,
                    action = action,
                    feedback = feedback,
                )
            )
        }.onFailure { android.util.Log.w("ProactiveEvents", "record interaction failed", it) }
        runCatching {
            when (action) {
                "dismissed" -> evolutionHooks?.onProactiveDismissed(eventId.toString(), dismissalKind = feedback.ifBlank { "user" })
                "acted" -> evolutionHooks?.onProactiveActionTaken(eventId.toString(), action = feedback.ifBlank { action })
                "snoozed" -> evolutionHooks?.onProactiveSnoozed(eventId.toString())
                "opened" -> evolutionHooks?.onProactiveOpened(eventId.toString(), eventType = eventType)
                else -> evolutionHooks?.onProactiveDelivered(eventId.toString(), eventType = eventType)
            }
        }.onFailure { android.util.Log.w("ProactiveEvents", "evolution hook failed", it) }
    }

    private fun ProactiveEventBus.Event.withId(id: Long): ProactiveEventBus.Event = when (this) {
        is ProactiveEventBus.Event.MorningBriefReady -> copy(id = id)
        is ProactiveEventBus.Event.MorningBriefStructured -> copy(id = id)
        is ProactiveEventBus.Event.CalendarEventSoon -> copy(id = id)
        is ProactiveEventBus.Event.MemoryDecayWarning -> copy(id = id)
        is ProactiveEventBus.Event.DaemonInsight -> copy(id = id)
    }

    private fun ProactiveEventEntity.toEvent(): ProactiveEventBus.Event? {
        return when (eventType) {
            "MorningBriefReady" -> ProactiveEventBus.Event.MorningBriefReady(title, body, timestamp, id)
            "MorningBriefStructured" -> {
                // The structured body is serialized as the entity's
                // `body` field. Older rows that lack the structured
                // blob are skipped — the legacy MorningBriefReady
                // event is the fallback surface.
                val ctx = runCatching {
                    briefContextJson.decodeFromString<BriefContext>(body)
                }.getOrNull() ?: return null
                ProactiveEventBus.Event.MorningBriefStructured(ctx, timestamp, id)
            }
            "CalendarEventSoon" -> {
                val minutes = body.toIntOrNull() ?: return null
                ProactiveEventBus.Event.CalendarEventSoon(title, minutes, timestamp, id)
            }
            // Legacy placeholder rows are deliberately ignored. Aura never had
            // a producer or geofence implementation for this advertised event.
            "LocationArrived" -> null
            "MemoryDecayWarning" -> ProactiveEventBus.Event.MemoryDecayWarning(body, title, timestamp, id)
            "DaemonInsight" -> ProactiveEventBus.Event.DaemonInsight(title, body, timestamp, id)
            else -> null
        }
    }

    private fun ProactiveEventBus.Event.toEntity(): ProactiveEventEntity = when (this) {
        is ProactiveEventBus.Event.MorningBriefReady -> ProactiveEventEntity(
            id = id, eventType = "MorningBriefReady", title = title, body = body, timestamp = timestamp,
            payload = "",
        )
        is ProactiveEventBus.Event.MorningBriefStructured -> ProactiveEventEntity(
            id = id, eventType = "MorningBriefStructured",
            title = "Morning brief",
            body = briefContextJson.encodeToString(BriefContext.serializer(), context),
            timestamp = timestamp,
            payload = "",
        )
        is ProactiveEventBus.Event.CalendarEventSoon -> ProactiveEventEntity(
            id = id, eventType = "CalendarEventSoon",
            title = title,
            body = minutesUntil.toString(),
            timestamp = timestamp,
            payload = "",
        )
        is ProactiveEventBus.Event.MemoryDecayWarning -> ProactiveEventEntity(
            id = id, eventType = "MemoryDecayWarning",
            title = preview,
            body = memoryId,
            timestamp = timestamp,
            payload = "",
        )
        is ProactiveEventBus.Event.DaemonInsight -> ProactiveEventEntity(
            id = id, eventType = "DaemonInsight",
            title = title,
            body = body,
            timestamp = timestamp,
            payload = "",
        )
    }
}