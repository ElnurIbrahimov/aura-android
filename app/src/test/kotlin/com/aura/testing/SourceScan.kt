package com.aura.testing

import java.io.File

/**
 * Helpers for tests that read the source tree instead of running the code.
 *
 * A handful of architectural contracts have no runtime equivalent — "every
 * navigate target has a composable registration", "no screen reads the raw
 * palette", "nested Scaffolds opt out of system insets" — so scanning the
 * sources is the only way to check them. The hazard is that such a test passes
 * just as happily when it finds nothing to scan: resolve the wrong directory,
 * get an empty file list, and every `violations.isEmpty()` assertion succeeds
 * trivially while having examined nothing.
 *
 * ENGINEERING_HISTORY §2.6 records this exact defect being found in
 * `NavigationReachabilityTest` ("a hardcoded path and a bare `return` when
 * nothing matched") and fixed there. It survived in the app module's screen
 * contract tests, one of which still carried a literal
 * `if (!dir.exists()) continue`.
 *
 * Use [sourceDir] rather than `File(System.getProperty("user.dir"), …)`, and
 * [requireNonEmpty] on any scan whose result feeds an emptiness assertion.
 * Mirrors `com.aura.agent.SourceScan` in :aura-core — duplicated because the
 * two modules share no test source set, and adding one for ~40 lines would
 * cost more than it saves.
 */
internal fun sourceDir(relative: String): File {
    val cwd = System.getProperty("user.dir")
    // Gradle runs unit tests with the module directory as the working
    // directory; IDE runners and other harnesses often use the repo root.
    // Accept both rather than assuming, and fail loudly when neither resolves.
    val candidates = listOf(
        File(cwd, relative),
        File(File(cwd), "app/$relative"),
        File(File(cwd), "aura-core/$relative"),
    )
    return candidates.firstOrNull { it.isDirectory }
        ?: error(
            "source dir '$relative' not found from cwd=$cwd. Tried:\n" +
                candidates.joinToString("\n") { " - ${it.absolutePath}" } +
                "\nA source-scanning test that cannot find its sources passes vacuously, so this is fatal.",
        )
}

/** Fail loudly when a source scan matched nothing — an empty scan proves nothing. */
internal fun <T> List<T>.requireNonEmpty(what: String): List<T> = also {
    check(it.isNotEmpty()) { "source scan matched no $what — the scan is vacuous, so its assertions prove nothing" }
}

/** Same guard for a lazily-walked sequence; returns the realised list. */
internal fun <T> Sequence<T>.requireNonEmpty(what: String): List<T> = toList().requireNonEmpty(what)
