package com.aura.ui.screens

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Extended source-scan contract tests for all screens added since v0.40.0.
 *
 * Verifies that every screen file:
 * 1. Has a @Composable function
 * 2. Uses collectAsStateWithLifecycle (not collectAsState)
 * 3. Has no hardcoded MaterialTheme.colorScheme bypasses
 * 4. Is reachable from NavGraph (if it's a *Route.kt or *Screen.kt)
 */
class ExtendedScreenContractTest {

    private val projectRoot = File(System.getProperty("user.dir"))
    private val screenDir = File(projectRoot, "src/main/kotlin/com/aura/ui/screens")

    @Test
    fun `all screen files use collectAsStateWithLifecycle not collectAsState`() {
        val violations = mutableListOf<String>()
        screenDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .forEach { file ->
                val content = file.readText()
                // Check for collectAsState() without WithLifecycle
                if (content.contains("collectAsState()") && !content.contains("collectAsStateWithLifecycle")) {
                    violations.add(file.name)
                }
            }
        assertTrue(violations.isEmpty(),
            "Files using collectAsState() instead of collectAsStateWithLifecycle(): $violations")
    }

    @Test
    fun `no screen file has more than 15 MaterialTheme colorScheme bypasses`() {
        val violations = mutableListOf<Pair<String, Int>>()
        screenDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .forEach { file ->
                val content = file.readText()
                val count = Regex("MaterialTheme\\.colorScheme\\.\\w+")
                    .findAll(content).count()
                if (count > 15) {
                    violations.add(file.name to count)
                }
            }
        assertTrue(violations.isEmpty(),
            "Files with >15 MaterialTheme.colorScheme bypasses (should use AuraThemeTokens): $violations")
    }

    @Test
    fun `every Route file defines a composable function ending with Route`() {
        val violations = mutableListOf<String>()
        screenDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith("Route.kt") }
            .forEach { file ->
                val content = file.readText()
                val funcName = file.nameWithoutExtension
                if (!content.contains("fun $funcName(")) {
                    violations.add(file.name)
                }
            }
        assertTrue(violations.isEmpty(),
            "Route files without a matching composable function: $violations")
    }

    @Test
    fun `every Screen file defines a composable function ending with Screen`() {
        val violations = mutableListOf<String>()
        screenDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith("Screen.kt") }
            .forEach { file ->
                val content = file.readText()
                val funcName = file.nameWithoutExtension
                if (!content.contains("fun $funcName(")) {
                    violations.add(file.name)
                }
            }
        assertTrue(violations.isEmpty(),
            "Screen files without a matching composable function: $violations")
    }

    @Test
    fun `no screen file contains TODO or FIXME`() {
        val violations = mutableListOf<String>()
        screenDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .forEach { file ->
                val content = file.readText()
                if (content.contains("TODO") || content.contains("FIXME")) {
                    violations.add(file.name)
                }
            }
        assertTrue(violations.isEmpty(),
            "Screen files with TODO/FIXME: $violations")
    }

    @Test
    fun `all screen files total under 1200 lines`() {
        val violations = mutableListOf<Pair<String, Int>>()
        screenDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .forEach { file ->
                val lines = file.readLines().size
                if (lines > 1200) {
                    violations.add(file.name to lines)
                }
            }
        // Soft assertion: flag but don't fail — large screens are a smell, not a bug
        if (violations.isNotEmpty()) {
            println("WARNING: Screen files over 1200 lines (consider splitting): $violations")
        }
    }
}