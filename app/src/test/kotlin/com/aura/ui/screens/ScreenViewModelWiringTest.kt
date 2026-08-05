package com.aura.ui.screens

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Screen → ViewModel wiring contract test.
 *
 * Every screen in ui/screens that collects state from a ViewModel must
 * reference it by name (hiltViewModel / viewModel() call). A screen that
 * renders static content without a VM is fine (empty states, charts);
 * a screen that USES a VM but imports it under a wrong name, or a screen
 * whose VM was deleted, breaks here.
 *
 * Also verifies no screen references a ViewModel class that no longer
 * exists (dead import — the compiler would catch it, but this test
 * documents the wiring for humans and catches it before a build).
 */
class ScreenViewModelWiringTest {

    private val projectRoot = File(System.getProperty("user.dir"))
    private val screenDir = File(projectRoot, "src/main/kotlin/com/aura/ui/screens")
    private val vmDir = File(projectRoot, "src/main/kotlin/com/aura/ui/viewmodel")
    private val settingsVmDir = File(projectRoot, "src/main/kotlin/com/aura/ui/settings")
    private val evolutionVmDir = File(projectRoot, "src/main/kotlin/com/aura/ui/evolution")

    @Test
    fun `every ViewModel referenced by a screen still exists`() {
        val allVmNames = buildSet {
            for (dir in listOf(
                vmDir,
                settingsVmDir,
                evolutionVmDir,
                File(projectRoot, "src/main/kotlin/com/aura/ui/voice"),
                File(projectRoot, "src/main/kotlin/com/aura/ui/screens"),
            )) {
                if (!dir.exists()) continue
                dir.walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".kt") }
                    .forEach { file ->
                        file.readText()
                            .split("\n")
                            .filter { it.contains("class ") && it.contains("ViewModel") }
                            .forEach { line ->
                                val name = Regex("class (\\w+ViewModel)")
                                    .find(line)?.groupValues?.get(1)
                                if (name != null) add(name)
                            }
                    }
            }
        }

        val brokenRefs = mutableListOf<String>()
        screenDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .forEach { file ->
                val content = file.readText()
                // Every capitalized *ViewModel identifier used in the file
                // (hiltViewModel(), documentViewModel, etc. are lowercase
                // function/variable names — only class references are
                // capitalized).
                Regex("\\b([A-Z]\\w*ViewModel)\\b").findAll(content).forEach { m ->
                    val name = m.groupValues[1]
                    // Skip the HiltViewModel annotation (not a VM class)
                    if (name == "HiltViewModel") return@forEach
                    // Skip VM names that are file-local (declared in this file)
                    if (content.contains("class $name") || content.contains("interface $name")) return@forEach
                    if (name !in allVmNames) {
                        brokenRefs.add("${file.name}: $name")
                    }
                }
            }
        assertTrue(
            brokenRefs.isEmpty(),
            "Screens reference ViewModels that don't exist: $brokenRefs"
        )
    }

    @Test
    fun `every screen that collects a state flow has a ViewModel reference`() {
        // Screens that legitimately render without a VM (static/visual only)
        val noVmScreens = setOf(
            "EmptyChatState.kt",
            "LoadingState.kt",
            "ErrorState.kt",
        )
        val suspicious = mutableListOf<String>()
        screenDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith("Screen.kt") && it.name !in noVmScreens }
            .forEach { file ->
                val content = file.readText()
                val usesStateFlow = content.contains("collectAsState") ||
                    content.contains("StateFlow") ||
                    content.contains("state.")
                val hasVm = content.contains("hiltViewModel") ||
                    content.contains("viewModel(") ||
                    content.contains("ViewModel")
                if (usesStateFlow && !hasVm) {
                    suspicious.add(file.name)
                }
            }
        assertTrue(
            suspicious.isEmpty(),
            "Screens that collect state but have no ViewModel reference: $suspicious"
        )
    }
}
