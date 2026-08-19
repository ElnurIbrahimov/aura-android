package com.aura.ui.nav

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether a top-level navigation may restore the destination's saved back stack.
 *
 * Every top-level navigate in [NavGraph] used the standard bottom-bar recipe —
 * `popUpTo(start) { saveState = true }`, `launchSingleTop`, `restoreState = true` — which is
 * correct for the bottom bar and wrong for everything else that used it.
 *
 * `restoreState` restores the back-stack entry saved for the destination, and a restored
 * entry keeps the arguments it was created with. The destination is identified by its route
 * *pattern*, so `chat?draft=hello` and `chat?briefId=7` both resolve to the same saved
 * `chat` entry. Once Chat had been visited and a tab switched even once, every later
 * navigation carrying a draft or a brief id silently arrived without it: Home's ask field
 * dropped what was typed into it, and the morning-brief notification opened an empty chat.
 *
 * Both symptoms are one line, and both need a real device to notice — which is why they
 * lived through 3,419 unit tests.
 */
class TopLevelNavigationTest {

    @Test
    fun `a route carrying arguments starts fresh`() {
        assertFalse(shouldRestoreState("chat?draft=hello"), "the typed draft must not be dropped")
        assertFalse(shouldRestoreState("chat?briefId=7"), "the brief id must not be dropped")
    }

    @Test
    fun `a bare route restores what the user left there`() {
        // The bottom bar's whole purpose: come back to the tab as you left it.
        assertTrue(shouldRestoreState("chat"))
        assertTrue(shouldRestoreState("memory"))
    }

    @Test
    fun `an empty argument value still counts as arguments`() {
        // "chat?draft=" means the caller intends an empty draft, not "whatever was there".
        assertFalse(shouldRestoreState("chat?draft="))
    }
}
