package com.aura.testing

import org.junit.Test
import kotlin.test.assertTrue

/**
 * Cards must use [com.aura.ui.components.AuraCard], not Material's `Card`.
 *
 * `AuraCard` was written, styled correctly against the theme tokens — `surface1`,
 * a hairline `borderSubtle`, `cardRadius` — and then had **zero callers** for its
 * entire existence, while eighteen sites drew a bare `Card {}` and got Material3's
 * default `surfaceContainerLow`: a light flat grey that sits wrong on Aura's
 * near-black ground and reads as borrowed UI.
 *
 * That is this repo's signature defect wearing a different hat. `WorkerRunRecorder.prune()`
 * had a unit test and no caller; `allowedScopes` was a setting that decided nothing;
 * `SceneContextBuilder` documented eight context sections while its only caller
 * passed six. A shared component nothing calls is just a file, and the screens
 * drift apart in the meantime — which is exactly what happened: chat, memory,
 * tasks and hands look designed, while Dreams, Mind, World Model, Taste, Beliefs
 * and the evolution screens looked like a different app.
 *
 * A `Card` that sets its own `colors`/`containerColor` is allowed: that is a
 * deliberate choice, not an accidental default, and `TasksScreen` uses one for a
 * selected-state chip.
 */
class AuraCardIsUsedTest {

    /** `Card(` but not `AuraCard(`, and not a qualified `foo.Card(`. */
    private val bareCard = Regex("""(?<![A-Za-z.])Card\s*\(""")

    @Test
    fun `no screen uses Material's Card without setting its colours`() {
        val root = sourceDir("src/main/kotlin/com/aura")
        val offenders = mutableListOf<String>()
        var scanned = 0

        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                scanned++
                val text = file.readText()
                for (match in bareCard.findAll(text)) {
                    val window = text.substring(match.range.last, minOf(text.length, match.range.last + 240))
                    if ("colors" in window || "containerColor" in window) continue
                    val line = text.take(match.range.first).count { it == '\n' } + 1
                    offenders += "${file.name}:$line"
                }
            }

        // A scan that reads nothing passes for the wrong reason — the defect
        // four of this repo's source-scanning tests shipped with.
        assertTrue(scanned > 50, "only scanned $scanned files; this test is reading the wrong tree")

        assertTrue(
            offenders.isEmpty(),
            "these use Material's Card and will render its default grey instead of the app's " +
                "surface. Use AuraCard, or set colors explicitly if the grey is deliberate:\n  " +
                offenders.joinToString("\n  "),
        )
    }
}
