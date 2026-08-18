package com.aura.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the app is currently unlocked, for the whole process.
 *
 * "Unlocked" used to be `remember { mutableStateOf(false) }` inside
 * `MainActivity.AuraRoot`, which made it a property of one composition rather
 * than of the app. Every other entry point therefore had no notion of the lock
 * at all: `QuickAskActivity` — a full `ChatViewModel` with memory recall, tools
 * and persistence — rendered conversation content with no check, and so did the
 * home-screen widgets. "Biometric required to open Aura" described one of five
 * doors.
 *
 * Relocking is keyed to the **process** going to the background, not to any one
 * activity stopping. That distinction is the whole reason this counts started
 * activities instead of observing a single lifecycle: `MainActivity` launching
 * `QuickAskActivity` stops `MainActivity`, and a per-activity ON_STOP would
 * relock the app in the middle of the user's own navigation. The counter only
 * reaches zero when nothing of Aura's is on screen.
 *
 * Deliberately not persisted. A process death should leave the app locked, and
 * the absence of storage is what guarantees it.
 */
@Singleton
class AppLockState @Inject constructor() {

    private val _unlocked = MutableStateFlow(false)

    /** True once the user has authenticated, until the app next goes to background. */
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    private val lock = Any()
    private var startedActivities = 0

    /** Record a successful authentication. */
    fun unlock() {
        _unlocked.value = true
    }

    /**
     * Drop back to locked.
     *
     * Called when the process backgrounds, and when the `appLockEnabled`
     * preference changes — flipping the switch on must not leave a session that
     * predates it running unlocked.
     */
    fun lock() {
        _unlocked.value = false
    }

    /** Wired from `Application.registerActivityLifecycleCallbacks`. */
    fun onActivityStarted() {
        synchronized(lock) { startedActivities++ }
    }

    /** Wired from `Application.registerActivityLifecycleCallbacks`. */
    fun onActivityStopped() {
        val nowBackgrounded = synchronized(lock) {
            startedActivities = (startedActivities - 1).coerceAtLeast(0)
            startedActivities == 0
        }
        if (nowBackgrounded) lock()
    }
}
