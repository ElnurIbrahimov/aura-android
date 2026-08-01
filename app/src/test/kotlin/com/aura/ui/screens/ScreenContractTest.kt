package com.aura.ui.screens

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Source-scan contract tests for Screen composables.
 *
 * These tests don't instantiate Compose UI (that would require
 * Robolectric or instrumentation tests). Instead they scan the
 * source tree to verify structural contracts that are easy to
 * break during refactoring.
 */
class ScreenContractTest {

    private val projectRoot = File(System.getProperty("user.dir"))
    private val screenDir = File(projectRoot, "src/main/kotlin/com/aura/ui/screens")
    private val navGraphFile = File(projectRoot, "src/main/kotlin/com/aura/ui/nav/NavGraph.kt")

    @Test
    fun `every Screen file defines at least one Composable function`() {
        val violations = mutableListOf<String>()
        screenDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith("Screen.kt") }
            .forEach { file ->
                val content = file.readText()
                if (!content.contains("@Composable")) {
                    violations.add(file.name)
                }
            }
        assertTrue(violations.isEmpty(), "Screen files without @Composable: $violations")
    }

    @Test
    fun `no duplicate composable routes in NavGraph`() {
        val content = navGraphFile.readText()
        val routes = Regex("""composable\("([^"]+)"""").findAll(content)
            .map { it.groupValues[1] }
            .toList()
        val duplicates = routes.groupingBy { it }.eachCount().filter { it.value > 1 }
        assertTrue(duplicates.isEmpty(), "Duplicate routes: $duplicates")
    }

    @Test
    fun `every navigate target in NavGraph has a composable registration`() {
        val content = navGraphFile.readText()
        // Extract all composable route registrations (including route = "..." form)
        val registeredRoutes = Regex("""composable\(\s*"([^"]+)"""").findAll(content)
            .map { it.groupValues[1] }
            .toMutableSet()
        // Also extract route = "..." form
        Regex("""route\s*=\s*"([^"]+)"""").findAll(content)
            .forEach { registeredRoutes.add(it.groupValues[1]) }
        // Extract all navigate("...") calls
        val navTargets = Regex("""navigate\("([^"]+)"""").findAll(content)
            .map { it.groupValues[1] }
            .toList()
        // Check each navigate target — match by prefix (route may have ?param={paramId})
        val missing = navTargets.filter { target ->
            val base = target.substringBefore("?").substringBefore("/")
            // Check if any registered route starts with the base
            !registeredRoutes.any { it.startsWith(base) }
        }
        assertTrue(missing.isEmpty(),
            "navigate() targets without composable(): $missing\nRegistered: $registeredRoutes")
    }

    @Test
    fun `NavGraph imports or fully-qualifies every Screen composable it references`() {
        val content = navGraphFile.readText()
        // Extract all import statements
        val imports = Regex("""import\s+([\w.]+)""").findAll(content)
            .map { it.value.substringAfterLast(".") }
            .toSet()
        // Extract all Screen/Route references in composable bodies
        val screenRefs = Regex("""\b(\w+Screen)\s*\(""").findAll(content)
            .map { it.groupValues[1] }
            .toSet()
        val routeRefs = Regex("""\b(\w+Route)\s*\(""").findAll(content)
            .map { it.groupValues[1] }
            .toSet()
        val allRefs = screenRefs + routeRefs
        // Check: each ref is either imported, FQN-qualified, or defined in NavGraph
        val missing = allRefs.filter { ref ->
            ref != "Composable" && ref !in imports &&
            !content.contains("fun $ref(") &&
            // Check if it's used with a FQN (e.g. com.aura.ui.screens.IdentityEditorScreen)
            !content.contains("com.aura.ui.screens.$ref(") &&
            !content.contains("com.aura.ui.evolution.$ref(") &&
            !content.contains("com.aura.ui.screens.creative.$ref(") &&
            !content.contains("com.aura.ui.screens.council.$ref(") &&
            !content.contains("com.aura.ui.screens.home.$ref(") &&
            !content.contains("com.aura.ui.screens.chat.$ref(")
        }
        assertTrue(missing.isEmpty(),
            "Screen/Route composables referenced in NavGraph but not imported or FQN-qualified: $missing")
    }
}

/**
 * Contract test: Home card navigation wiring.
 */
class HomeNavigationContractTest {

    private val projectRoot = File(System.getProperty("user.dir"))
    private val navGraphFile = File(projectRoot, "src/main/kotlin/com/aura/ui/nav/NavGraph.kt")
    private val homeContentFile = File(projectRoot, "src/main/kotlin/com/aura/ui/screens/home/HomeContent.kt")

    @Test
    fun `HomeContent defines at least 10 onOpen callbacks for feature navigation`() {
        val homeContent = homeContentFile.readText()
        val callbacks = Regex("""onOpen(\w+)\s*:""").findAll(homeContent)
            .map { it.groupValues[1] }
            .toList()
        assertTrue(callbacks.size >= 10,
            "Expected at least 10 onOpen callbacks in HomeContent, found ${callbacks.size}: $callbacks")
    }

    @Test
    fun `NavGraph has at least 20 composable routes registered`() {
        val content = navGraphFile.readText()
        val routes = Regex("""composable\(\s*"([^"]+)"""").findAll(content)
            .map { it.groupValues[1] }
            .toList()
        // Also count route = "..." form
        val routeFormRoutes = Regex("""route\s*=\s*"([^"]+)"""").findAll(content)
            .map { it.groupValues[1] }
            .toList()
        val allRoutes = routes + routeFormRoutes
        assertTrue(allRoutes.size >= 20,
            "Expected at least 20 composable routes in NavGraph, found ${allRoutes.size}")
    }
}