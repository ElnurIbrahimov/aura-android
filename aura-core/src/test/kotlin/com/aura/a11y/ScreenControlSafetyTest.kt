package com.aura.a11y

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bounds on screen control.
 *
 * This is the most dangerous capability in the app, and the honest position is
 * that nothing here fully prevents a destructive tap. What these rules do is
 * bound the damage: a budget that runs out, a grant that expires, an app it
 * cannot follow the user out of, a set of packages it may never touch at all,
 * and a prompt in front of anything whose label reads like it is irreversible.
 */
class ScreenControlSafetyTest {

    private val now = 1_700_000_000_000L

    private fun snapshot(pkg: String = "com.whatsapp", password: Boolean = false) =
        UiSnapshot(1, pkg, "Main", 1080, 2400, emptyList(), 0, password)

    // ---- the rule everything else depends on -----------------------------

    @Test
    fun `Aura will not drive its own interface`() {
        // Without this the agent taps its own confirmation dialogs, and every
        // other gate in the system becomes a button it can press. There is no
        // configuration that turns this off.
        assertTrue(ScreenControlGuard.block("com.aura", snapshot("com.aura")) is ScreenControlGuard.Block.SelfDrive)
        assertTrue(
            ScreenControlGuard.block("com.aura.debug", snapshot("com.aura.debug"))
                is ScreenControlGuard.Block.SelfDrive,
        )
    }

    @Test
    fun `the system settings app is denied`() {
        // The accessibility toggle lives here: an agent that can reach it can
        // grant itself capabilities or disable its own kill switch.
        val blocked = ScreenControlGuard.block("com.android.settings", snapshot("com.android.settings"))
        assertTrue(blocked is ScreenControlGuard.Block.DeniedApp)
    }

    @Test
    fun `an ordinary app is not blocked`() {
        assertNull(ScreenControlGuard.block("com.whatsapp", snapshot()))
    }

    // ---- password handling -----------------------------------------------

    @Test
    fun `acting is refused while any password field is visible`() {
        // Not just when the target is one. A login screen is the worst place
        // for an agent to improvise, and being conservative costs one turn.
        val blocked = ScreenControlGuard.block("com.bank", snapshot("com.bank", password = true))
        assertTrue(blocked is ScreenControlGuard.Block.PasswordVisible)
    }

    @Test
    fun `typing into a password field is refused unconditionally`() {
        val blocked = ScreenControlGuard.block("com.x", snapshot(), targetIsPassword = true)
        assertTrue(blocked is ScreenControlGuard.Block.PasswordTarget)
    }

    // ---- the tripwire ----------------------------------------------------

    @Test
    fun `labels that mean something irreversible are flagged`() {
        listOf(
            "Delete account", "Remove photo", "Uninstall", "Erase all data",
            "Pay now", "Buy", "Complete purchase", "Checkout", "Place order",
            "Transfer funds", "Confirm", "Submit", "Log out", "Sign out",
            "Unsubscribe", "Deactivate", "Factory reset",
        ).forEach {
            assertTrue(ScreenControlGuard.looksDestructive(it), "not flagged: $it")
        }
    }

    @Test
    fun `ordinary labels are not flagged`() {
        // A tripwire that fires on everything trains the user to approve
        // without reading, which is worse than no tripwire.
        listOf(
            "Send message", "Back", "Search", "Settings", "Next", "Cancel",
            "Reply", "Share", "Add to cart", "Show more", "Refresh",
        ).forEach {
            assertTrue(!ScreenControlGuard.looksDestructive(it), "false positive: $it")
        }
    }

    @Test
    fun `the tripwire matches whole words only`() {
        // "resettings" and "buyer" are not "reset" and "buy". Substring
        // matching here would fire constantly and defeat the purpose.
        assertTrue(!ScreenControlGuard.looksDestructive("Resettings menu"))
        assertTrue(!ScreenControlGuard.looksDestructive("Contact the buyer"))
        assertTrue(ScreenControlGuard.looksDestructive("Reset password"))
    }

