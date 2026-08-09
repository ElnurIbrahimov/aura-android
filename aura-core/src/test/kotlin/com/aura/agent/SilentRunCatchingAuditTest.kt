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
            val rawLines = file.readText().lines()
            // Call sites and handlers are looked for in CODE only. A
            // `runCatching` written in prose is not a call site, and flagging
            // one made the audit punish explaining what the code does — which is
            // how a rule stops being kept and starts being worked around.
            // Blanking preserves line numbering, so violations still point at
            // the right line.
            val codeLines = withoutComments(file.readText()).lines()
            for (i in codeLines.indices) {
                if (!codeLines[i].contains("runCatching")) continue
                scanned++
                val block = buildString {
                    for (j in i until minOf(i + SCAN_WINDOW_LINES, codeLines.size)) appendLine(codeLines[j])
                }
                val handled = handlerKeywords.any { block.contains(it) }
                // The exemption marker is read from the RAW lines: it lives in a
                // comment by design, so the blanked copy cannot see it.
                // Blocks longer than the scan window opt out in the source,
                // next to the code, where the justification can be read. The
                // marker usually reads best as a comment ABOVE the runCatching,
                // so look backwards as well as forwards.
                val nearby = rawLines.subList(
                    maxOf(0, i - EXEMPTION_LOOKBEHIND_LINES),
                    minOf(rawLines.size, i + EXEMPTION_LOOKAHEAD_LINES),
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

    /**
     * Blank out comments while keeping every line where it was.
     *
     * Block comments become the same number of blank lines and `//` runs to end
     * of line, so a violation's reported line number still matches the file.
     * Not a Kotlin lexer — a `//` inside a string literal is blanked too, which
     * for this scan is harmless.
     */
    private fun withoutComments(source: String): String =
        source
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)) { match ->
                "\n".repeat(match.value.count { it == '\n' })
            }
            .replace(Regex("""//[^\n]*"""), "")

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
