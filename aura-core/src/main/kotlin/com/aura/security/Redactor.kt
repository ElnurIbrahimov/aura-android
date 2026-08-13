package com.aura.security

/**
 * Strips incidental personal data out of text Aura captured rather than was given.
 *
 * The privacy engineering inside this app is careful — the foreground app is
 * never persisted, notification contents never reach Room, retrieved content is
 * framed as untrusted before entering a prompt. All of that stops at the network
 * boundary: screen contents and notification text went to a third-party API in
 * plaintext the moment they entered a turn.
 *
 * **This deliberately does not run at the wire.** `ProviderRegistry.chat` is the
 * one place every call passes through and is exactly the wrong place for it:
 * redacting everything on the way out would strip the number from "call mum on
 * 0555 123 4567" and break the assistant to protect a number the user typed on
 * purpose. The distinction that matters is not *what the text contains* but *how
 * Aura came to have it*:
 *
 *  - **Captured in bulk, incidentally** — a screen read returns whatever happened
 *    to be on screen, a notification list returns whatever happened to arrive.
 *    Nobody chose to send any of it. Redact.
 *  - **Asked for specifically** — `contacts_search` returns the contact the user
 *    asked about, and a redacted answer to that question is not an answer.
 *    Do not redact.
 *
 * Only the second rule keeps the first from spreading until the app is useless,
 * which is why `RedactorScopeTest` asserts the negative case too.
 *
 * The masks keep their shape (`[phone]`, `[email]`) rather than blanking, so the
 * model can still reason about structure — "there is a phone number here" is
 * usually the part that matters, and the digits almost never are.
 */
object Redactor {

    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")

    /**
     * An unbroken run of 12+ digits: card numbers, IBAN bodies, account numbers.
     *
     * No leading `\b`, deliberately — an IBAN is `GB29` then twenty digits, and a
     * word boundary would refuse to start inside the token and leave the whole
     * account number in place.
     */
    private val LONG_DIGITS = Regex("""\d{12,}""")

    /**
     * *Candidates* for a phone number. Deliberately loose, because the decision
     * is made by counting digits in [scrub] rather than by the pattern.
     *
     * A regex tight enough to exclude dates, prices, times and version strings
     * while still catching `+994 55 123 45 67`, `(020) 7946-0958` and
     * `+1-800-555-0199` is unreadable and wrong in ways nobody can see. Counting
     * is legible and the reason is statable: a phone number has 9 to 15 digits,
     * an ISO date has 8, and almost nothing else on a screen has 9 or more with
     * only phone-shaped separators between them.
     */
    private val PHONE_CANDIDATE = Regex("""\+?\(?\d[\d\s().+-]{6,}\d""")

    private val PHONE_DIGITS = 9..15

    /**
     * Mask personal data in text that was captured rather than requested.
     *
     * Returns the input unchanged when nothing matches, so a caller can compare
     * identity to tell whether anything was found.
     *
     * Order is load-bearing. Email first: an address can contain a digit run
     * that a later rule would claim half of, leaving a fragment that is both
     * unreadable and still identifying. Long digit runs before phones: a
     * 16-digit card is inside the phone pattern's reach and is not a phone
     * number, and mislabelling it would be the kind of quiet corruption that
     * makes a model confidently wrong.
     */
    fun scrub(text: String): String {
        if (text.isBlank()) return text
        var out = EMAIL.replace(text, "[email]")
        out = LONG_DIGITS.replace(out, "[number]")
        out = PHONE_CANDIDATE.replace(out) { match ->
            val digits = match.value.count(Char::isDigit)
            // Not a phone number — a date, a price, a version, an order id. Put
            // it back exactly as it was.
            if (digits in PHONE_DIGITS) "[phone]" else match.value
        }
        return out
    }

    /** True when [scrub] would change [text]. For tests and for logging counts. */
    fun containsPersonalData(text: String): Boolean = scrub(text) != text
}
