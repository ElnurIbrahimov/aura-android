package com.aura.agent

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SilentRunCatchingAuditTest {

    private val projectRoot = File(System.getProperty("user.dir"))
    private val mainSource = File(projectRoot, "src/main/kotlin/com/aura")

    private val allowList: Set<String> = setOf(
        // none currently — all production runCatching sites are handled
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
        assertEquals(emptySet<String>(), allowList)
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
