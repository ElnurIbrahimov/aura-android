package com.aura.security

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The app lock is a property of the process, not of one composition.
 *
 * It used to be `remember { mutableStateOf(false) }` inside
 * `MainActivity.AuraRoot`, which is why it covered exactly one screen:
 * `QuickAskActivity` ran the same `ChatViewModel` with memory recall and had no
 * notion of the lock at all, and neither did the home-screen widgets. The
 * README's "biometric gate for app lock" described one of five doors.
 *
 * The counting is the load-bearing part, and the reason this is not a single
 * lifecycle observer: `MainActivity` launching `QuickAskActivity` *stops*
 * `MainActivity`, so a per-activity ON_STOP relock would demand a fingerprint
 * in the middle of the user's own navigation. Only a count reaching zero means
 * the app is actually gone.
 */
class AppLockStateTest {

    @Test
    fun `starts locked`() {
        // No persistence, deliberately: process death must leave it locked, and
        // having nowhere to store the flag is what guarantees that.
        assertFalse(AppLockState().unlocked.value)
    }

    @Test
    fun `unlock opens it and stays open while a screen is showing`() {
        val state = AppLockState()
        state.onActivityStarted()
        state.unlock()
        assertTrue(state.unlocked.value)
    }

    @Test
    fun `navigating between two Aura screens does not relock`() {
        // The regression the counter exists for. MainActivity is stopped while
        // QuickAskActivity is on top; a naive ON_STOP relock fires here and
        // asks for a fingerprint to finish a tap the user just made.
        val state = AppLockState()
        state.onActivityStarted() // MainActivity
        state.unlock()

        state.onActivityStarted() // QuickAskActivity opens over it
        state.onActivityStopped() // MainActivity stops, as Android does

        assertTrue(
            state.unlocked.value,
            "one screen stopping is not the app going away — a count of started activities is",
        )
    }

    @Test
    fun `the app going to background relocks`() {
        val state = AppLockState()
        state.onActivityStarted()
        state.unlock()

        state.onActivityStopped()

        assertFalse(state.unlocked.value, "nothing of Aura's is on screen, so it must be locked")
    }

    @Test
    fun `returning from background requires unlocking again`() {
        val state = AppLockState()
        state.onActivityStarted()
        state.unlock()
        state.onActivityStopped()

        state.onActivityStarted()

        assertFalse(state.unlocked.value)
    }

    @Test
    fun `an unbalanced stop cannot drive the count negative`() {
        // A stray stop — a config change, a killed activity, an ordering the
        // platform does not promise — must not leave the counter below zero,
        // where a later start would not bring it back to "foregrounded" and the
        // app would relock at the wrong moment forever after.
        val state = AppLockState()
        state.onActivityStopped()
        state.onActivityStopped()

        state.onActivityStarted()
        state.unlock()
        state.onActivityStarted()
        state.onActivityStopped()

        assertTrue(state.unlocked.value, "the count must not have gone negative")
    }

    @Test
    fun `lock is idempotent and explicit`() {
        // Called directly when the appLockEnabled preference changes: switching
        // the lock on with the app already open must not leave it open.
        val state = AppLockState()
        state.onActivityStarted()
        state.unlock()

        state.lock()
        state.lock()

        assertFalse(state.unlocked.value)
    }
}
