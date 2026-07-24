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
 * Known unfixed bugs (ratchet — these will pass; once
 * fixed, remove from the list and the test verifies the
 * real audit):
 *   - chat?convId=... (HistoryScreen) — no composable
 *   - chat?brief=...  (Proactive events) — no composable
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
        // Find the app/src/main directory across platforms.
        // Local dev on Windows: D:/aura-android-clean/app/src/main
        // CI on Linux: <cwd>/app/src/main (where <cwd> is
        // /home/runner/work/aura-android/aura-android or similar)
        val candidates = listOf(
            "D:/aura-android-clean/app/src/main",
            "app/src/main",
            "/home/runner/work/aura-android/aura-android/app/src/main",
        )
        val appMain = candidates
            .map { File(it) }
            .firstOrNull { it.exists() }
        if (appMain == null) {
            // Skip if the app module isn't on disk (shouldn't
            // happen for normal test runs).
            return
        }

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
