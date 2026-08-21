package com.aura.testing

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * Every navigation callback the home screen declares is actually passed down.
 *
 * The Library row shipped dead. `onOpenLibrary` was declared at all four levels
 * — `NavGraph` → `HomeRoute` → `homeResolvedItems` → `HomeSecondaryActions` —
 * and forwarded at only two of them, so the row fell back to the `= {}` default
 * and tapping it did nothing at all. No crash, nothing in logcat, and the
 * button looked exactly like the ones that worked.
 *
 * That default is what made it silent. Removing it would turn the next one into
 * a compile error, which is the better fix in the abstract, but `HomeContent`
 * is also built by `HomeContentTest` and the debug UI catalog, and both rely on
 * passing only the handful of callbacks they care about. Making twenty-two
 * arguments mandatory at every one of those call sites costs more than it
 * saves.
 *
 * So the contract is checked here instead: a parameter a composable accepts,
 * and its caller does not pass, is a hole. That is a true statement about
 * these three functions specifically — every callback they take exists to be
 * threaded through — and it is not true generally, which is why this test names
 * its hops rather than scanning for a pattern.
 */
class HomeNavigationIsWiredTest {

    private val home = sourceDir("src/main/kotlin/com/aura/ui/screens/home")

    private fun read(name: String) = File(home, name)
        .also { check(it.isFile) { "expected ${it.absolutePath}; a scan that cannot find its source proves nothing" } }
        .readText()

    /** The text between the parentheses of `fun <name>(`, brackets matched rather than regexed. */
    private fun signature(source: String, function: String): String =
        balanced(source, source.indexOf("fun $function(") + "fun $function(".length)

    /** The text between the parentheses of the first `<Name>(` call. */
    private fun callSite(source: String, call: String): String =
        balanced(source, source.indexOf("$call(") + "$call(".length)

    private fun balanced(source: String, from: Int): String {
        check(from > 0) { "could not locate the opening parenthesis" }
        var depth = 0
        for (i in from until source.length) {
            when (source[i]) {
                '(' -> depth++
                ')' -> if (depth == 0) return source.substring(from, i) else depth--
            }
        }
        error("unbalanced parentheses from offset $from")
    }

    private fun declared(text: String): Set<String> =
        Regex("""\b(on[A-Z]\w*)\s*:""").findAll(text)
            .map { it.groupValues[1] }
            .distinct()
            .requireNonEmpty("declared callbacks")
            .toSet()

    private fun passed(text: String): Set<String> =
        Regex("""\b(on[A-Z]\w*)\s*=""").findAll(text)
            .map { it.groupValues[1] }
            .distinct()
            .requireNonEmpty("passed callbacks")
            .toSet()

    private fun assertNoneDropped(
        accepts: String,
        acceptedBy: String,
        callerSource: String,
        call: String,
    ) {
        val takes = declared(signature(read(acceptedBy), accepts))
        val gets = passed(callSite(read(callerSource), call))
        assertEquals(
            emptySet<String>(),
            takes - gets,
            "$call takes these callbacks and its caller does not pass them, so they silently default to {} " +
                "and whatever they open becomes unreachable",
        )
    }

    @Test
    fun `HomeRoute passes on every callback HomeContent takes`() {
        assertNoneDropped(
            accepts = "HomeContent",
            acceptedBy = "HomeContent.kt",
            callerSource = "HomeRoute.kt",
            call = "HomeContent",
        )
    }

    @Test
    fun `the resolved home list passes on every callback the action grid takes`() {
        assertNoneDropped(
            accepts = "HomeSecondaryActions",
            acceptedBy = "HomeSecondaryActions.kt",
            callerSource = "HomeContent.kt",
            call = "HomeSecondaryActions",
        )
    }
}
