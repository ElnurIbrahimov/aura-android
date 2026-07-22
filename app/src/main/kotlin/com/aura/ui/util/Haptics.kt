package com.aura.ui.util

import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Tactile feedback for chat events. Haptics make the app feel responsive
 * in a way that visual feedback alone can't — sending a message and
 * receiving a response get distinct patterns.
 *
 * Uses the view's performHapticFeedback which respects the user's
 * system-level haptic setting (so if they turned it off, ours stays off too).
 */
object Haptics {
    fun send(view: View) = perform(view, HapticFeedbackConstants.KEYBOARD_TAP)

    fun receive(view: View) = perform(view, HapticFeedbackConstants.CONTEXT_CLICK)

    private fun perform(view: View, code: Int) {
        view.performHapticFeedback(code)
    }
}