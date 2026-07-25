package com.aura.ui.nav

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * UI/UX_AUDIT A1 P0 finding: "Verify every navigate("...")
 * has matching composable("...") — subagent didn't
 * complete."
 *
 * This test scans the app/ source for navigate("...") calls
 * and composable("...") registrations, then asserts every
 * navigate target has a matching composable. Routes with
 * template variables are matched against parameterized
 * composable registrations.
 *
 * The `knownBugs` ratchet below is currently empty and should stay that
 * way. It previously listed `chat?convId=...` and `chat?brief=...` as
 * unregistered; both are in fact covered by the parameterized
 * `composable("chat?convId={convId}&draft={draft}&brief={brief}&...")`
 * registration in NavGraph, since query parameters are optional.
 *
 * The bug class this catches: a screen navigates to a
 * route that was never registered. The user clicks a UI
 * element expecting navigation, and the app either
 * crashes with "navigation destination X is unknown" or
 * silently fails to navigate.
 */
class NavigationReachabilityTest {

    @Test
    fun `every navigate() target has a matching composable() route`() {
        // Find the app/src/main directory. Gradle sets the test working
        // directory to the module dir, so "src/main" resolves when run as
        // :app:test; "app/src/main" covers a repo-root working directory.
        //
        // Both are relative — no absolute machine-specific paths. An
        // earlier version listed "D:/aura-android-clean/app/src/main" and
        // a hardcoded CI runner path, then silently `return`ed when none
        // matched. That made the test pass without asserting anything on
        // any machine whose checkout lived elsewhere. Resolution failure
        // is now a hard failure: a test that cannot find its inputs has
        // not passed.
        val appMain = listOf("src/main", "app/src/main")
            .map { File(it) }
            .firstOrNull { it.isDirectory }
            ?: error(
                "Could not locate app/src/main from ${File(".").absolutePath}. " +
                    "This test scans production sources and cannot run without them.",
            )

        val navigateRoutes = mutableSetOf<String>()
        val composableRoutes = mutableSetOf<String>()

        // Scan every .kt file
        appMain.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val text = file.readText()
                // Find navigate("...") — single-arg, single-line.
                // The regex uses a single-quoted string for the
                // pattern so the backslash-dollar is literal
                // (Kotlin's regex engine treats the string as
                // already-escaped via the way raw $ is matched).
                val navRegex = Regex("""navigate\(\s*"([^"]+)"""")
                navRegex.findAll(text).forEach { match ->
                    navigateRoutes.add(match.groupValues[1])
                }
                // Find composable("...") — single-arg
                val compRegex = Regex("""composable\(\s*"([^"]+)"""")
                compRegex.findAll(text).forEach { match ->
                    composableRoutes.add(match.groupValues[1])
                }
                // Find composable(route = "...") — named arg
                val compRouteRegex = Regex("""composable\(\s*route\s*=\s*"([^"]+)"""")
                compRouteRegex.findAll(text).forEach { match ->
                    composableRoutes.add(match.groupValues[1])
                }
            }

        // Match navigate routes to composable routes.
        // A navigate target like "creative/$id" matches
        // composable("creative/{projectId}") after
        // normalizing $variable to {variable}.
        val unmatched = mutableListOf<String>()
        for (nav in navigateRoutes) {
            val baseRoute = nav.substringBefore('?')
            val normalized = baseRoute
                .replace(Regex("[$]\\{?\\w+\\}?"), "VAR")
            val match = composableRoutes.any { c ->
                val cBase = c.substringBefore('?')
                val cNorm = cBase.replace(Regex("\\{[^}]+\\}"), "VAR")
                normalized == cNorm || cNorm.startsWith("$normalized/")
            }
            if (!match) unmatched.add(nav)
        }

        // Known bugs from UI/UX_AUDIT A1. The audit was
        // uncertain — manual verification showed all
        // navigate() targets DO have matching composable()
        // routes (e.g. "chat?convId=$id" matches
        // composable("chat?convId={convId}&...&brief={brief}&...")
        // because query parameters are optional). Keep the
        // list empty unless a real bug is found.
        val knownBugs = setOf<String>()
        val realUnmatched = unmatched - knownBugs

        assertTrue(realUnmatched.isEmpty(),
            "Routes navigated but NOT defined as composable: $realUnmatched. " +
            "Either add a composable() registration or update knownBugs if the route is intentionally dead.")
    }
}
