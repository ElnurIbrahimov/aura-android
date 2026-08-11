package com.aura.ui.theme

import com.aura.testing.requireNonEmpty
import com.aura.testing.sourceDir
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `AuraTokens.Dark` / `AuraTokens.Light` are the raw palettes. Reading one
 * outside `ui/theme` hardcodes a colour for one theme and leaves the other
 * wrong, which no test that renders anything would catch on a JVM run.
 *
 * The scan is guarded rather than merely resolved. The previous version located
 * the root correctly but then asserted `violations.isEmpty()` over whatever the
 * walk produced — and an empty walk satisfies that assertion perfectly, which is
 * the exact defect ENGINEERING_HISTORY §2.6 records. Both halves are needed:
 * [sourceDir] makes an unresolvable root fatal, [requireNonEmpty] makes an
 * empty result fatal.
 */
class AuraPaletteBoundaryTest {

    @Test
    fun `production code cannot read raw light or dark palettes outside theme`() {
        val sourceRoot = sourceDir("src/main/kotlin")

        val scanned = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.invariantSeparatorsPath.contains("/ui/theme/") }
            .requireNonEmpty("Kotlin sources outside ui/theme")

        val violations = scanned.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (Regex("AuraTokens\\.(Dark|Light)").containsMatchIn(line)) {
                    "${file.relativeTo(sourceRoot).invariantSeparatorsPath}:${index + 1}"
                } else {
                    null
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Raw palette access must stay inside ui/theme; violations: ${violations.joinToString()}",
        )
    }
}
