package com.aura.a11y

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A bounded grant to drive the screen.
 *
 * `confirmedTools` — the loop's existing confirmation mechanism — is
 * per-conversation and never expires, which is far too weak here: one "yes"
 * would license unlimited taps for the life of the chat. A grant to act on the
 * user's behalf in other apps should look like a parking ticket, not a
 * driving licence.
 *
 * So a confirmation opens a session bounded three ways: **time**, because
 * intent goes stale; **action count**, because a loop that goes wrong should
 * run out rather than run on; and **app**, because agreeing to let Aura use
 * WhatsApp is not agreeing to let it use the banking app it switches to.
 */
@Singleton
class ScreenControlSession @Inject constructor() {

    data class State(
        val active: Boolean = false,
        val boundPackage: String = "",
        val expiresAtMs: Long = 0,
        val actionsRemaining: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Why an action cannot proceed, or null when it can. */
    sealed class Denial(val reason: String) {
        object NoSession : Denial("no active screen-control session")
        object Expired : Denial("the screen-control session has expired")
        object Exhausted : Denial("the screen-control session has used all its actions")
        class WrongApp(current: String, bound: String) :
            Denial("the foreground app changed from $bound to $current")
    }

    /**
     * Open a session bound to [packageName].
     *
     * Called only after a confirmation gate has been satisfied — this type
     * enforces the bounds, it does not decide whether to grant them.
     */
    fun open(packageName: String, now: Long = System.currentTimeMillis()) {
        _state.value = State(
            active = true,
            boundPackage = packageName,
            expiresAtMs = now + DURATION_MS,
            actionsRemaining = MAX_ACTIONS,
        )
    }

    fun close() {
        _state.value = State()
    }

    /**
     * Whether an action may proceed in [currentPackage], without consuming a
     * budget slot.
     *
     * Separate from [consume] so a caller can check before doing expensive
     * setup, and so a denial can be reported without silently spending an
     * action on a request that never ran.
     */
    fun check(currentPackage: String, now: Long = System.currentTimeMillis()): Denial? {
        val s = _state.value
        return when {
            !s.active -> Denial.NoSession
            now >= s.expiresAtMs -> Denial.Expired
            s.actionsRemaining <= 0 -> Denial.Exhausted
            // A blank current package means the window event has not arrived
            // yet; treat it as "still where we were" rather than denying a
            // legitimate action on a timing detail.
            currentPackage.isNotBlank() && currentPackage != s.boundPackage ->
                Denial.WrongApp(currentPackage, s.boundPackage)
            else -> null
        }
    }

    /**
     * Spend one action, or return the reason it could not be spent.
     *
     * Decremented BEFORE the action runs, not after. A gesture that times out
     * or throws has still driven the screen as far as the user is concerned,
     * and a budget that only counts successes is one an unlucky loop can
     * exhaust without ever decrementing.
     */
    fun consume(currentPackage: String, now: Long = System.currentTimeMillis()): Denial? {
        val denial = check(currentPackage, now)
        if (denial != null) {
            // Expiry and exhaustion are terminal; clear the session so the next
            // attempt re-gates cleanly rather than reporting the same denial.
            if (denial !is Denial.NoSession) close()
            return denial
        }
        _state.value = _state.value.let { it.copy(actionsRemaining = it.actionsRemaining - 1) }
        return null
    }

    companion object {
        /**
         * Five minutes. Long enough for a multi-step task, short enough that a
         * forgotten session is not a standing grant.
         */
        const val DURATION_MS = 5 * 60 * 1000L

        /**
         * Twenty-five actions. Enough for a real task — open an app, scroll,
         * tap through two screens, type, confirm — and few enough that a loop
         * which has misunderstood runs out while the user is still nearby.
         */
        const val MAX_ACTIONS = 25
    }
}
