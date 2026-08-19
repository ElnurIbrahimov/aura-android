package com.aura.testing

import org.junit.Test
import kotlin.test.assertTrue

/**
 * Screens must default their ViewModels with `hiltViewModel()`, never the bare
 * `androidx.lifecycle.viewmodel.compose.viewModel()`.
 *
 * Every ViewModel in this module — all 41 of them — is `@HiltViewModel` with an
 * `@Inject constructor` that takes DAOs and stores. Inside a `NavHost` the
 * `LocalViewModelStoreOwner` is the `NavBackStackEntry`, whose default factory is
 * `SavedStateViewModelFactory`: it can build `()`, `(SavedStateHandle)` and
 * `(Application)` constructors and nothing else. Hilt's factory is installed *only*
 * by `hiltViewModel()`. So the bare call does not fall back, degrade, or render
 * empty — it throws `RuntimeException: Cannot create an instance of class X` the
 * moment the route is opened.
 *
 * `MindScreen` shipped with lines 72 and 75 calling `hiltViewModel()` and lines 73
 * and 74 calling `viewModel()`, three lines apart. `DreamsScreen` had the same
 * defect. Both sit two taps from a bottom-nav tab — Settings → "What Aura thinks",
 * and Memory → the routine and contradiction chips — and both were unopenable for
 * their entire existence.
 *
 * Nothing caught it, and the reason is worth writing down. 3,387 unit tests do not
 * compose a screen; `ui-test-junit4` is on the androidTest classpath only, so they
 * structurally cannot. `ScreenViewModelWiringTest` accepted `viewModel(` as proof
 * of correct wiring, so the one test named for this invariant scored the bug as a
 * pass. And `ProjectSpineIsWiredTest` — the gate written specifically to catch
 * subsystems that are built, tested and reachable by nothing — asserts that
 * MindScreen renders the project ledger, and is green, about a screen that cannot
 * be opened at all. A test can only see what it looks at.
 *
 * This gate bans the import as well as the call. That is deliberate: the import is
 * the stronger signal, it cannot be evaded by qualifying the call, and it also
 * catches the dead-import case — `TasteSection.kt` and `WorldModelSection.kt` both
 * carried the import with no call site, one edit away from becoming the same bug.
 *
 * If a non-Hilt ViewModel is ever genuinely needed, this test should be changed
 * deliberately, with the reason recorded — not because it went red.
 */
class HiltViewModelFactoryTest {

    /** `viewModel(` but not `hiltViewModel(`, and not a qualified `foo.viewModel(`. */
    private val bareFactoryCall = Regex("""(?<![A-Za-z.])viewModel\s*\(""")

    private val bareFactoryImport = "androidx.lifecycle.viewmodel.compose.viewModel"

    @Test
    fun `no screen defaults a ViewModel with the non-Hilt factory`() {
        val root = sourceDir("src/main/kotlin/com/aura")
        val offenders = mutableListOf<String>()
        var scanned = 0

        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                scanned++
                val path = file.relativeTo(root).invariantSeparatorsPath
                file.readLines().forEachIndexed { index, line ->
                    if (line.trimStart().startsWith("import ") && bareFactoryImport in line) {
                        offenders += "$path:${index + 1}  (import)"
                    } else if (bareFactoryCall.containsMatchIn(line)) {
                        offenders += "$path:${index + 1}  $line"
                    }
                }
            }

        // A scan that reads nothing passes for the wrong reason — the defect four of
        // this repo's source-scanning tests shipped with.
        assertTrue(scanned > 50, "only scanned $scanned files; this test is reading the wrong tree")

        assertTrue(
            offenders.isEmpty(),
            "These use Compose's plain viewModel() factory. Every ViewModel here is " +
                "@HiltViewModel with an @Inject constructor, so that factory cannot build " +
                "one and the screen throws the moment it is navigated to. Use " +
                "androidx.hilt.navigation.compose.hiltViewModel() instead, and drop the " +
                "$bareFactoryImport import:\n  " +
                offenders.joinToString("\n  "),
        )
    }
}
