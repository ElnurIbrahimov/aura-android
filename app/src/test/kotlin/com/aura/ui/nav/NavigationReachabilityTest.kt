package com.aura.ui.nav

import com.aura.testing.requireNonEmpty
import com.aura.testing.sourceDir
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * A route the user can reach must be registered as a `composable`. Reaching an
 * unregistered one throws `IllegalArgumentException: navigation destination is
 * unknown` at the tap, on a device, in whichever screen nobody opened during
 * review.
 *
 * The version this replaced had two halves and neither could fail.
 *
 * The string half compared `navigate("literal")` targets against
 * `composable("literal")` registrations, then subtracted a `knownBugs` set. In
 * this codebase there are exactly three string-literal navigate targets that do
 * not resolve — `"creative/"`, `"evolution/rollback/"`, `"council?convId="`, all
 * three written as `"prefix" + id` — and all three were in `knownBugs`. The
 * half therefore subtracted its entire output from itself. Concatenation is now
 * resolved instead of excused: a literal ending in `/` or `=` is treated as a
 * prefix and matched against registered bases, which lets the allowlist be
 * deleted rather than curated.
 *
 * The typed half compared constant *names* — `Route:Council` against
 * `Route:Council` — which only ever proved that the same constant appeared on
 * both sides of the file. It could not see that `TopLevelRoute.Tasks.route` and
 * `Route.Tasks.path` are different constants holding the same `"tasks"`, nor
 * that `composable(TopLevelRoute.Home.route)` registers a path at all, because
 * `TopLevelRoute` was not in any of its regexes. Both sides are now resolved to
 * the literal path string declared in `AuraBottomNavigation.kt` and compared as
 * paths.
 *
 * The second test is the one with real reach: every route constant declared in
 * `Route` or `TopLevelRoute` must be registered. Every destination in this app
 * is either one of those constants or a literal in a `navigate(` call, and the
 * constant side is fully resolvable — which matters because several call sites
 * pass a local `val route` or a lambda parameter that no regex can follow.
 */
class NavigationReachabilityTest {

    private val appMain = sourceDir("src/main")

    private fun kotlinSources(): List<File> = appMain.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .requireNonEmpty("Kotlin sources under app/src/main")

    /** `data object History : Route("history")` -> History to "history". */
    private val routeConstant = Regex("""data object (\w+)\s*:\s*Route\(\s*"([^"]+)"""")

    /** `data object Chat : TopLevelRoute(` on one line, `"chat",` on the next. */
    private val topLevelConstant = Regex("""data object (\w+)\s*:\s*TopLevelRoute\(\s*"([^"]+)"""")

    private val navLiteral = Regex("""navigate\(\s*"([^"]+)"""")
    private val composableLiteral = Regex("""composable\(\s*(?:route\s*=\s*)?"([^"]+)"""")
    private val composableRoute = Regex("""composable\(\s*(?:route\s*=\s*)?Route\.(\w+)\.path""")
    private val composableTopLevel = Regex("""composable\(\s*(?:route\s*=\s*)?TopLevelRoute\.(\w+)\.route""")

    private fun constantPaths(pattern: Regex, what: String): Map<String, String> =
        kotlinSources()
            .flatMap { file ->
                pattern.findAll(file.readText()).map { it.groupValues[1] to it.groupValues[2] }.toList()
            }
            .requireNonEmpty(what)
            .toMap()

    /**
     * The path a registration or a navigation is really about, with query
     * arguments dropped. Navigation Compose treats query parameters as optional,
     * so `composable("chat?convId={convId}&draft={draft}")` serves a plain
     * `navigate("chat")` and vice versa; comparing on the base is what makes
     * `TopLevelRoute.Chat.route` and that registration the same destination.
     */
    private fun base(route: String): String = route.substringBefore('?')

    private fun registeredBases(): Set<String> {
        val routePaths = constantPaths(routeConstant, "Route constants")
        val topLevelPaths = constantPaths(topLevelConstant, "TopLevelRoute constants")

        return kotlinSources().flatMap { file ->
            val text = file.readText()
            composableLiteral.findAll(text).map { base(it.groupValues[1]) }.toList() +
                composableRoute.findAll(text)
                    .mapNotNull { m -> routePaths[m.groupValues[1]]?.let { base(it) } }.toList() +
                composableTopLevel.findAll(text)
                    .mapNotNull { m -> topLevelPaths[m.groupValues[1]]?.let { base(it) } }.toList()
        }.requireNonEmpty("composable() route registrations").toSet()
    }

    /**
     * True when [target] names a destination that is registered. A target ending
     * in `/` or `=` came from `navigate("prefix" + id)`, so it is a prefix of the
     * real route and the id it is missing is exactly the `{arg}` the registration
     * declares — matching on the prefix is what resolves the three targets the
     * old allowlist excused instead.
     */
    private fun isRegistered(target: String, registered: Set<String>): Boolean {
        val path = base(target)
        if (path in registered) return true
        return (target.endsWith("/") || target.endsWith("=")) && registered.any { it.startsWith(path) }
    }

    @Test
    fun `every navigate target resolves to a registered composable`() {
        val registered = registeredBases()
        val targets = kotlinSources()
            .flatMap { file -> navLiteral.findAll(file.readText()).map { it.groupValues[1] }.toList() }
            .requireNonEmpty("navigate(\"...\") targets")
            .toSet()

        val unresolved = targets.filterNot { isRegistered(it, registered) }.sorted()

        assertEquals(
            emptyList<String>(), unresolved,
            "These routes are navigated to but nothing registers them: $unresolved. " +
                "Reaching one throws IllegalArgumentException at the tap, on a device. " +
                "Registered bases: ${registered.sorted().joinToString()}",
        )
    }

    @Test
    fun `every declared route constant is registered as a composable`() {
        val registered = registeredBases()
        val declared =
            constantPaths(routeConstant, "Route constants").map { "Route.${it.key}" to it.value } +
                constantPaths(topLevelConstant, "TopLevelRoute constants")
                    .map { "TopLevelRoute.${it.key}" to it.value }

        val unregistered = declared
            .filterNot { (_, path) -> base(path) in registered }
            .map { (name, path) -> "$name -> \"$path\"" }

        assertEquals(
            emptyList<String>(), unregistered,
            "These route constants exist but no composable() registers them: $unregistered. " +
                "A constant naming no destination is either dead code or a crash waiting for the " +
                "first tap that reaches it — and unlike a string literal, nothing about it looks wrong.",
        )
    }
}
