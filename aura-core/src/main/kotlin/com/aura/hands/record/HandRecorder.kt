package com.aura.hands.record

import com.aura.a11y.ScreenControlBridge
import com.aura.a11y.ScreenControlGuard
import com.aura.a11y.UiSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Watches one app while the user demonstrates a task, and writes down what it can.
 *
 * Driven by [onScreenChanged], which the accessibility service calls for window changes.
 * That callback fires for every window on the device, so the guards here are the feature:
 * a tick outside a live recording does nothing, and a tick from an app other than the one
 * being recorded does nothing. Aura learns nothing from the tick itself — it carries a
 * package name and no content — and everything it does learn comes from a snapshot it asks
 * for, under the same gate `screen_read` already runs under.
 *
 * Bounded like [com.aura.a11y.ScreenControlSession], for the same reason: this is driven by
 * a device-wide callback, and a recording the user forgets to stop would otherwise grow for
 * as long as they keep using their phone.
 */
@Singleton
class HandRecorder @Inject constructor(
    private val bridge: ScreenControlBridge,
) {
    data class State(
        val recording: Boolean = false,
        val boundPackage: String = "",
        val steps: List<RecordedStep> = emptyList(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** The last reading, held to diff the next one against. */
    private var previous: UiSnapshot? = null

    /**
     * Begin recording, binding to an app the user has not opened yet.
     *
     * When Record is tapped the foreground app is Aura, so there is nothing to bind to. The
     * first window that is not Aura's own — or any other package on the non-overridable
     * denylist — becomes the target, and everything after it is ignored.
     */
    fun start(packageName: String = "") {
        previous = null
        _state.value = State(recording = true, boundPackage = packageName)
    }

    /** End the recording and hand back what it gathered. */
    fun stop(): List<RecordedStep> {
        val gathered = _state.value.steps
        previous = null
        _state.value = State()
        return gathered
    }

    /** One window change in [packageName]. Reads the screen only if it is being recorded. */
    suspend fun onScreenChanged(packageName: String) {
        var current = _state.value
        if (!current.recording) return

        if (current.boundPackage.isEmpty()) {
            // Recording Aura's own screens would capture the review UI the user is about to
            // read, and the rest of the denylist is denied here for the same reasons acting
            // on it is.
            if (packageName.isEmpty() || packageName in ScreenControlGuard.deniedPackages()) return
            current = current.copy(boundPackage = packageName)
            _state.value = current
        }

        if (packageName != current.boundPackage) return

        // A failed read skips this tick rather than ending the recording: the screen is
        // mid-transition often enough that one miss is not a reason to lose the rest.
        val now = runCatching { bridge.snapshot() }.getOrNull() ?: return
        val before = previous
        previous = now
        if (before == null) return

        val step = StepInference.infer(before, now) ?: return
        val steps = current.steps + step
        if (steps.size >= MAX_STEPS) {
            previous = null
            _state.value = current.copy(recording = false, steps = steps.take(MAX_STEPS))
        } else {
            _state.value = current.copy(steps = steps)
        }
    }

    companion object {
        /**
         * Longer than any demonstration worth replaying, short enough that a forgotten
         * recording stops on its own.
         */
        const val MAX_STEPS = 50
    }
}
