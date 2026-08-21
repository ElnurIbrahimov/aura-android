package com.aura.agent

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A tool that duplicates another must not reach the model's schema.
 *
 * Three pairs did. `image_generate` sat beside `image_gen`, `text_to_speech`
 * beside `tts_speak`, `web_search_capability` beside `web_search` — same job,
 * different names, and in the image pair a difference the model cannot see:
 * one falls back when nothing is configured and the other reports
 * `no_provider` and stops. Which one it picked decided whether the user got a
 * result.
 *
 * `filterSearchTools` existed to solve exactly this for Tavily and Brave and
 * was never extended, because it was named after search rather than after the
 * problem.
 *
 * This asserts against the registry rather than a hardcoded list, so a tool
 * added to `SHADOWED_TOOLS` that no longer exists is caught here as a stale
 * entry rather than quietly excusing nothing.
 */
class ShadowedToolsAreNotOfferedTest {

    private fun source(): String =
        java.io.File("src/main/kotlin/com/aura/agent/MemoryAugmentedAgenticLoop.kt")
            .also { require(it.isFile) { "reading the wrong path: ${it.absolutePath}" } }
            .readText()

    private fun shadowed(): Set<String> {
        val block = Regex("""private val SHADOWED_TOOLS = setOf\(([^)]*)\)""")
            .find(source())
            ?.groupValues?.get(1)
            ?: error("SHADOWED_TOOLS not found — this test is reading the wrong shape")
        return Regex(""""([\w_]+)"""").findAll(block).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `every shadowed name belongs to a tool that actually exists`() {
        // A name that matches nothing is an allowlist entry excusing a tool that
        // is already gone, and it hides the fact that the real duplicate is
        // still being offered.
        val toolsDir = java.io.File("src/main/kotlin/com/aura/tools")
        val declared = toolsDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { Regex("""name = "([\w_]+)"""").findAll(it.readText()) }
            .map { it.groupValues[1] }
            .toSet()

        val stale = shadowed().filterNot { it in declared }.sorted()
        assertTrue(
            stale.isEmpty(),
            "SHADOWED_TOOLS names ${stale.size} tool(s) that no longer exist: $stale",
        )
    }

    @Test
    fun `each shadowed tool has a surviving twin that does the same job`() {
        // The point is not to hide tools, it is to leave exactly one of each
        // pair on the wire. If a twin disappears, the survivor list is wrong and
        // the capability has silently left the schema entirely.
        val twins = mapOf(
            "tavily_search" to "web_search",
            "brave_search" to "web_search",
            "web_search_capability" to "web_search",
            "image_generate" to "image_gen",
            "text_to_speech" to "tts_speak",
        )
        assertEquals(
            twins.keys,
            shadowed(),
            "SHADOWED_TOOLS and the twin map disagree — one of them was edited alone",
        )

        val toolsDir = java.io.File("src/main/kotlin/com/aura/tools")
        val declared = toolsDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { Regex("""name = "([\w_]+)"""").findAll(it.readText()) }
            .map { it.groupValues[1] }
            .toSet()

        val orphaned = twins.values.toSet().filterNot { it in declared }.sorted()
        assertTrue(
            orphaned.isEmpty(),
            "shadowed tools point at ${orphaned.size} survivor(s) that no longer exist: $orphaned",
        )
    }
}
