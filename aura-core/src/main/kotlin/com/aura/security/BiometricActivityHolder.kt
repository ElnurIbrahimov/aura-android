package com.aura.security

import androidx.fragment.app.FragmentActivity
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds a [WeakReference] to the current foreground [FragmentActivity] so
 * that tools (e.g. [BiometricPrompt][androidx.biometric.BiometricPrompt]) that
 * need a FragmentActivity reference can obtain it without the agent loop
 * having to thread the activity through every tool invocation.
 *
 * The activity sets itself in [FragmentActivity.onCreate] via
 * `holder.activity = this` and clears in [FragmentActivity.onDestroy] via
 * [clearIfCurrent] — never `holder.activity = null`, which is a different and
 * wrong thing. Two activities register here (`MainActivity` and the widget's
 * `QuickAskActivity`, which `MainActivity` can launch), so whichever is
 * destroyed second would otherwise clear a slot the other still owns, and an
 * unconditional clear on the way out of the overlay took the live main
 * activity with it.
 */
@Singleton
class BiometricActivityHolder @Inject constructor() {

    @Volatile
    private var activityRef: WeakReference<FragmentActivity>? = null

    /**
     * The current foreground [FragmentActivity], or `null` if no activity is alive.
     */
    var activity: FragmentActivity?
        get() = activityRef?.get()
        set(value) {
            activityRef = if (value != null) WeakReference(value) else null
        }

    /**
     * Clear the reference only if [candidate] is the activity currently held.
     *
     * The identity check is the whole point. `QuickAskActivity` sets itself
     * here on top of a live `MainActivity`; without the check, whichever one
     * is destroyed first hands a dead or absent activity to the next
     * `BiometricPrompt`, and whichever is destroyed second wipes a slot that
     * was never theirs.
     *
     * Reads and writes go through [activityRef] directly rather than the
     * [activity] accessor so a reference whose activity has already been
     * garbage-collected still clears rather than lingering as an empty
     * [WeakReference].
     */
    fun clearIfCurrent(candidate: FragmentActivity) {
        if (activityRef?.get() === candidate) activityRef = null
    }
}