    // ---- session bounds --------------------------------------------------

    @Test
    fun `no action is allowed without a session`() {
        val session = ScreenControlSession()
        assertTrue(session.check("com.whatsapp", now) is ScreenControlSession.Denial.NoSession)
    }

    @Test
    fun `a session expires on time`() {
        val session = ScreenControlSession()
        session.open("com.whatsapp", now)
        assertNull(session.check("com.whatsapp", now + ScreenControlSession.DURATION_MS - 1))
        assertTrue(
            session.check("com.whatsapp", now + ScreenControlSession.DURATION_MS)
                is ScreenControlSession.Denial.Expired,
        )
    }

    @Test
    fun `a session runs out of actions`() {
        val session = ScreenControlSession()
        session.open("com.whatsapp", now)
        repeat(ScreenControlSession.MAX_ACTIONS) {
            assertNull(session.consume("com.whatsapp", now), "denied at action ${it + 1}")
        }
        assertTrue(
            session.consume("com.whatsapp", now) is ScreenControlSession.Denial.Exhausted,
            "the budget did not run out",
        )
    }

    @Test
    fun `a session does not follow the user into another app`() {
        // Agreeing to let Aura use WhatsApp is not agreeing to let it use the
        // banking app it switches to.
        val session = ScreenControlSession()
        session.open("com.whatsapp", now)
        val denial = session.check("com.bank", now)
        assertTrue(denial is ScreenControlSession.Denial.WrongApp, "followed the user out of the bound app")
    }

    @Test
    fun `a blank foreground package does not deny`() {
        // The window event may not have arrived yet; denying on a timing detail
        // would make the feature flaky in a way users read as broken.
        val session = ScreenControlSession()
        session.open("com.whatsapp", now)
        assertNull(session.check("", now))
    }

    @Test
    fun `an action is spent before it runs, not after`() {
        // A gesture that times out has still driven the screen as far as the
        // user is concerned. A budget counting only successes is one an
        // unlucky loop can exhaust without ever decrementing.
        val session = ScreenControlSession()
        session.open("com.whatsapp", now)
        val before = session.state.value.actionsRemaining
        session.consume("com.whatsapp", now)
        assertEquals(before - 1, session.state.value.actionsRemaining)
    }

    @Test
    fun `expiry closes the session so the next attempt re-gates`() {
        // Otherwise every later action reports the same stale denial and the
        // user never gets asked again.
        val session = ScreenControlSession()
        session.open("com.whatsapp", now)
        session.consume("com.whatsapp", now + ScreenControlSession.DURATION_MS)
        assertTrue(!session.state.value.active, "an expired session must not stay active")
        assertTrue(session.check("com.whatsapp", now) is ScreenControlSession.Denial.NoSession)
    }

    @Test
    fun `exhaustion closes the session`() {
        val session = ScreenControlSession()
        session.open("com.whatsapp", now)
        repeat(ScreenControlSession.MAX_ACTIONS) { session.consume("com.whatsapp", now) }
        session.consume("com.whatsapp", now)
        assertTrue(!session.state.value.active)
    }

    @Test
    fun `close revokes immediately`() {
        // The kill switch. It has to take effect on the very next check, not
        // at the next expiry.
        val session = ScreenControlSession()
        session.open("com.whatsapp", now)
        session.close()
        assertTrue(session.check("com.whatsapp", now) is ScreenControlSession.Denial.NoSession)
    }

    // ---- composition -----------------------------------------------------

    @Test
    fun `a user denylist adds to the built-in one without replacing it`() {
        // A user-configured list must never be able to un-deny Aura's own
        // package, which is what a replacing implementation would allow.
        val denied = ScreenControlGuard.deniedPackages(setOf("com.bank"))
        assertTrue("com.bank" in denied)
        assertTrue("com.aura" in denied, "the built-in denylist was replaced rather than extended")
    }
}
