package com.aura.security

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a redactor gets wrong is usually over-reach, not under-reach.
 *
 * A pattern loose enough to catch every phone number also catches prices, dates,
 * order numbers, step counts and version strings — and a screen read where every
 * number has become `[phone]` is worse than no redaction, because the model now
 * reasons confidently about text that has been quietly corrupted. Roughly half
 * the cases below are things that must survive untouched.
 */
class RedactorTest {

    private fun assertScrubbed(input: String, expected: String) =
        assertEquals(expected, Redactor.scrub(input))

    private fun assertUntouched(input: String) =
        assertEquals(input, Redactor.scrub(input), "\"$input\" was redacted and should not have been")

    @Test
    fun `phone numbers in the shapes people write them`() {
        assertScrubbed("call me on +994 55 123 45 67", "call me on [phone]")
        assertScrubbed("ring 0555 123 4567 later", "ring [phone] later")
        assertScrubbed("(020) 7946-0958 is the office", "[phone] is the office")
        assertScrubbed("+1-800-555-0199", "[phone]")
    }

    @Test
    fun `email addresses`() {
        assertScrubbed("from elnur.ibrahimov@example.com re: invoice", "from [email] re: invoice")
        assertScrubbed("a+tag@sub.domain.co.uk", "[email]")
    }

    @Test
    fun `long digit runs are card and account numbers`() {
        assertScrubbed("card 4111111111111111 declined", "card [number] declined")
        // The country prefix survives and the account number does not, because
        // the rule has no leading word boundary — an IBAN's digits start inside
        // the token, and `\b` would refuse to enter it and leave the lot.
        assertScrubbed("IBAN GB29000060161331926819", "IBAN GB[number]")
    }

    /**
     * The half that keeps this from spreading until the app is useless. Every
     * one of these is something a screen read legitimately contains and a model
     * legitimately needs.
     */
    @Test
    fun `ordinary numbers on a screen survive`() {
        assertUntouched("Total: 149.99 AZN")
        assertUntouched("Meeting at 14:30 on 2026-08-13")
        assertUntouched("12,458 steps today")
        assertUntouched("Battery 87%")
        assertUntouched("v0.66.0 (versionCode 81)")
        assertUntouched("Order #90210 shipped")
        assertUntouched("3 of 12 tasks done")
        assertUntouched("Scene 4 scored 7/10")
    }

    @Test
    fun `text with no personal data is returned unchanged and reports so`() {
        val plain = "Reply from Sam about the meeting"
        assertEquals(plain, Redactor.scrub(plain))
        assertFalse(Redactor.containsPersonalData(plain))
        assertTrue(Redactor.containsPersonalData("mail me at sam@example.com"))
    }

    @Test
    fun `an email containing digits is not half-eaten by the phone rule`() {
        // Ordering matters: the phone pattern would otherwise claim part of the
        // address and leave an unreadable, still-identifying fragment.
        assertScrubbed("sam1994558822@example.com", "[email]")
    }

    @Test
    fun `blank and empty input are safe`() {
        assertEquals("", Redactor.scrub(""))
        assertEquals("   ", Redactor.scrub("   "))
    }

    @Test
    fun `several kinds in one line are all masked`() {
        assertScrubbed(
            "Sam (sam@example.com, +994 55 123 45 67) paid with 4111111111111111",
            "Sam ([email], [phone]) paid with [number]",
        )
    }
}
