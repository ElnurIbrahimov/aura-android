package com.aura.security

import com.aura.agent.sourceDir
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Where the redactor runs, and — more importantly — where it must not.
 *
 * The rule is not *what the text contains* but *how Aura came to have it*. A
 * screen read returns whatever happened to be on screen; a notification list
 * returns whatever happened to arrive; nobody chose to send any of it. But
 * `contacts_search` returns the contact the user asked about, and a redacted
 * answer to that question is not an answer — "call mum" would find her and then
 * hand back `[phone]`.
 *
 * Both halves are asserted because only the second stops the first from
 * spreading until the assistant cannot answer anything. A redactor that has
 * quietly reached the whole app is the most likely way this ends up wrong, and
 * it would look like working.
 */
class RedactorScopeTest {

    private fun source(relative: String): File =
        File(sourceDir("src/main/kotlin/com/aura"), relative)
            .also { check(it.isFile) { "$relative not found at ${it.absolutePath} — this test is reading the wrong tree" } }

    /** Captured in bulk, incidentally. Everything in them is unchosen. */
    private val mustRedact = listOf(
        "a11y/UiTraversal.kt",
        "tools/NotificationListTool.kt",
    )

    /**
     * Asked for specifically, or carrying no text to redact.
     *
     * `ContactsSearchTool` is the load-bearing entry: the user named the contact,
     * and masking the answer defeats the tool. `CaptureScreenTool` returns a
     * base64 JPEG — there is no text for a regex to touch, and pretending
     * otherwise would be a claim the code cannot honour. `NotificationsTool`
     * *posts* a notification rather than reading one, so nothing captured passes
     * through it at all.
     */
    private val mustNotRedact = listOf(
        "tools/ContactsSearchTool.kt",
        "tools/CaptureScreenTool.kt",
        "tools/NotificationsTool.kt",
    )

    @Test
    fun `every bulk-capture path scrubs before the text can reach a model`() {
        val unscrubbed = mustRedact.filterNot { "Redactor.scrub" in source(it).readText() }

        assertTrue(
            unscrubbed.isEmpty(),
            "these capture text nobody chose to send and hand it to a third-party API unmasked:\n" +
                unscrubbed.joinToString("\n") { "  - $it" },
        )
    }

    @Test
    fun `the redactor has not spread to paths the user asked for`() {
        val overreached = mustNotRedact.filter { "Redactor" in source(it).readText() }

        assertTrue(
            overreached.isEmpty(),
            "the redactor reached a path where the user asked for the data by name:\n" +
                overreached.joinToString("\n") { "  - $it" } +
                "\nRedacting the answer to a question the user asked is not privacy, it is a broken tool. " +
                "If one of these genuinely became a bulk-capture path, move it to `mustRedact` and say why.",
        )
    }

    /**
     * The a11y hook keeps its own field-level rules as well as the shared ones.
     * They answer different questions — *this app says this field is a password*
     * versus *this looks like a phone number* — and the first cannot be derived
     * from the text.
     */
    @Test
    fun `screen redaction still honours the password flag and secret hints`() {
        val traversal = source("a11y/UiTraversal.kt").readText()

        assertTrue("node.password" in traversal, "the authoritative password flag is no longer checked")
        assertTrue("SECRET_HINT" in traversal, "the id-pattern hint for unmarked one-time-code fields is gone")
    }
}
