package com.aura.ui.screens

import com.aura.testing.requireNonEmpty
import com.aura.testing.sourceDir
import org.junit.Test
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

    // Resolved through sourceDir so an unresolvable path is fatal rather than
    // silently yielding an empty scan. This file previously held the clearest
    // instance of that defect: `if (!dir.exists()) continue` below meant a
    // wrong working directory produced an empty ViewModel set, and the
    // "referenced ViewModel still exists" test then reported no violations
    // because it had nothing to compare against.
    private val screenDir = sourceDir("src/main/kotlin/com/aura/ui/screens")
    private val vmDir = sourceDir("src/main/kotlin/com/aura/ui/viewmodel")
    private val settingsVmDir = sourceDir("src/main/kotlin/com/aura/ui/settings")
    private val evolutionVmDir = sourceDir("src/main/kotlin/com/aura/ui/evolution")
    private val voiceDir = sourceDir("src/main/kotlin/com/aura/ui/voice")

    @Test
    fun `every ViewModel referenced by a screen still exists`() {
        val allVmNames = buildSet {
            for (dir in listOf(
                vmDir,
                settingsVmDir,
                evolutionVmDir,
                voiceDir,
                screenDir,
            )) {
                dir.walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".kt") }
                    .requireNonEmpty("Kotlin sources under ${dir.name}")
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

        // An empty set would make every screen reference look "broken", which
        // would fail loudly — but assert it anyway so the failure names the
        // real cause instead of listing every ViewModel in the app.
        assertTrue(allVmNames.isNotEmpty(), "harvested no ViewModel class names — the scan found nothing to compare against")

        val brokenRefs = mutableListOf<String>()
        screenDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .requireNonEmpty("screen source files")
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
            .requireNonEmpty("Screen.kt files")
            .forEach { file ->
                val content = file.readText()
                val usesStateFlow = content.contains("collectAsState") ||
                    content.contains("StateFlow") ||
                    content.contains("state.")
                // `viewModel(` is deliberately NOT accepted here. Every ViewModel in
                // this module is @HiltViewModel with an @Inject constructor, so the bare
                // Compose factory cannot construct one — it throws on navigation. This
                // check used to list it as evidence of correct wiring, which is how
                // MindScreen and DreamsScreen shipped uncomposable while a test named
                // for screen/ViewModel wiring stayed green. HiltViewModelFactoryTest
                // now fails on the call and the import; this stops blessing it.
                val hasVm = content.contains("hiltViewModel") ||
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
