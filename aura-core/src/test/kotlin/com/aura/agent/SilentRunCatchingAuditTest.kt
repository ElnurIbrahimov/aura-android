package com.aura.agent

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SilentRunCatchingAuditTest {

    private val projectRoot = File(System.getProperty("user.dir"))
    private val mainSource = File(projectRoot, "src/main/kotlin/com/aura")

    private val allowList: Set<String> = setOf(
        // DreamConsolidator Phase 6: the outer runCatching spans ~55 lines
        // (LLM call + JSON parse + merge). The .onFailure handler is at the
        // end of the block — beyond the audit's 40-line scan window. The
        // block IS handled: .onFailure { Log.w(...) }.getOrDefault(false).
        // (Line drifts when the file above it changes; keep in sync.)
        "DreamConsolidator.kt:621",
    )

    @Test
    fun `no unhandled runCatching in production code`() {
        val violations = mutableListOf<String>()
        mainSource.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val lines = file.readText().lines()
            var i = 0
            while (i < lines.size) {
                if (lines[i].contains("runCatching")) {
                    val block = buildString {
                        for (j in i until minOf(i + 40, lines.size)) {
                            appendLine(lines[j])
                        }
                    }
                    val handled = handlerKeywords.any { block.contains(it) }
                    if (!handled) {
                        val key = file.name + ":" + (i + 1)
                        if (key !in allowList) violations.add(key)
                    }
                }
                i++
            }
        }
        if (violations.isNotEmpty()) {
            throw AssertionError(
                "Found " + violations.size + " unhandled runCatching block(s).\n" +
                    violations.joinToString("\n") +
                    "\nAdd .onFailure { Log.w(tag, it.message) } or add to allowList with justification."
            )
        }
        assertTrue(violations.isEmpty())
    }

    @Test
    fun `allowList is documented`() {
        assertEquals(1, allowList.size)
        assertTrue(allowList.all { it.contains(":") && it.contains(".kt") })
    }

    companion object {
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
