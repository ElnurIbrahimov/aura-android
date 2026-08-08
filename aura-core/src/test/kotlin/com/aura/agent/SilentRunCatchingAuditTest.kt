package com.aura.agent

import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Every `runCatching` in production code must handle its Result.
 *
 * The audit reads a fixed window of lines after each `runCatching` and looks
 * for a handler. A block longer than the window is a false positive, so there
 * is an escape hatch — but it is an in-source marker, not a list of line
 * numbers here.
 *
 * The exemption used to be `setOf("DreamConsolidator.kt:621")`, whose own
 * comment conceded "line drifts when the file above it changes; keep in sync."
 * It drifted the first time anything above it was edited, and the failure
 * pointed at a line that was correct — which is worse than no gate, because it
 * trains you to bump the number instead of reading the block. Marking the
 * source is stable under every edit that does not touch the block itself.
 */
class SilentRunCatchingAuditTest {

    private val mainSource = sourceDir("src/main/kotlin/com/aura")

    @Test
    fun `no unhandled runCatching in production code`() {
        val violations = mutableListOf<String>()
        var scanned = 0
        mainSource.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val lines = file.readText().lines()
            for (i in lines.indices) {
                if (!lines[i].contains("runCatching")) continue
                scanned++
                val block = buildString {
                    for (j in i until minOf(i + SCAN_WINDOW_LINES, lines.size)) appendLine(lines[j])
                }
                val handled = handlerKeywords.any { block.contains(it) }
                // Blocks longer than the scan window opt out in the source,
                // next to the code, where the justification can be read. The
                // marker usually reads best as a comment ABOVE the runCatching,
                // so look backwards as well as forwards.
                val nearby = lines.subList(
                    maxOf(0, i - EXEMPTION_LOOKBEHIND_LINES),
                    minOf(lines.size, i + EXEMPTION_LOOKAHEAD_LINES),
                )
                val exempt = nearby.any { EXEMPTION_MARKER in it }
                if (!handled && !exempt) violations.add("${file.name}:${i + 1}")
            }
        }

        assertTrue(scanned > 0, "scanned no runCatching blocks at all — the audit is vacuous, check ${mainSource.absolutePath}")
        assertTrue(
            violations.isEmpty(),
            "Found ${violations.size} unhandled runCatching block(s):\n" +
                violations.joinToString("\n") +
                "\nAdd .onFailure { Log.w(tag, it.message, it) }, or — if the handler is real but " +
                "further than $SCAN_WINDOW_LINES lines below — put `$EXEMPTION_MARKER <why>` on the " +
                "runCatching line or just above it.",
        )
    }

    @Test
    fun `every exemption carries a justification`() {
        val undocumented = mutableListOf<String>()
        var exemptions = 0
        mainSource.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            file.readText().lines().forEachIndexed { index, line ->
                if (EXEMPTION_MARKER !in line) return@forEachIndexed
                exemptions++
                val justification = line.substringAfter(EXEMPTION_MARKER).trim()
                if (justification.length < MIN_JUSTIFICATION_CHARS) {
                    undocumented.add("${file.name}:${index + 1} — \"$justification\"")
                }
            }
        }

        assertTrue(
            undocumented.isEmpty(),
            "$EXEMPTION_MARKER needs a real reason (>= $MIN_JUSTIFICATION_CHARS chars), not a bare marker:\n" +
                undocumented.joinToString("\n"),
        )
        // Not an upper bound on purpose: capping the count would just push the
        // next exemption into a silent `.getOrNull()` instead.
        assertTrue(exemptions >= 0)
    }

    companion object {
        /** How far below a `runCatching` the audit looks for its handler. */
        private const val SCAN_WINDOW_LINES = 40

        /** How far into a block an exemption marker may sit to count. */
        private const val EXEMPTION_LOOKAHEAD_LINES = 2

        /** How far above a `runCatching` an exemption comment may sit. */
        private const val EXEMPTION_LOOKBEHIND_LINES = 4

        private const val EXEMPTION_MARKER = "runCatching-audit:"

        private const val MIN_JUSTIFICATION_CHARS = 20

        private val handlerKeywords = listOf(
            ".onFailure",
            ".onSuccess",
            ".getOrElse",
            ".getOrDefault",
            ".getOrNull",
            ".getOrThrow",
            ".fold(",
            "Result.success(",
            "Result.failure(",
        )
    }
}
