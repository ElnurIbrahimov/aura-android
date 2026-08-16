package com.aura.tools

import com.aura.agent.requireNonEmpty
import com.aura.agent.sourceDir
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Every tool that returns text fetched from the open internet must sit in
 * [ToolCategories.WEB], because that category is what decides whether its output
 * reaches the model under the untrusted-data directive.
 *
 * `MemoryAugmentedAgenticLoop.frameToolResult` keys the framing off `category`
 * deliberately — one fact rather than two that can drift. The cost of that
 * choice is that `category` now carries a security meaning on top of its
 * original one, which is grouping rows in the Tools browser. Someone tidying
 * that screen could move `firecrawl_fetch` to a new "research" row and silently
 * unframe attacker-controlled content, with every existing test still green.
 *
 * This test is the thing that stops them. It pins the membership, so changing it
 * is a deliberate, visible act rather than a side effect. If a tool genuinely
 * belongs somewhere else in the browser, the fix is to give framing its own flag
 * on `Tool` — not to quietly edit the list below.
 *
 * Scans source rather than a live registry because the registry needs Hilt and
 * fifteen constructors' worth of mocks, and the fact under test is written in
 * the source anyway.
 */
class ToolFramingAuditTest {

    /**
     * Tool sources whose output is, or can contain, text fetched from a URL.
     *
     * `open_browser_tab` and `http_file_write` are in this set because they are
     * declared `category = "web"`, not because their short status strings are
     * dangerous. The set records what is categorised, not what is hostile.
     */
    private val expectedWebTools = setOf(
        "BraveSearchTool.kt",
        "DdgInstantAnswerTool.kt",
        "DeepResearchTool.kt",
        "FirecrawlFetchTool.kt",
        "HttpFileReadTool.kt",
        "HttpFileWriteTool.kt",
        "JinaReaderFreeTool.kt",
        "OpenBrowserTabTool.kt",
        "ParallelResearchTool.kt",
        "SearxngSearchTool.kt",
        "TavilySearchTool.kt",
        "WebSearchCapabilityTool.kt",
        "WebSearchTool.kt",
        "WikipediaReadTool.kt",
        "WikipediaSearchTool.kt",
    )

    private val webCategory = Regex("""category\s*=\s*"web"""")

    @Test
    fun `the set of web-categorised tools has not changed`() {
        val toolsDir = sourceDir("src/main/kotlin/com/aura/tools")
        val sources = toolsDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
            .requireNonEmpty("no tool sources found under ${toolsDir.absolutePath}")

        val actual = sources
            .filter { webCategory.containsMatchIn(it.readText()) }
            .map { it.name }
            .toSet()

        assertEquals(
            expectedWebTools,
            actual,
            "The web-tool set changed. Framing of attacker-controlled tool output keys off " +
                "category == ToolCategories.WEB (see MemoryAugmentedAgenticLoop.frameToolResult). " +
                "Removing a tool from this set stops framing its output; adding one starts. " +
                "Confirm that is intended, then update this list.",
        )
    }
}
