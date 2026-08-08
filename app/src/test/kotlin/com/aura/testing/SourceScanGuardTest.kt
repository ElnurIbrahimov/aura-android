package com.aura.testing

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Proves the source-scan guards actually fire.
 *
 * Every scanning test asserts "the violations list is empty", which an
 * unresolvable source directory satisfies having read nothing. These two
 * cases pin that the guards turn that silent pass into a loud failure —
 * without them, this file would have nothing to assert.
 */
class SourceScanGuardTest {

    @Test
    fun `an unresolvable source dir is fatal, not an empty scan`() {
        val failure = runCatching { sourceDir("src/main/kotlin/com/aura/definitely_not_a_package") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException, "expected a hard failure, got: $failure")
        assertTrue(
            failure.message.orEmpty().contains("passes vacuously"),
            "the message must explain WHY this is fatal, got: ${failure?.message}",
        )
    }

    @Test
    fun `a scan that matches nothing is fatal, not a pass`() {
        val failure = runCatching { emptyList<String>().requireNonEmpty("Screen.kt files") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException, "expected a hard failure, got: $failure")
        assertTrue(
            failure.message.orEmpty().contains("vacuous"),
            "the message must name the vacuous scan, got: ${failure?.message}",
        )
    }

    @Test
    fun `a resolvable source dir still works`() {
        val dir = sourceDir("src/main/kotlin/com/aura/ui/screens")
        assertTrue(dir.isDirectory)
        assertTrue(dir.walkTopDown().any { it.name.endsWith("Screen.kt") }, "expected screens under ${dir.absolutePath}")
    }
}
