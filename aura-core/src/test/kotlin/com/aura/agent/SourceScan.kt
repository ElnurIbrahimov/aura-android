package com.aura.agent

import java.io.File

/**
 * Helpers for tests that read the source tree instead of running the code.
 *
 * A handful of architectural contracts have no runtime equivalent — "no
 * unhandled runCatching", "no screen reads the raw palette" — so the only way
 * to check them is to scan the sources. The hazard is that such a test passes
 * just as happily when it finds nothing to scan: resolve the wrong directory,
 * get an empty file list, and every `violations.isEmpty()` assertion succeeds
 * trivially while reporting nothing. ENGINEERING_HISTORY §2.6 records exactly
 * this defect being found and removed from one test; it survived in others.
 *
 * These two helpers make the vacuous case loud. Use [sourceDir] instead of
 * `File(System.getProperty("user.dir"), …)`, and [requireNonEmpty] on any scan
 * whose result feeds an emptiness assertion.
 */
internal fun sourceDir(relative: String): File {
    val cwd = System.getProperty("user.dir")
    // Gradle runs unit tests with the module directory as the working
    // directory, but IDE runners and other harnesses use the repo root. Accept
    // both rather than assuming, and fail loudly when neither resolves.
    val candidates = listOf(
        File(cwd, relative),
        File(File(cwd), "aura-core/$relative"),
        File(File(cwd), "app/$relative"),
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
